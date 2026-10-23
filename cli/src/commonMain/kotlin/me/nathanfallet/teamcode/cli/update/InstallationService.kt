package me.nathanfallet.teamcode.cli.update

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.config.configStore
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.service.*
import okio.Path.Companion.toPath

internal data class InstallationService(val settings: ServiceSettings, val running: Boolean)

internal suspend fun installationService(store: InstallationStore, firstInstall: Boolean, configuration: String = configPath()): InstallationService? {
    val selected = loadService(configuration) ?: return null
    if (!sameInstallationPath(selected.executable.toPath(), store.paths.launcher, store.paths.target)) {
        if (firstInstall) return null // Preserve a manually installed/source-checkout service.
        throw CliOperationException("release_service_unmanaged")
    }
    if (!ownsRegistration(selected)) throw CliOperationException("release_service_unmanaged")
    return InstallationService(selected, managedServiceRunning(selected))
}

internal suspend fun awaitInstallationService(service: InstallationService, version: String) {
    val config = configStore(service.settings.config).load() ?: throw CliOperationException("release_service_auth_missing")
    withTimeout(30_000) {
        while (!serviceResponds(service.settings, config.serverUrl, expectedVersion = version)) delay(200)
    }
}

internal suspend fun recoverInstallationService(store: InstallationStore, pending: InstallationJournal, arguments: List<String> = emptyList()) {
    if (!pending.restartService) return
    val path = pending.serviceConfig ?: throw CliOperationException("release_service_recovery_failed")
    // This invocation is itself the registered service startup; let its restored payload start it.
    if (arguments.take(2) == listOf("service", "serve") && arguments.drop(2) == listOf("--config", path)) return
    val selected = loadService(path) ?: throw CliOperationException("release_service_recovery_failed")
    if (!sameInstallationPath(selected.executable.toPath(), store.paths.launcher, store.paths.target) || !ownsRegistration(selected))
        throw CliOperationException("release_service_unmanaged")
    stopManagedService(selected)
    startManagedService(selected)
    awaitInstallationService(InstallationService(selected, true), pending.before?.current?.version ?: throw CliOperationException("release_service_recovery_failed"))
}
