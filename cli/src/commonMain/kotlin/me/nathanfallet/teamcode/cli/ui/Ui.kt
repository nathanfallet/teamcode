package me.nathanfallet.teamcode.cli.ui

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.mordant.terminal.Terminal
import io.ktor.client.HttpClient
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.VERSION
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.cli.platform.hostOs
import me.nathanfallet.teamcode.cli.platform.runInteractiveProcess
import me.nathanfallet.teamcode.cli.platform.servicePlatform
import me.nathanfallet.teamcode.cli.platform.waitForProcessExit
import me.nathanfallet.teamcode.cli.describeFailure
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.cli.artifacts.ensurePrivateDirectory
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import okio.Buffer
import okio.IOException
import me.nathanfallet.teamcode.cli.platform.startDetachedProcess
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import me.nathanfallet.teamcode.cli.remote.remoteDirectory
import me.nathanfallet.teamcode.cli.service.ServiceSetup
import me.nathanfallet.teamcode.cli.update.installationTarget
import me.nathanfallet.teamcode.cli.update.managedInstallation
import me.nathanfallet.teamcode.cli.update.releaseOperation
import me.nathanfallet.teamcode.cli.update.setUpInstallationService
import me.nathanfallet.teamcode.client.ReleaseClient
import me.nathanfallet.teamcode.client.ReleaseDownloadException
import me.nathanfallet.teamcode.config.configSibling
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * `teamcode ui [DIRECTORY]`: opens TeamCode Desktop, installing it first when it is missing or older than this CLI.
 * The app is started with this CLI's `config.json` (`--config-path`), so both use the same account whatever the app's
 * own environment, and with DIRECTORY (`--directory`) to start a session there. Once it installed the app, it installs
 * or repairs the local service at login, like `teamcode install` (decisions 4 and 11).
 */
internal class Ui : SuspendingCliktCommand(name = "ui") {
    private val directory by argument("DIRECTORY", help = "Open a new session on this device in this folder.").optional()
    private val yes by option("--yes", help = "Install or update the desktop app without asking.").flag()
    private val noLaunch by option("--no-launch", help = "Install or update the desktop app if needed, without opening it.").flag()
    private val bundle by option("--bundle", help = "Install from an offline directory with desktop-manifest.json, desktop-manifest.sig and the package.")
    private val noService by option("--no-service", help = "After installing the desktop app, do not install the local service or check its login startup.").flag()
    private val restartApp by option("--restart-app", metavar = "PID", help = "Download and check the desktop app of this CLI's version while the running app with this process id " +
        "still runs, then put it in place and reopen it as soon as that app has quit (what TeamCode Desktop's Update button runs). Its files are never replaced under it.").long()
    private val waitFor by option("--wait-for", hidden = true, help = "Wait for this process to exit before installing and opening the app (the hand-off --restart-app starts).").long()
    private val ready by option("--ready", hidden = true, help = "With --wait-for: the file to create once waiting, which --restart-app waits for.")
    private val staged by option("--staged", hidden = true, help = "With --wait-for: the app --restart-app downloaded and checked, put in place once the process has exited.")
    override fun help(context: Context) = "Open TeamCode Desktop, installing the version of this CLI first when it is missing or older " +
        "(signed manifest, SHA-256 and, on macOS, the Developer ID signature are checked; no administrator rights)."

    override suspend fun run() = releaseOperation {
        val os = hostOs()
        val restart = restartApp
        val waited = waitFor
        if (restart != null && (restart <= 0 || noLaunch || bundle != null || directory != null || waited != null))
            throw UsageError("--restart-app takes the app's process id and cannot be combined with --no-launch, --bundle, a directory or --wait-for.")
        if (restart != null && !yes) throw UsageError("--restart-app needs --yes: the app asks no question.")
        if ((ready != null || staged != null) && waited == null) throw UsageError("--ready and --staged go with --wait-for.")
        val folder = directory?.let { remoteDirectory(it).toString() }
        val config = configPath()
        val locations = DesktopLocations(os, ::environment)
        val temporary = (environment("TMPDIR") ?: environment("TEMP") ?: environment("TMP") ?: "/tmp").toPath()
        val client = if (bundle == null && staged == null) ReleaseClient { configure -> HttpClient { configure(this) } } else null
        try {
            val source = bundle?.let { BundleDesktopSource(it.toPath()) } ?: client?.let(::OnlineDesktopSource) ?: OfflineDesktopSource
            val terminal = Terminal()
            // After the member clicked Update (--wait-for), the Windows installer may show its progress window.
            val installer = DesktopInstaller(os, installationTarget(), locations, source, temporary, { command, arguments ->
                runInteractiveProcess(command, arguments, emptyMap(), quiet = command == "codesign")
            }, report = { echo(it) }, quietWindows = waited == null && (yes || !terminal.terminalInfo.interactive))
            val service = suspend {
                setUpInstallationService(try { managedInstallation() } catch (_: Exception) { null }, config, noService)
            }
            val ui = DesktopUi(VERSION, locations, installer, report = { echo(it) }, service = service,
                reportError = { echo(it, err = true) }) { question ->
                if (yes) true
                else if (!terminal.terminalInfo.interactive) throw CliUserException("$question Run teamcode ui --yes to install it without asking.")
                else {
                    echo("$question [y/N] ", trailingNewline = false)
                    terminal.readLineOrNull(hideInput = false)?.trim()?.lowercase() in setOf("y", "yes")
                }
            }
            when {
                restart != null -> stageRestart(restart, ui, installer, config, temporary)
                waited != null -> reopen(waited, os, ui, installer, locations, config)
                else -> {
                    val app = ui.prepare() ?: return@releaseOperation
                    if (noLaunch) { echo("TeamCode Desktop ${app.version} is installed: ${app.root}"); return@releaseOperation }
                    val arguments = listOf("--config-path", config) + (folder?.let { listOf("--directory", it) } ?: emptyList())
                    launch(os, app, arguments, config)
                    echo("Opened TeamCode Desktop ${app.version}.")
                }
            }
        } finally {
            client?.close()
        }
    }

