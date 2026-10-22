package me.nathanfallet.teamcode.cli.update

import io.ktor.client.HttpClient
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.VERSION
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.cli.platform.servicePlatform
import me.nathanfallet.teamcode.client.ReleaseClient

/** Explicit native user-job control; normal command launches never install a scheduler job. */
internal suspend fun automaticUpdateCommand(store: InstallationStore, operation: String?, execute: Boolean, echo: (String) -> Unit) {
    val current = store.state() ?: throw CliOperationException("release_not_managed")
    store.validate(current.current)
    if (store.launcherVersion() == null) throw CliOperationException("release_not_managed")
    store.ensureLauncher(current.current)
    val policy = AutomaticUpdateStore(store.paths)
    val platform = servicePlatform()
    val schedule = AutomaticUpdateScheduleImpl()
    if (execute) {
        val result = AutomaticUpdateRunner(policy, platform, !environment("TEAMCODE_DISABLE_AUTOUPDATE").isNullOrEmpty(),
            // A selection made while agent sessions ran restarts the service once they have all ended.
            prepare = { applyPendingServiceRestart(store) { echo(it) } }).run(
            fetch = {
                PeriodicReleaseCheck(store.paths, VERSION).check(forInstallation = true) {
                    ReleaseClient { configure -> HttpClient { configure(this) } }.use { CheckRelease(it, currentVersion = VERSION)() }
                }
            },
            install = { candidate, settings -> InstallationOperations(store, settings.configuration) { echo(it) }.install(
                candidate.release.manifest.version, null, allowDowngrade = false, approvedMetadata = candidate.metadata)
                ?.also { activation -> activationReport(activation, store).drop(1).forEach(echo) }?.version },
        )
        echo("Automatic update: ${result?.outcome ?: "DISABLED"}${result?.version?.let { " ($it)" }.orEmpty()}.")
        if (result?.outcome == "FAILED") throw CliOperationException(result.diagnostic ?: "automatic_update_failed")
        return
    }
    if (operation != "status") {
        AutomaticUpdateManager(policy, schedule, platform) {
            automaticUpdateSettings(store.paths, configPath(), platform,
                environment("HOME") ?: environment("USERPROFILE") ?: throw CliOperationException("release_home_unavailable"))
        }.configure(requireNotNull(operation))
    }
    val settings = policy.settings()
    echo("Automatic updates: ${if (settings?.enabled == true) "on" else "off"}.")
    echo("Registration present: ${settings != null && schedule.owns(settings)}.")
    policy.result()?.let { echo("Last attempt: ${it.outcome} at ${it.attemptedAtMillis}${it.version?.let { version -> " ($version)" }.orEmpty()}${it.diagnostic?.let { diagnostic -> " [$diagnostic]" }.orEmpty()}.") }
    if (!environment("TEAMCODE_DISABLE_AUTOUPDATE").isNullOrEmpty()) echo("Environment override: automatic updates are disabled in this process.")
}
