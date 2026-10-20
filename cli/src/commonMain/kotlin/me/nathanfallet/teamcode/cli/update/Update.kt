package me.nathanfallet.teamcode.cli.update

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.VERSION
import me.nathanfallet.teamcode.client.ReleaseClient
import me.nathanfallet.teamcode.client.ReleaseDownloadException

internal suspend fun <T> releaseOperation(block: suspend () -> T): T = try { block() }
catch (error: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    throw CliOperationException("release_operation_timeout")
} catch (error: CancellationException) { throw error }
catch (error: ReleaseDownloadException) { throw CliOperationException(error.code) }

internal class Update : SuspendingCliktCommand(name = "update") {
    private val check by option("--check", help = "Check signed metadata without installing or restarting anything.").flag()
    private val rollback by option("--rollback", help = "Restore the previous verified local version without network access.").flag()
    private val version by option("--version", help = "Choose a published version, including prereleases or an explicit downgrade.")
    private val bundle by option("--bundle", help = "Use an offline signed release directory.")
    private val root by option("--root", help = "Explicit managed installation directory; ordinary launches discover their own installation.")
    private val notifications by option("--notifications", help = "Configure background signed release notifications for this managed installation.").choice("on", "off", "status")
    private val automatic by option("--automatic", help = "Opt into an hourly user update job, disable it, inspect it or remove its registration.").choice("on", "off", "status", "uninstall")
    private val automaticRun by option("--automatic-run", help = "Run one enabled automatic update attempt; supplied by the OS scheduler.").flag()
    override fun help(context: Context) = "Check or update a managed native installation; preserve login and drain its owned service."

    override suspend fun run() = releaseOperation {
        if (listOf(notifications != null, automatic != null, automaticRun).count { it } > 1)
            throw UsageError("Choose notifications, automatic configuration or one automatic attempt.")
        if (automatic != null || automaticRun) {
            if (check || rollback || version != null || bundle != null) throw UsageError("Automatic updates cannot be combined with a check, version, bundle or rollback.")
            val store = root?.let { InstallationStore(defaultInstallationPaths(it)) } ?: managedInstallation()
                ?: throw CliOperationException("release_not_managed")
            automaticUpdateCommand(store, automatic, automaticRun) { echo(it) }
            return@releaseOperation
        }
        if (notifications != null) {
            if (check || rollback || version != null || bundle != null) throw UsageError("--notifications cannot be combined with a check, installation or rollback.")
            val store = root?.let { InstallationStore(defaultInstallationPaths(it)) } ?: managedInstallation()
                ?: throw CliOperationException("release_not_managed")
            if (store.state() == null) throw CliOperationException("release_not_managed")
            val settings = PeriodicReleaseCheck(store.paths, VERSION)
            if (notifications != "status") settings.setNotifications(notifications == "on")
            echo("Background release notifications: ${if (settings.notificationsEnabled()) "on" else "off"}.")
            if (!me.nathanfallet.teamcode.cli.platform.environment("TEAMCODE_DISABLE_AUTOUPDATE").isNullOrEmpty()) echo("Environment override: background checks are disabled.")
            return@releaseOperation
        }
        if (check) {
            if (rollback || bundle != null || root != null) throw UsageError("--check cannot be combined with --rollback, --bundle or --root.")
            val result = ReleaseClient { configure -> HttpClient { configure(this) } }.use { CheckRelease(it, currentVersion = VERSION)(version) }
            if (result == null) echo("No stable published release is available. Current version: $VERSION.")
            else {
                echo("Current version: ${result.currentVersion}")
                echo("Verified release: ${result.release.manifest.version} (${result.release.manifest.sourceCommit})")
                echo("Release key: ${result.release.keyId}")
                echo("Update available: ${result.updateAvailable}")
                echo("Check only: no files were installed and no service was restarted.")
            }
        } else {
            if (rollback && (version != null || bundle != null)) throw UsageError("--rollback cannot be combined with --version or --bundle.")
            val store = root?.let { InstallationStore(defaultInstallationPaths(it)) } ?: managedInstallation()
                ?: throw CliOperationException("release_not_managed")
            store.initialize()
            if (store.state() == null) throw CliOperationException("release_not_managed")
            val selected = if (rollback) InstallationOperations(store).rollback() else
                InstallationOperations(store).install(version, bundle, allowDowngrade = version != null || bundle != null)
            echo(if (selected == null) "The managed installation is already up to date." else
                "Selected TeamCode ${selected.version}. Launcher: ${store.paths.launcher}")
        }
    }
}
