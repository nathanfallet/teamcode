package me.nathanfallet.teamcode.cli.ui

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.Terminal
import io.ktor.client.HttpClient
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.VERSION
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.cli.platform.hostOs
import me.nathanfallet.teamcode.cli.platform.runInteractiveProcess
import me.nathanfallet.teamcode.cli.platform.startDetachedProcess
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import me.nathanfallet.teamcode.cli.remote.remoteDirectory
import me.nathanfallet.teamcode.cli.update.installationTarget
import me.nathanfallet.teamcode.cli.update.releaseOperation
import me.nathanfallet.teamcode.client.ReleaseClient
import me.nathanfallet.teamcode.client.ReleaseDownloadException
import me.nathanfallet.teamcode.config.configSibling
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * `teamcode ui [DIRECTORY]`: opens TeamCode Desktop, installing it first when it is missing or older than this CLI.
 * The app is started with this CLI's `config.json` (`--config-path`), so both use the same account whatever the app's
 * own environment, and with DIRECTORY (`--directory`) to start a session there.
 */
internal class Ui : SuspendingCliktCommand(name = "ui") {
    private val directory by argument("DIRECTORY", help = "Open a new session on this device in this folder.").optional()
    private val yes by option("--yes", help = "Install or update the desktop app without asking.").flag()
    private val noLaunch by option("--no-launch", help = "Install or update the desktop app if needed, without opening it.").flag()
    private val bundle by option("--bundle", help = "Install from an offline directory with desktop-manifest.json, desktop-manifest.sig and the package.")
    override fun help(context: Context) = "Open TeamCode Desktop, installing the version of this CLI first when it is missing or older " +
        "(signed manifest, SHA-256 and, on macOS, the Developer ID signature are checked; no administrator rights)."

    override suspend fun run() = releaseOperation {
        val os = hostOs()
        val folder = directory?.let { remoteDirectory(it).toString() }
        val config = configPath()
        val locations = DesktopLocations(os, ::environment)
        val temporary = (environment("TMPDIR") ?: environment("TEMP") ?: environment("TMP") ?: "/tmp").toPath()
        val client = if (bundle == null) ReleaseClient { configure -> HttpClient { configure(this) } } else null
        try {
            val source = bundle?.let { BundleDesktopSource(it.toPath()) } ?: OnlineDesktopSource(requireNotNull(client))
            val installer = DesktopInstaller(os, installationTarget(), locations, source, temporary, { command, arguments ->
                runInteractiveProcess(command, arguments, emptyMap(), quiet = command == "codesign")
            }, report = { echo(it) })
            val terminal = Terminal()
            val app = DesktopUi(VERSION, locations, installer, report = { echo(it) }) { question ->
                if (yes) true
                else if (!terminal.terminalInfo.interactive) throw CliUserException("$question Run teamcode ui --yes to install it without asking.")
                else {
                    echo("$question [y/N] ", trailingNewline = false)
                    terminal.readLineOrNull(hideInput = false)?.trim()?.lowercase() in setOf("y", "yes")
                }
            }.prepare() ?: return@releaseOperation
            if (noLaunch) { echo("TeamCode Desktop ${app.version} is installed: ${app.root}"); return@releaseOperation }
            val arguments = listOf("--config-path", config) + (folder?.let { listOf("--directory", it) } ?: emptyList())
            launch(os, app, arguments, config)
            echo("Opened TeamCode Desktop ${app.version}.")
        } finally {
            client?.close()
        }
    }

    private suspend fun launch(os: String, app: DesktopInstallation, arguments: List<String>, config: String) {
        val command = desktopLaunchCommand(os, app, arguments)
        if (os == "macos") {
            if (runInteractiveProcess(command.first(), command.drop(1), emptyMap(), quiet = true) != 0) throw CliOperationException("desktop_launch_failed")
            return
        }
        // Its output goes to a private log beside config.json, emptied once it passes 1 MiB.
        val log = configSibling(config, "desktop.log")
        val size = FileSystem.SYSTEM.metadataOrNull(log.toPath())?.size
        if (size == null || size > 1_048_576) writePrivateFile(log, "")
        startDetachedProcess(command.first(), command.drop(1), log)
    }
}

/**
 * What `teamcode ui` decides before opening the app: the first installed app of this CLI's version or newer is used
 * as is; otherwise (none, or only older ones) the CLI's version is installed once [confirm] agrees. Null when the member
 * declined.
 */
internal class DesktopUi(
    private val cliVersion: String,
    private val locations: DesktopLocations,
    private val installer: DesktopInstaller,
    private val report: (String) -> Unit,
    private val confirm: (String) -> Boolean,
) {
    suspend fun prepare(): DesktopInstallation? {
        val installed = locations.installed()
        installed.firstOrNull { it.usableWith(cliVersion) }?.let { return it }
        val older = installed.firstOrNull()
        val desktop = try { installer.release(cliVersion) }
        catch (error: ReleaseDownloadException) {
            if (error.code == "release_http_404") throw CliUserException("No TeamCode Desktop was published for version $cliVersion.")
            throw error
        }
        val size = DesktopInstaller.megabytes(desktop.asset.size)
        val question = if (older == null) "Install TeamCode Desktop ${desktop.version} ($size)?"
            else "TeamCode Desktop ${older.version ?: "(unknown version)"} is older than this CLI. Update it to ${desktop.version} ($size)?"
        if (!confirm(question)) { report("Nothing was installed."); return null }
        val app = installer.install(desktop)
        report("Installed TeamCode Desktop ${app.version} in ${app.root}.")
        return app
    }
}
