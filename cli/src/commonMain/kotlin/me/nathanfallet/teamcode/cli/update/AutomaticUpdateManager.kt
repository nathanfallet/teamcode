package me.nathanfallet.teamcode.cli.update

import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.platform.ServicePlatform

/** Changes opt-in atomically. Disabling affects future work; an already started update may finish. */
internal class AutomaticUpdateManager(private val policy: AutomaticUpdateStore,
    private val schedule: AutomaticUpdateSchedule, private val platform: ServicePlatform,
    private val createSettings: () -> AutomaticUpdateSettings) {
    suspend fun configure(operation: String): AutomaticUpdateSettings? {
        require(operation in setOf("on", "off", "uninstall"))
        if (platform.userId == "0") throw CliOperationException("release_user_installation_required")
        val lock = acquireInstallationLock(policy.managementLock.toString(), exclusive = true)
            ?: throw CliOperationException("automatic_settings_busy")
        lock.use {
            val selected = policy.settings() ?: if (operation == "on") createSettings() else return null
            require(selected.os == platform.os && selected.userId == platform.userId)
            val disabled = selected.copy(enabled = false)
            policy.save(disabled) // Fail closed before registration changes or cancelled setup.
            when (operation) {
                "on" -> {
                    schedule.install(disabled)
                    val enabled = disabled.copy(enabled = true)
                    policy.save(enabled)
                    schedule.start(enabled)
                    return enabled
                }
                "off" -> { schedule.disable(disabled); return disabled }
                else -> {
                    val worker = acquireInstallationLock(policy.workerLock.toString(), exclusive = true)
                        ?: throw CliOperationException("automatic_worker_busy")
                    worker.use { schedule.uninstall(disabled); policy.remove() }
                    return null
                }
            }
        }
    }
}
