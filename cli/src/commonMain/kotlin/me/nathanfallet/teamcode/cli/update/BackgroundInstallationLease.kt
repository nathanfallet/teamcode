package me.nathanfallet.teamcode.cli.update

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.cli.platform.servicePlatform

/**
 * Holds the installation lease of a remote session hosted by the device's daemon while its agent has background work in
 * flight ([inFlight]: subagents, background commands, monitors). Such a host otherwise holds no lease (decision 12), so
 * `teamcode update` restarted the local service at once, its daemon paused the session, and the work died without a word.
 * Holding it defers that restart, as a `teamcode run` session does, and `teamcode update` says so.
 *
 * Every [pollMillis] it takes the lease ([acquire], which never waits: null while a restart holds the lock, retried at the
 * next poll) when work starts, and gives it back when the work ends or after [maxMillis] of one unbroken stretch, so work
 * that never ends cannot hold an update back for ever; [released] then applies a restart deferred meanwhile. The host's
 * exit closes it too ([run] is cancelled), and the host applies the deferred restart itself.
 */
internal class BackgroundInstallationLease(
    private val inFlight: () -> Boolean,
    private val acquire: () -> AutoCloseable?,
    private val released: suspend () -> Unit,
    private val maxMillis: Long = DEFAULT_MAX_MILLIS,
    private val pollMillis: Long = POLL_MILLIS,
    private val now: () -> Long,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun run(): Nothing {
        var lease: AutoCloseable? = null
        var since: Long? = null
        try {
            while (true) {
                val working = inFlight()
                if (!working) since = null else if (since == null) since = now()
                val within = since?.let { now() - it < maxMillis } == true
                if (working && within && lease == null) lease = try { acquire() } catch (_: Exception) { null }
                if (lease != null && (!working || !within)) {
                    val held = lease
                    lease = null
                    held.close()
                    released()
                }
                sleep(pollMillis)
            }
        } finally {
            withContext(NonCancellable) { lease?.close() }
        }
    }

    companion object {
        /** As the server's default `backgroundWorkMaxMinutes`: four hours of one unbroken stretch at most. */
        const val DEFAULT_MAX_MILLIS = 240 * 60_000L
        const val POLL_MILLIS = 5_000L

        /**
         * The lease of this host's managed installation, without waiting and without applying a pending restart first
         * (which would pause this very session): null outside a managed installation or while a restart holds the lock.
         */
        fun acquireNow(): AutoCloseable? {
            val executable = servicePlatform().executable
            val store = managedInstallation(executable) ?: return null
            val activity = acquireInstallationLock(store.paths.lock.toString(), exclusive = false) ?: return null
            // Counted among the update's running sessions; the activity lease alone defers the restart.
            val session = try { runningPayload(store, executable)?.let(store::openSession) } catch (_: Exception) { null }
            return AgentInstallationLease(store, activity, session)
        }
    }
}
