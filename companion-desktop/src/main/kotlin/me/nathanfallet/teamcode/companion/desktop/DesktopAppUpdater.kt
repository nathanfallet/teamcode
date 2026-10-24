package me.nathanfallet.teamcode.companion.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.teamcode.api.models.ReleaseFormat
import me.nathanfallet.teamcode.companion.domain.services.AppUpdateException
import me.nathanfallet.teamcode.companion.domain.services.AppUpdater

/**
 * The update banner's work, done by the installed CLI so that every release goes through the CLI's own client and
 * signature checks (the app never downloads or verifies a release itself):
 * - the check is `teamcode update --check --json` (or, for a CLI older than `--json`, the text of `update --check`);
 * - the update is `teamcode update` (managed installation; running agent sessions keep their version and the local
 *   service restarts when they end), then `teamcode ui --yes --restart-app PID`, which installs the app of the CLI's
 *   new version with the checks of `teamcode ui` (signed manifest, SHA-256, the Developer ID on macOS) and hands the
 *   reopening to a process of its own, started detached, which waits for this app to quit. On Windows, where a running
 *   app keeps its files open, that process also does the installation once the app has quit.
 * The app only reads the CLI's output; it never signals the CLI.
 */
class DesktopAppUpdater(
    private val locator: CliLocator,
    private val processes: CliProcesses,
    private val environment: DesktopEnvironment,
    override val appVersion: String,
    private val processId: Long = ProcessHandle.current().pid(),
    private val exit: () -> Unit = { kotlin.system.exitProcess(0) },
) : AppUpdater {
    @Volatile private var startupError: String? = environment.updateError

    override fun takeStartupError(): String? = synchronized(this) { startupError.also { startupError = null } }

    override suspend fun cliVersion(): String? = locator.find()?.version

    override suspend fun latestVersion(): String? {
        val cli = locator.find() ?: throw AppUpdateException("The TeamCode CLI is not installed.")
        val result = processes.run(cli.executable, listOf("update", "--check", "--json"), CHECK_TIMEOUT_MILLIS)
        if (result.exitCode == 0) return parseCheck(result.stdout)
        // A CLI from before --json: its text says the same, after the same signature checks.
        if (result.exitCode != null && unknownOption(result.stderr)) {
            val text = processes.run(cli.executable, listOf("update", "--check"), CHECK_TIMEOUT_MILLIS)
            if (text.exitCode == 0) return parseCheckText(text.stdout)
        }
        throw AppUpdateException("teamcode update --check failed.")
    }

    override suspend fun update(output: (String) -> Unit) {
        val cli = locator.find() ?: throw AppUpdateException(
            "The TeamCode CLI is not installed, and the app updates through it. Install it from New session › On this device, or with: " +
                CliLocator.installCommand(environment.os, appVersion),
        )
        output("Updating the TeamCode CLI ${cli.version}…")
        step(cli.executable, listOf("update"), UPDATE_TIMEOUT_MILLIS, output, "teamcode update")
        val updated = locator.find() ?: throw AppUpdateException("The TeamCode CLI no longer answers after its update. Run teamcode version in a terminal.")
        output("Installing TeamCode Desktop ${updated.version}…")
        step(updated.executable, listOf("ui", "--yes", "--restart-app", processId.toString()), INSTALL_TIMEOUT_MILLIS, output, "teamcode ui",
            tooOld = "TeamCode CLI ${updated.version} cannot update this app by itself. Quit TeamCode, then run teamcode ui --yes in a terminal.")
    }

    override fun quit() = exit()

    /** One CLI command: its lines go to [output]; a failure is said with the CLI's own last error lines. */
    private suspend fun step(executable: String, arguments: List<String>, timeoutMillis: Long, output: (String) -> Unit, name: String, tooOld: String? = null) {
        val errors = ArrayDeque<String>()
        val code = processes.stream(executable, arguments, timeoutMillis) { line, error ->
            if (line.isBlank()) return@stream
            output(line)
            if (error) synchronized(errors) { errors.addLast(line); if (errors.size > MAX_ERROR_LINES) errors.removeFirst() }
        }
        val lines = synchronized(errors) { errors.toList() }
        when {
            code == null -> throw AppUpdateException("$name is still running after ${timeoutMillis / 60_000} minutes; it is left to finish. Try again once it is done.")
            code == 0 -> return
            tooOld != null && unknownOption(lines.joinToString("\n")) -> throw AppUpdateException(tooOld)
            else -> throw AppUpdateException(lines.joinToString(" ").removePrefix("Error: ").trim().ifEmpty { "$name stopped with exit code $code." })
        }
    }

    companion object {
        private const val CHECK_TIMEOUT_MILLIS = 60_000L
        private const val UPDATE_TIMEOUT_MILLIS = 20 * 60_000L
        private const val INSTALL_TIMEOUT_MILLIS = 30 * 60_000L
        private const val MAX_ERROR_LINES = 4

        /** Clikt's refusal of an option an older CLI does not know. */
        fun unknownOption(stderr: String): Boolean = stderr.contains("no such option", ignoreCase = true)

        /** `teamcode update --check --json`: the authenticated latest version, null when none is published. */
        fun parseCheck(stdout: String): String? {
            val line = stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") } ?: throw AppUpdateException("Unexpected check output.")
            val report = try { Json.parseToJsonElement(line) as JsonObject } catch (_: Exception) { throw AppUpdateException("Unexpected check output.") }
            val latest = report["latestVersion"]?.jsonPrimitive?.contentOrNull ?: return null
            if (!ReleaseFormat.validVersion(latest) || report["updateAvailable"]?.jsonPrimitive?.booleanOrNull == null) throw AppUpdateException("Unexpected check output.")
            return latest
        }

        /** The text of an older `teamcode update --check`: `Verified release: 1.2.3 (commit)`, or no stable release. */
        fun parseCheckText(stdout: String): String? {
            if (stdout.contains("No stable published release")) return null
            val latest = Regex("""Verified release: (\S+)""").find(stdout)?.groupValues?.get(1) ?: throw AppUpdateException("Unexpected check output.")
            if (!ReleaseFormat.validVersion(latest)) throw AppUpdateException("Unexpected check output.")
            return latest
        }
    }
}
