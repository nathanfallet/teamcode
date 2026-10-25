package me.nathanfallet.teamcode.cli.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.config.configStore
import me.nathanfallet.teamcode.cli.platform.servicePlatform
import me.nathanfallet.teamcode.cli.service.ServiceSettings
import me.nathanfallet.teamcode.cli.service.loadService
import me.nathanfallet.teamcode.cli.service.managedServiceRunning
import me.nathanfallet.teamcode.cli.service.ownsRegistration
import me.nathanfallet.teamcode.cli.service.serviceResponds
import me.nathanfallet.teamcode.cli.service.startManagedService
import me.nathanfallet.teamcode.cli.service.stopManagedService
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** The registered managed OS service, behind an interface so activation and deferred restarts are testable without one. */
internal interface ManagedServiceControl {
    suspend fun running(): Boolean
    /** True when the running service already announces [version]. */
    suspend fun serving(version: String): Boolean
    /** Drains in-flight requests before the service exits. */
    suspend fun stop()
    suspend fun start()
    suspend fun await(version: String)
}

/** Resolves a saved service configuration path to its control, or null when it is gone or not owned by this installation. */
internal typealias ServiceControls = suspend (String) -> ManagedServiceControl?

internal class SystemServiceControl(private val settings: ServiceSettings) : ManagedServiceControl {
    override suspend fun running() = managedServiceRunning(settings)
    override suspend fun serving(version: String): Boolean {
        val config = configStore(settings.config).load() ?: return false
        return serviceResponds(settings, config.serverUrl, expectedVersion = version)
    }
    override suspend fun stop() = stopManagedService(settings)
    override suspend fun start() = startManagedService(settings)
    override suspend fun await(version: String) = awaitInstallationService(InstallationService(settings, true), version)
}

internal suspend fun managedServiceControl(store: InstallationStore, config: String): ManagedServiceControl? {
    val selected = loadService(config) ?: return null
    if (!sameInstallationPath(selected.executable.toPath(), store.paths.launcher, store.paths.target) || !ownsRegistration(selected)) return null
    return SystemServiceControl(selected)
}

internal fun systemServiceControls(store: InstallationStore): ServiceControls = { config -> managedServiceControl(store, config) }

/** The running managed service an activation must keep consistent with the selection. */
internal class ActivationService(val config: String, val control: ManagedServiceControl)

internal enum class ServiceOutcome { NONE, RESTARTED, DEFERRED, FAILED }

internal data class Activation(
    val version: InstalledVersion,
    val previous: InstalledVersion?,
    val service: ServiceOutcome,
    /** Live agent sessions per payload manifest digest when the selection changed. */
    val sessions: Map<String, Int> = emptyMap(),
    /** The payload the service keeps running until its deferred restart. */
    val serviceVersion: InstalledVersion? = null,
)

internal enum class PendingRestart { NONE, RESTARTED, WAITING, FAILED }

internal const val INSTALLATION_LOCK_WAIT_MILLIS = 60_000L

/** Waits for a briefly held activity lock (a service restart or a recovery), without ever stopping its holder. */
internal suspend fun awaitInstallationLock(store: InstallationStore, exclusive: Boolean, waitMillis: Long = INSTALLATION_LOCK_WAIT_MILLIS): InstallationLock {
    val deadline = TimeSource.Monotonic.markNow() + waitMillis.milliseconds
    while (true) {
        acquireInstallationLock(store.paths.lock.toString(), exclusive)?.let { return it }
        if (deadline.hasPassedNow()) throw CliOperationException("release_installation_busy")
        delay(100)
    }
}

/**
 * Select [version]. Caller holds the writer lock. Without agent sessions, the service restarts at once and a failed start
 * restores the previous selection, as before. With sessions holding the shared activity lease, the selection still
 * happens now: payloads are immutable, so running sessions keep their executable while new launches use [version]. The
 * service, which those sessions depend on, restarts when the last lease is released ([applyPendingServiceRestart]).
 */