    /**
     * The update banner's step, while the app still runs and shows these lines: everything that can take long or be
     * refused (the signed manifest, the download and its SHA-256, on macOS the Developer ID, the app's version), then a
     * detached run of this CLI ([reopen]) that waits for the app to quit, puts the staged app in place and opens it. It
     * returns only once that run says it is waiting, so the app quits when nothing is left but the swap; a failure here
     * leaves the app open with the reason.
     */
    private suspend fun stageRestart(pid: Long, ui: DesktopUi, installer: DesktopInstaller, config: String, temporary: okio.Path) {
        val staged = ui.stage { echo("Downloading TeamCode Desktop $VERSION: $it%") }
        try {
            val handshake = temporary / "teamcode-desktop-restart-${Buffer().write(secureRandomBytes(8)).readByteString().hex()}"
            if (!ensurePrivateDirectory(handshake.toString())) throw CliOperationException("desktop_staging_unavailable")
            try {
                val readyFile = handshake / "ready"
                handOff(listOf("ui", "--yes", "--wait-for", pid.toString(), "--ready", readyFile.toString()) +
                    (staged?.let { listOf("--staged", it.directory.toString()) } ?: emptyList()) + if (noService) listOf("--no-service") else emptyList(), config)
                if (!waitForFile(readyFile, HANDOFF_WAIT)) {
                    echo("The step that reopens TeamCode Desktop did not start within ${HANDOFF_WAIT.inWholeSeconds} seconds; nothing was changed. " +
                        "Its log: ${configSibling(config, "desktop.log")}", err = true)
                    throw CliOperationException("desktop_restart_unavailable")
                }
            } finally {
                // Removed whatever happens: a hand-off that starts late finds no folder and does nothing.
                try { FileSystem.SYSTEM.deleteRecursively(handshake) } catch (_: IOException) { }
            }
        } catch (error: Throwable) {
            staged?.let(installer::discard)
            throw error
        }
        echo("TeamCode Desktop $VERSION is ready: it opens as soon as this app quits.")
    }

