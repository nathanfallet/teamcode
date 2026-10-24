package me.nathanfallet.teamcode.cli.service

import com.github.ajalt.clikt.core.CliktError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.config.configStore
import me.nathanfallet.teamcode.cli.platform.ServicePlatform
import me.nathanfallet.teamcode.cli.platform.createPrivateFile
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Decisions 10 and 11: `teamcode install`, `teamcode update` and the automatic update job install the local service
 * (the device's daemon) when it is missing, and check that it starts at login: registered, enabled, loaded and running.
 * What is missing is repaired, without elevation. A registration made with `--at-boot` is left as it is.
 */
internal data class LoginStartup(val registered: Boolean, val enabled: Boolean, val loaded: Boolean, val running: Boolean) {
    val healthy: Boolean get() = registered && enabled && loaded && running
}

/** The OS operations the setup needs, all in the user's own login domain. */
internal interface LoginServiceSystem {
    suspend fun inspect(settings: ServiceSettings): LoginStartup
    /** Writes this configuration's registration file; never replaces one that differs. */
    suspend fun register(settings: ServiceSettings)
    /** Makes the OS start the registration at login. */
    suspend fun enable(settings: ServiceSettings)
    /** Loads the registration if needed and starts the service now. */
    suspend fun start(settings: ServiceSettings)
}

/** The OS service managers: launchd's `gui/UID` domain, the systemd user manager, or a logon task. */
internal object SystemLoginService : LoginServiceSystem {
    override suspend fun inspect(settings: ServiceSettings): LoginStartup {
        if (!ownsRegistration(settings)) return LoginStartup(registered = false, enabled = false, loaded = false, running = false)
        return when (settings.os) {
            "macos" -> {
                val loaded = queryService(settings, "status") == 0
                LoginStartup(true, queryService(settings, "enabled") == 0, loaded, loaded && queryService(settings, "running") == 0)
            }
            "windows" -> when (queryService(settings, "status")) {
                3 -> LoginStartup(registered = true, enabled = false, loaded = false, running = false)
                0 -> LoginStartup(true, queryService(settings, "enabled") == 0, true, queryService(settings, "running") == 0)
                else -> throw CliktError("Cannot query Windows Task Scheduler.")
            }
            // A unit systemd does not know is reported neither enabled nor active; enabling it reloads the manager first.
            else -> {
                val enabled = queryService(settings, "enabled") == 0
                LoginStartup(true, enabled, enabled, queryService(settings, "running") == 0)
            }
        }
    }
    override suspend fun register(settings: ServiceSettings) = installServiceRegistration(settings)
    override suspend fun enable(settings: ServiceSettings) = enableManagedService(settings)
    override suspend fun start(settings: ServiceSettings) = startManagedService(settings)
}

internal enum class ServiceSetupOutcome { INSTALLED, REPAIRED, UNCHANGED, AT_BOOT, OTHER_EXECUTABLE, OPTED_OUT, CLOUD_RUNNER, FAILED }

/** The one line `install` and `update` print about the service, on the error stream when [failed]. */
internal data class ServiceSetup(val outcome: ServiceSetupOutcome, val line: String) {
    val failed: Boolean get() = outcome == ServiceSetupOutcome.FAILED
}

/**
 * Installs or repairs the login service of [config], run by [executable] (the managed installation's launcher).
 * Never throws (except cancellation): a failure is a [ServiceSetupOutcome.FAILED] line naming the command to run, so it
 * never undoes the installation or update that ran before it.
 */
internal suspend fun setUpService(
    config: String,
    executable: String,
    /** Why this command skips the service (`--no-service`, `TEAMCODE_NO_SERVICE`), or null. */
    optOut: String?,
    cloudRunner: () -> Boolean,
    platform: () -> ServicePlatform,
    home: () -> String?,
    /** Whether a saved service's executable is [executable], for example through another spelling of the same path. */
    sameExecutable: (String) -> Boolean = { it == executable },
    system: LoginServiceSystem = SystemLoginService,
    /** Reads service.json; a boot registration's OS identity is checked against this process's. */
    load: (String) -> ServiceSettings? = ::loadService,
): ServiceSetup {
    fun result(outcome: ServiceSetupOutcome, line: String) = ServiceSetup(outcome, line)
    return try {
        // Decision 9: one pod runs one session, so a cloud session's runner never becomes a device.
        if (cloudRunner()) return result(ServiceSetupOutcome.CLOUD_RUNNER, "Local TeamCode service: not set up in a cloud session's runner.")
        if (optOut != null) return result(ServiceSetupOutcome.OPTED_OUT, "Local TeamCode service: not checked ($optOut).")
        if (!automaticServiceSetup(config)) return result(ServiceSetupOutcome.OPTED_OUT,
            "Local TeamCode service: not checked, automatic setup is off (teamcode service automatic on turns it back on).")
        val identity = platform()
        if (identity.userId == "0") throw CliktError("run it as your own user, not as root")
        val existing = load(config)
        if (existing != null && existing.atBoot)
            return result(ServiceSetupOutcome.AT_BOOT, "Local TeamCode service: starts at boot (--at-boot); its registration is left as it is.")
        if (existing != null && !sameExecutable(existing.executable))
            return result(ServiceSetupOutcome.OTHER_EXECUTABLE, "Local TeamCode service: runs another executable (${existing.executable}); left as it is.")
        val settings = existing ?: createServiceSettings(config, executable, identity,
            home() ?: throw CliktError("cannot locate the user home directory"), load)
        val before = system.inspect(settings)
        if (existing != null && before.healthy)
            return result(ServiceSetupOutcome.UNCHANGED, "Local TeamCode service: starts at login and runs; nothing to change.")
        val done = mutableListOf<String>()
        if (!before.registered) { system.register(settings); done += "registered" }
        if (!before.registered || !before.enabled) { system.enable(settings); done += "enabled at login" }
        if (!before.registered || !before.loaded || !before.running) { system.start(settings); done += "started" }
        val after = system.inspect(settings)
        if (!after.registered || !after.enabled || !after.loaded) throw CliktError("the OS service manager does not report it enabled and loaded")
        val login = if (configStore(config).load() == null) " It connects once you run teamcode login." else ""
        if (existing == null) result(ServiceSetupOutcome.INSTALLED,
            "Local TeamCode service: installed and started; it starts at login (port ${settings.port}).$login")
        else result(ServiceSetupOutcome.REPAIRED, "Local TeamCode service: repaired (${done.joinToString(", ")}); it starts at login.$login")
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) {
        val reason = when (error) {
            is CliktError -> error.message?.trimEnd('.')?.replaceFirstChar { it.lowercase() }
            is CliOperationException -> error.code
            else -> null
        } ?: "unexpected error"
        result(ServiceSetupOutcome.FAILED, "Local TeamCode service: not set up ($reason). TeamCode itself is installed; run teamcode service install to retry.")
    }
}

/** service.json for a new login registration. A concurrent setup that wrote it first wins, and its settings are used. */
private fun createServiceSettings(config: String, executable: String, identity: ServicePlatform, home: String, load: (String) -> ServiceSettings?): ServiceSettings {
    val created = newServiceSettings(config, 8789, executable, atBoot = false, platform = identity, home = home)
    if (createPrivateFile(servicePath(config), Serialization.json.encodeToString(ServiceSettings.serializer(), created))) return created
    return requireNotNull(load(config))
}

@Serializable
private class ServiceSetupPreference(val automatic: Boolean = true)

private fun serviceSetupPath(config: String) = config.toPath().parent!!.resolve("service-setup.json")

/** Whether install and update set up the service (`teamcode service automatic`). On unless turned off; an unreadable file counts as on. */
internal fun automaticServiceSetup(config: String): Boolean {
    val path = serviceSetupPath(config)
    val metadata = FileSystem.SYSTEM.metadataOrNull(path) ?: return true
    if (!metadata.isRegularFile || metadata.symlinkTarget != null || (metadata.size ?: 0) > 4096) return true
    return try { Serialization.json.decodeFromString<ServiceSetupPreference>(FileSystem.SYSTEM.read(path) { readUtf8() }).automatic }
    catch (_: Exception) { true }
}

internal fun setAutomaticServiceSetup(config: String, enabled: Boolean) {
    if (enabled) FileSystem.SYSTEM.delete(serviceSetupPath(config), mustExist = false)
    else writePrivateFile(serviceSetupPath(config).toString(), Serialization.json.encodeToString(ServiceSetupPreference.serializer(), ServiceSetupPreference(false)))
}