internal suspend fun activateInstallation(store: InstallationStore, version: InstalledVersion, rollback: Boolean,
    service: ActivationService?, controls: ServiceControls, waitMillis: Long = INSTALLATION_LOCK_WAIT_MILLIS,
    report: (String) -> Unit = {}): Activation {
    val before = store.state()
    if (before?.current == version) {
        // Nothing to select or restart; still say whether an earlier selection waits for sessions to end.
        store.validate(version)
        val waiting = store.pendingServiceRestart()
        return Activation(version, before.previous, if (waiting != null) ServiceOutcome.DEFERRED else ServiceOutcome.NONE,
            if (waiting != null) store.liveSessions() else emptyMap(), waiting?.version)
    }
    acquireInstallationLock(store.paths.lock.toString(), exclusive = true)?.use {
        store.activate(version, rollback = rollback, serviceConfig = service?.config, restartService = service != null,
            prepare = { service?.control?.stop() },
            start = { service?.control?.let { it.start(); it.await(version.version) } },
            recover = {
                service?.control?.let { control ->
                    control.stop()
                    control.start()
                    control.await(before?.current?.version ?: throw CliOperationException("release_service_recovery_failed"))
                }
            })
        try {
            if (service != null) store.saveServiceRecord(ServiceRecord(serviceConfig = service.config, version = version))
            else store.clearServiceRecord()
        } catch (_: Exception) { /* A stale record only causes a needless check later: serving() finds the version. */ }
        collectGarbageQuietly(store)
        return Activation(version, before?.current, if (service != null) ServiceOutcome.RESTARTED else ServiceOutcome.NONE)
    }
    val sessions = store.liveSessions()
    // A shared lease keeps any launcher's recovery (which needs the exclusive lock) from undoing this journal mid-write.
    awaitInstallationLock(store, exclusive = false, waitMillis).use {
        if (service != null && before != null && store.serviceRecord() == null)
            store.saveServiceRecord(ServiceRecord(serviceConfig = service.config, version = before.current))
        store.activate(version, rollback = rollback)
    }
    if (service == null) return Activation(version, before?.current, ServiceOutcome.NONE, sessions)
    // The last session may have ended meanwhile; its exit then found no pending restart yet.
    val outcome = reportingRestartFailure(store, report) { applyPendingServiceRestartLocked(store, controls, report) }
    return when (outcome) {
        PendingRestart.WAITING -> Activation(version, before?.current, ServiceOutcome.DEFERRED, sessions, store.serviceRecord()?.version)
        PendingRestart.RESTARTED -> Activation(version, before?.current, ServiceOutcome.RESTARTED, sessions)
        // For example a rollback, while sessions run, to the payload the service never left.
        PendingRestart.NONE -> Activation(version, before?.current, ServiceOutcome.NONE, sessions)
        // The service's payload is selected again; the failure was reported.
        PendingRestart.FAILED -> Activation(store.state()?.current ?: version, before?.current, ServiceOutcome.FAILED, sessions)
    }
}

/** Caller holds the writer lock. Restarts the service on the selection only if no agent session holds a lease. */
internal suspend fun applyPendingServiceRestartLocked(store: InstallationStore, controls: ServiceControls, report: (String) -> Unit = {}): PendingRestart {
    if (store.journal() == null && store.pendingServiceRestart() == null) return PendingRestart.NONE
    val exclusive = acquireInstallationLock(store.paths.lock.toString(), exclusive = true) ?: return PendingRestart.WAITING
    exclusive.use {
        if (store.journal() != null) store.recoverInterrupted { recoverInstallationService(store, it) }
        val record = store.pendingServiceRestart() ?: return PendingRestart.NONE
        val current = store.state()?.current ?: return PendingRestart.NONE
        val control = controls(record.serviceConfig)
        val outcome = if (control == null || !control.running()) {
            // A stopped or removed service starts on the selection whenever it starts again.
            store.clearServiceRecord()
            PendingRestart.NONE
        } else if (control.serving(current.version)) {
            store.saveServiceRecord(record.copy(version = current))
            PendingRestart.NONE
        } else {
            report("Restarting the local TeamCode service on ${current.version}: no agent session uses ${record.version.version} any more.")
            store.restartService(record, prepare = { control.stop() }, start = { control.start(); control.await(current.version) },
                recover = { fallback -> control.stop(); control.start(); control.await(fallback.version) })
            PendingRestart.RESTARTED
        }
        collectGarbageQuietly(store)
        return outcome
    }
}

/**
 * Applies a deferred service restart when neither an installer nor an agent session is running. It never waits for
 * either: an active installer applies it itself, and the last session to end retries. Failures are reported, not thrown.
 */
internal suspend fun applyPendingServiceRestart(store: InstallationStore, controls: ServiceControls = systemServiceControls(store),
    report: (String) -> Unit): PendingRestart = reportingRestartFailure(store, report) {
    if (store.journal() == null && store.pendingServiceRestart() == null) PendingRestart.NONE
    else acquireInstallationLock(store.paths.writerLock.toString(), exclusive = true)?.use {
        applyPendingServiceRestartLocked(store, controls, report)
    } ?: PendingRestart.WAITING
}

/** A failed deferred restart already restored the service's payload; say so instead of failing the caller (an agent's exit). */
internal suspend fun reportingRestartFailure(store: InstallationStore, report: (String) -> Unit, block: suspend () -> PendingRestart): PendingRestart = try {
    block()
} catch (error: CancellationException) {
    // A service start timeout is a failure of this attempt, not cancellation of the caller.
    if (error !is TimeoutCancellationException) throw error
    currentCoroutineContext().ensureActive()
    restartFailure(store, "release_service_start_timeout", report)
} catch (error: Exception) {
    restartFailure(store, (error as? CliOperationException)?.code ?: "release_service_restart_failed", report)
}