    /**
     * The hand-off [stageRestart] starts, detached: it says it waits ([ready]), waits for the app to quit, then puts the
     * staged app in place ([staged], a rename on macOS and Linux, `msiexec` on Windows) and opens it. Without a stage
     * (an app of this version was already installed, or an older CLI's hand-off), it installs as `teamcode ui` would.
     * When that fails, the app still installed is reopened with `--update-error`, which its banner shows.
     */
    private suspend fun reopen(pid: Long, os: String, ui: DesktopUi, installer: DesktopInstaller, locations: DesktopLocations, config: String) {
        val stage = staged?.let { installer.staged(it.toPath(), VERSION) }
        ready?.toPath()?.let { readyFile ->
            // [stageRestart] removes its folder when it stops waiting: the app then stays open, and nothing is done.
            if (FileSystem.SYSTEM.metadataOrNull(requireNotNull(readyFile.parent))?.isDirectory != true) return
            FileSystem.SYSTEM.write(readyFile) { }
        }
        // The app has to quit first (on Windows, before its files can be replaced).
        if (!waitForProcessExit(pid, RESTART_WAIT)) {
            stage?.let(installer::discard)
            echo("TeamCode Desktop (process $pid) was still running after ${RESTART_WAIT.inWholeMinutes} minutes: nothing was installed or opened.", err = true)
            throw CliOperationException("desktop_restart_timeout")
        }
        val app = try {
            when {
                staged == null -> ui.prepare() ?: return
                stage == null -> throw CliOperationException("desktop_stage_invalid")
                else -> installer.activate(stage).also { echo("Installed TeamCode Desktop ${it.version} in ${it.root}.") }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            // The app already quit for this update: it is reopened on the version still installed, which says why.
            val previous = locations.installed().firstOrNull()
            if (previous == null) echo("No TeamCode Desktop is left to reopen: run teamcode ui to install it again.", err = true)
            else try { launch(os, previous, listOf("--config-path", config, "--update-error", describeFailure(error, null).message.take(1_000)), config) }
            catch (relaunch: Exception) {
                if (relaunch is kotlinx.coroutines.CancellationException) throw relaunch
                echo("TeamCode Desktop could not be reopened: ${describeFailure(relaunch, null).message}", err = true)
            }
            throw error
        } finally {
            stage?.let(installer::discard)
        }
        launch(os, app, listOf("--config-path", config), config)
        echo("Opened TeamCode Desktop ${app.version}.")
    }

    private suspend fun launch(os: String, app: DesktopInstallation, arguments: List<String>, config: String) {
        val command = desktopLaunchCommand(os, app, arguments)
        if (os == "macos") {
            if (runInteractiveProcess(command.first(), command.drop(1), emptyMap(), quiet = true) != 0) throw CliOperationException("desktop_launch_failed")
            return
        }
        startDetachedProcess(command.first(), command.drop(1), desktopLog(config))
    }

    /**
     * Starts this CLI again, detached, with [arguments]: the step that outlives the app. The managed launcher when there
     * is one, so it runs the selected version; its output goes to `desktop.log`.
     */
    private fun handOff(arguments: List<String>, config: String) {
        val executable = (try { managedInstallation()?.paths?.launcher?.toString() } catch (_: Exception) { null }) ?: servicePlatform().executable
        startDetachedProcess(executable, arguments, desktopLog(config))
    }

    /** A private log beside config.json, emptied once it passes 1 MiB. */
    private fun desktopLog(config: String): String {
        val log = configSibling(config, "desktop.log")
        val size = FileSystem.SYSTEM.metadataOrNull(log.toPath())?.size
        if (size == null || size > 1_048_576) writePrivateFile(log, "")
        return log
    }

    private companion object {
        val RESTART_WAIT = 2.minutes
        /** How long the hand-off has to start and say it waits; a cold start on Windows (an antivirus scan) takes a few seconds. */
        val HANDOFF_WAIT = 30.seconds
    }
}

/** Waits until [file] exists, checking every 100 ms; false when it still does not after [timeout]. */
internal suspend fun waitForFile(file: okio.Path, timeout: Duration, fs: FileSystem = FileSystem.SYSTEM): Boolean {
    val start = TimeSource.Monotonic.markNow()
    while (!fs.exists(file)) {
        if (start.elapsedNow() >= timeout) return false
        delay(100)
    }
    return true
}

/** The hand-off of a stage downloads nothing: it only puts in place what [DesktopInstaller.staged] found. */
private object OfflineDesktopSource : DesktopSource {
    override suspend fun metadata(version: String) = throw CliOperationException("desktop_stage_invalid")
    override suspend fun download(version: String, target: String, asset: me.nathanfallet.teamcode.api.models.ReleaseAsset, destination: okio.Path, progress: (Long) -> Unit) =
        throw CliOperationException("desktop_stage_invalid")
}

/**
 * What `teamcode ui` decides before opening the app: the first installed app of this CLI's version or newer is used
 * as is; otherwise (none, or only older ones) the CLI's version is installed once [confirm] agrees. Null when the member
 * declined. After an installation, [service] sets up the local service; its failure is reported and never fails the app.
 */
internal class DesktopUi(
    private val cliVersion: String,
    private val locations: DesktopLocations,
    private val installer: DesktopInstaller,
    private val report: (String) -> Unit,
    private val service: suspend () -> ServiceSetup? = { null },
    private val reportError: (String) -> Unit = report,
    private val confirm: (String) -> Boolean,
) {
    suspend fun prepare(): DesktopInstallation? {
        val installed = locations.installed()
        installed.firstOrNull { it.usableWith(cliVersion) }?.let { return it }
        val older = installed.firstOrNull()
        val desktop = release()
        val size = DesktopInstaller.megabytes(desktop.asset.size)
        val question = if (older == null) "Install TeamCode Desktop ${desktop.version} ($size)?"
            else "TeamCode Desktop ${older.version ?: "(unknown version)"} is older than this CLI. Update it to ${desktop.version} ($size)?"
        if (!confirm(question)) { report("Nothing was installed."); return null }
        val app = installer.install(desktop)
        report("Installed TeamCode Desktop ${app.version} in ${app.root}.")
        service()?.let { if (it.failed) reportError(it.line) else report(it.line) }
        return app
    }

    /**
     * The update banner's step, without a question (the member clicked Update): null when an app of this CLI's version
     * or newer is already installed (it is only reopened), otherwise the CLI's version downloaded, checked and staged.
     * The local service was already set up by the `teamcode update` that comes before.
     */
    suspend fun stage(progress: (Int) -> Unit): StagedDesktop? {
        if (locations.installed().any { it.usableWith(cliVersion) }) return null
        return installer.stage(release(), progress)
    }

    private suspend fun release(): DesktopRelease = try { installer.release(cliVersion) }
        catch (error: ReleaseDownloadException) {
            if (error.code == "release_http_404") throw CliUserException("No TeamCode Desktop was published for version $cliVersion.")
            throw error
        }
}
