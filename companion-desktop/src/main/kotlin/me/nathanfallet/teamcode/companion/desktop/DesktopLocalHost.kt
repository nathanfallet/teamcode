package me.nathanfallet.teamcode.companion.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.services.LocalAvailability
import me.nathanfallet.teamcode.companion.domain.services.LocalHost
import me.nathanfallet.teamcode.companion.domain.services.LocalHostException
import me.nathanfallet.teamcode.companion.domain.services.LocalSessionRequest
import me.nathanfallet.teamcode.companion.domain.services.LocalResumables
import me.nathanfallet.teamcode.companion.domain.services.LocalResumeBlock
import me.nathanfallet.teamcode.companion.domain.models.ResumableSession
import me.nathanfallet.teamcode.companion.domain.models.ResumableSessions
import me.nathanfallet.teamcode.api.models.LocalResumableSessionsView
import me.nathanfallet.teamcode.api.models.LocalResumeReasons
import me.nathanfallet.teamcode.api.models.ResumableSessions as ResumableRules

/**
 * "On this device" through the installed CLI: `teamcode remote start DIRECTORY --json --detach` creates the session
 * (kind `remote`, hosted by the CLI's device). The device's daemon hosts it, and starts it again when it dies or a
 * message wakes it; without the daemon the CLI starts its host in the background. Either way the host outlives the app,
 * which starts no host itself. Sessions here use the CLI's sign-in, so only the CLI's account can start them.
 *
 * A session that continues a Claude Code conversation of the folder (`--resume`) goes through the daemon only: the
 * conversations are listed by `teamcode remote resumable`, which reads them with the daemon's own code, and the daemon
 * checks the chosen one again and forks it, exactly as for a session started from another device.
 */