private fun restartFailure(store: InstallationStore, code: String, report: (String) -> Unit): PendingRestart {
    val selected = try { store.state()?.current?.version } catch (_: Exception) { null }
    report("The local TeamCode service could not restart on the newly selected version ($code). " +
        (selected?.let { "TeamCode $it is selected again for the service and new sessions. " } ?: "") +
        "Run $COMMAND_NAME update to retry.")
    return PendingRestart.FAILED
}

/** Collection is a cleanup: it never fails an activation or a restart that already happened. */
internal fun collectGarbageQuietly(store: InstallationStore) {
    try { store.collectGarbage() } catch (_: Exception) { /* Retried by the next collection. */ }
}

/** Held for the complete agent lifetime, including quiet gaps between inference requests. */
internal class AgentInstallationLease(val store: InstallationStore, private val activity: InstallationLock, private val session: InstallationLock?) : AutoCloseable {
    override fun close() { try { session?.close() } finally { activity.close() } }
}

internal suspend fun agentInstallationLease(serviceExecutable: String?, report: (String) -> Unit = {}): AgentInstallationLease? {
    val executable = servicePlatform().executable
    val store = managedInstallation(executable) ?: serviceExecutable?.let { managedInstallation(it) } ?: return null
    // A restart deferred by sessions that have all ended runs before this one depends on the service.
    applyPendingServiceRestart(store, report = report)
    val activity = acquireInstallationLock(store.paths.lock.toString(), exclusive = false) ?: run {
        report("Waiting for the local TeamCode service to finish restarting.")
        awaitInstallationLock(store, exclusive = false)
    }
    val session = try { runningPayload(store, executable)?.let(store::openSession) }
    catch (error: CancellationException) { activity.close(); throw error }
    catch (_: Exception) { null } // Counting is informational; the activity lease alone protects the service.
    return AgentInstallationLease(store, activity, session)
}

/** The manifest digest of the payload directory [executable] runs from, when it is one of [store]'s versions. */
internal fun runningPayload(store: InstallationStore, executable: String): String? = try {
    val directory = FileSystem.SYSTEM.canonicalize(executable.toPath()).parent
    val versions = directory?.parent
    if (directory == null || versions == null || !sameInstallationPath(versions, store.paths.versions, store.paths.target)) null
    else directory.name.lowercase().takeIf { Regex("[0-9a-f]{64}").matches(it) }
} catch (_: Exception) { null }

/** What the user needs to know after a selection: which version new launches use and when the service follows. */
internal fun activationReport(activation: Activation, store: InstallationStore): List<String> {
    val selected = activation.version.version
    val lines = mutableListOf("Selected TeamCode $selected. Launcher: ${store.paths.launcher}")
    val total = activation.sessions.values.sum()
    val sessions = when {
        total == 0 -> "Running agent sessions keep their current version."
        else -> activation.sessions.entries.groupBy({ store.versionName(it.key) ?: "an older version" }, { it.value })
            .entries.joinToString("; ") { (name, counts) ->
                val count = counts.sum()
                "$count running agent session${if (count == 1) "" else "s"} keep${if (count == 1) "s" else ""} running on $name"
            } + "."
    }
    when (activation.service) {
        ServiceOutcome.RESTARTED -> lines += "The local TeamCode service restarted on $selected."
        ServiceOutcome.DEFERRED -> {
            val running = activation.serviceVersion?.version ?: activation.previous?.version ?: "its current version"
            lines += sessions
            lines += "New sessions and commands use $selected. The local TeamCode service keeps running $running and restarts on $selected when they end."
            // Remote sessions of the device's daemon count here only while their agent has background work in flight.
            lines += "Remote sessions this device's daemon hosts count while their agent has background work in flight (subagents, background commands), " +
                "4 hours at most. To restart now instead, run $COMMAND_NAME service restart: those sessions pause and resume on $selected, " +
                "and background work that does not end within 10 minutes is stopped."
        }
        ServiceOutcome.NONE -> if (total > 0) { lines += sessions; lines += "New sessions and commands use $selected." }
        ServiceOutcome.FAILED -> lines[0] = "TeamCode $selected remains selected. Launcher: ${store.paths.launcher}"
    }
    return lines
}

/** Shown when nothing new is selected but an earlier selection still waits for agent sessions to end. */
internal fun pendingRestartReport(store: InstallationStore): String? {
    val record = store.pendingServiceRestart() ?: return null
    val current = store.state()?.current ?: return null
    return "The local TeamCode service still runs ${record.version.version}; it restarts on ${current.version} when the last agent session ends."
}