class DesktopLocalHost(
    private val locator: CliLocator,
    private val processes: CliProcesses,
    private val accounts: AccountRepository,
    private val environment: DesktopEnvironment,
    private val appVersion: String,
    private val installer: CliInstaller? = null,
    private val picker: suspend () -> String? = { pickDirectory(environment.os) },
) : LocalHost {
    @Volatile private var initialDirectory: String? = environment.initialDirectory

    override fun takeInitialDirectory(): String? = synchronized(this) { initialDirectory.also { initialDirectory = null } }

    override suspend fun installCli(output: (String) -> Unit) {
        val installer = installer?.takeIf { it.available } ?: throw LocalHostException("Install the TeamCode CLI with the command shown.")
        installer.install(output)
    }

    override suspend fun availability(accountId: String): LocalAvailability {
        val cli = locator.find() ?: return LocalAvailability.CliMissing(CliLocator.installCommand(environment.os, appVersion), installer?.available == true)
        if (!CliLocator.compatible(cli.version, appVersion)) return LocalAvailability.CliTooOld(cli.version, appVersion, "teamcode update")
        val account = accounts.accounts.value.firstOrNull { it.id == accountId }
        if (account?.usedByCli != true || !account.signedIn) {
            val cliAccount = accounts.accounts.value.firstOrNull { it.usedByCli && it.signedIn }
            return LocalAvailability.OtherAccount(cliAccount?.let { "${it.username} on ${it.origin.substringAfter("://")}" })
        }
        return LocalAvailability.Ready
    }

    override suspend fun pickDirectory(): String? = picker()

    override suspend fun start(accountId: String, request: LocalSessionRequest): String {
        val cli = locator.find() ?: throw LocalHostException("The TeamCode CLI is not installed.")
        val result = processes.run(cli.executable, startArguments(request), START_TIMEOUT_MILLIS)
        val id = sessionId(result.stdout)
        // Exit code 0, or still waiting for its host after the session was created: the session exists either way.
        if (id == null || (result.exitCode != 0 && result.exitCode != null)) throw LocalHostException(explanation(result))
        return id
    }

    override suspend fun resumable(accountId: String, directory: String, before: Long?): LocalResumables {
        val cli = locator.find() ?: throw LocalHostException("The TeamCode CLI is not installed.")
        return resumables(processes.run(cli.executable, resumableArguments(directory, before), RESUMABLE_TIMEOUT_MILLIS))
    }

    companion object {
        /** Two server answers (capabilities, devices) and a bounded read of the folder's transcripts. */
        private const val RESUMABLE_TIMEOUT_MILLIS = 30_000L
        /** `remote start` waits up to 30 s for its host; the rest is the server's answers and the CLI's own start. */
        private const val START_TIMEOUT_MILLIS = 90_000L
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The command line: the directory, the agent, the name and the options, each its own argument. The first message
         * is not on it (another process of the device could read it there): the app sends it through the server.
         */
        fun startArguments(request: LocalSessionRequest): List<String> = buildList {
            addAll(listOf("remote", "start", request.directory, "--agent", request.agent, "--name", request.name))
            request.model.takeIf { it.isNotBlank() }?.let { addAll(listOf("--model", it)) }
            request.effort.takeIf { it.isNotBlank() }?.let { addAll(listOf("--effort", it)) }
            if (request.bypassPermissions) add("--bypass-permissions")
            request.resumeSessionId?.takeIf { request.agent == "claude" }?.let { addAll(listOf("--resume", it)) }
            addAll(listOf("--json", "--detach"))
        }

        /** `teamcode remote resumable DIRECTORY --json`, from [before] for an older page. */
        fun resumableArguments(directory: String, before: Long?): List<String> = buildList {
            addAll(listOf("remote", "resumable", directory, "--json"))
            before?.let { addAll(listOf("--before", it.toString())) }
        }

        /**
         * What `remote resumable --json` answered. A CLI without the command is [LocalResumeBlock.CLI_TOO_OLD]; any other
         * failure is the CLI's own sentence. Ids that are not Claude Code session ids are dropped, titles kept to their bound.
         */
        fun resumables(result: CliResult): LocalResumables {
            val line = result.stdout.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("{") }
            if (line == null || result.exitCode != 0) {
                if ("no such subcommand" in result.stderr) return LocalResumables.Unavailable(LocalResumeBlock.CLI_TOO_OLD)
                throw LocalHostException(explanation(result))
            }
            val view = try { json.decodeFromString<LocalResumableSessionsView>(line) }
                catch (_: Exception) { throw LocalHostException("The TeamCode CLI gave an answer this app does not read: run teamcode update.") }
            view.unavailable?.let { reason ->
                return LocalResumables.Unavailable(when (reason) {
                    LocalResumeReasons.SERVER_UNSUPPORTED -> LocalResumeBlock.SERVER
                    LocalResumeReasons.PLATFORM_UNSUPPORTED -> LocalResumeBlock.PLATFORM
                    LocalResumeReasons.DAEMON_OFFLINE -> LocalResumeBlock.DAEMON_OFFLINE
                    else -> LocalResumeBlock.DAEMON_TOO_OLD
                })
            }
            val sessions = view.sessions.filter { ResumableRules.ID.matches(it.id) }.take(ResumableRules.MAX_LIMIT)
                .map { ResumableSession(it.id, it.title.take(ResumableRules.TITLE_LENGTH), it.lastActiveAt, it.turns) }
            return LocalResumables.Page(ResumableSessions(sessions, view.nextBefore))
        }

        /** The created session's id, from the JSON line `--json` prints on standard output. */
        fun sessionId(stdout: String): String? = stdout.lineSequence().map { it.trim() }.filter { it.startsWith("{") }.firstNotNullOfOrNull { line ->
            try { (json.parseToJsonElement(line) as? JsonObject)?.get("id")?.jsonPrimitive?.content } catch (_: Exception) { null }
        }?.takeIf { Regex("[a-zA-Z0-9-]{1,36}").matches(it) }

        /** The CLI's last error line (its own sentence: TeamCode's messages never carry a credential), bounded. */
        fun explanation(result: CliResult): String {
            if (result.exitCode == null) return "The TeamCode CLI did not answer in time."
            val line = result.stderr.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.removePrefix("Error: ")?.removePrefix("error: ")
            return line?.take(400) ?: "The TeamCode CLI could not start the session (exit code ${result.exitCode})."
        }

        /** The system's folder picker: the native one on macOS, Swing's elsewhere. */
        suspend fun pickDirectory(os: DesktopEnvironment.Os): String? = withContext(Dispatchers.Swing) {
            if (os == DesktopEnvironment.Os.MACOS) {
                System.setProperty("apple.awt.fileDialogForDirectories", "true")
                try {
                    val dialog = FileDialog(null as Frame?, "Choose the folder the agent works in", FileDialog.LOAD)
                    dialog.isVisible = true
                    val directory = dialog.directory ?: return@withContext null
                    val file = dialog.file ?: return@withContext null
                    File(directory, file).absolutePath
                } finally { System.setProperty("apple.awt.fileDialogForDirectories", "false") }
            } else {
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = "Choose the folder the agent works in"
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
            }
        }
    }
}
