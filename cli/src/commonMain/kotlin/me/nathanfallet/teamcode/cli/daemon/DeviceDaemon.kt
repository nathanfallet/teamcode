package me.nathanfallet.teamcode.cli.daemon

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.nathanfallet.teamcode.api.models.CloudHostView
import me.nathanfallet.teamcode.api.models.DaemonOrderView
import me.nathanfallet.teamcode.api.models.DeviceFolderView
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.platform.DetachedProcess
import me.nathanfallet.teamcode.cli.remote.RemoteSessionRecord
import me.nathanfallet.teamcode.cli.remote.RemoteSessions
import me.nathanfallet.teamcode.cli.remote.describe
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import okio.Path
import kotlin.time.TimeSource

/** Starts the host of one accepted session: `teamcode remote host SESSION --accepted-epoch N --team T`, its output in [log]. */
internal fun interface HostLauncher {
    fun start(sessionId: String, epoch: Int, teamId: String, log: String): DetachedProcess
}

/**
 * The device's daemon: the channel through which its owner starts remote sessions on it from any other client. It runs
 * inside the local service (`teamcode service serve`), signed in with the CLI's own `config.json`, one per device
 * (the service holds `daemon.lock`: two daemons on one queue would each accept, and each acceptance fences the other).
 *
 * - **Folders.** It publishes `folders.json` when it starts and whenever the file changes.
 * - **Orders.** It long-polls its queue; each order is checked against its own folders ([checkOrder]) and refused with a
 *   reason, or accepted (`POST /cloud/sessions/{id}/host` opens the epoch and takes it out of the queue) and handed to a
 *   host process ([HostLauncher]) that runs the session with the existing runner code. The session's permission mode,
 *   agent and conversation come from the server and `remote/`, exactly as for `teamcode remote host`.
 * - **Supervision.** A host that exits while its session should still run on that epoch is started again on a new
 *   epoch, at most [maxStarts] times in [startWindowMillis]; the session is then refused (`host_failed`) if it is still
 *   queued. A host whose session paused, ended or moved to another host is forgotten.
 * - **Stop.** It stops polling, asks the server to pause every session it hosts (each agent finishes its turn and its
 *   host checkpoints and exits), waits for them up to [stopWaitMillis], then says it is offline
 *   (`DELETE /devices/current/daemon`). The next daemon resumes the sessions it paused ([DaemonSessions]), and a
 *   session left hosting by a daemon that crashed once the server paused it for its lost host (`host_offline`).
 */
internal class DeviceDaemon(
    private val client: TeamCodeClient,
    private val folders: FolderStore,
    private val records: RemoteSessions,
    private val sessions: DaemonSessions,
    private val launcher: HostLauncher,
    private val version: String,
    private val report: (String) -> Unit,
    private val stopRequested: () -> Boolean,
    private val home: Path?,
    private val windows: Boolean = false,
    private val pollWaitMillis: Long = 20_000,
    private val tickMillis: Long = 2_000,
    private val stopWaitMillis: Long = 540_000,
    private val maxStarts: Int = 3,
    private val startWindowMillis: Long = 600_000,
    private val retryMillis: Long = 30_000,
    private val maxBackoffMillis: Long = 60_000,
) {
    private class Hosted(val sessionId: String, val epoch: Int, val process: DetachedProcess?)

    private val clock = TimeSource.Monotonic.markNow()
    private fun now() = clock.elapsedNow().inWholeMilliseconds
    private val mutex = Mutex()
    private val hosted = mutableMapOf<String, Hosted>()
    private val starts = mutableMapOf<String, MutableList<Long>>()
    private val retryAt = mutableMapOf<String, Long>()
    private var pending: List<DaemonSession> = sessions.read()
    private var published: List<DeviceFolderView>? = null
    @kotlin.concurrent.Volatile private var refused = false

    /** Runs until [stopRequested], then pauses its sessions and goes offline. */
    suspend fun run() = coroutineScope {
        report("Daemon started (version $version).")
        // The folders first, so an order never waits for them; a failure is retried at the next tick.
        mutex.withLock { publishIfChanged(); resumePending() }
        val work = launch {
            launch { supervise() }
            poll()
        }
        while (!stopRequested() && work.isActive) delay(STOP_POLL_MILLIS)
        work.cancelAndJoin()
        withContext(NonCancellable) { shutdown() }
    }

    private suspend fun poll() {
        var failures = 0
        var online = false
        while (true) {
            try {
                val answer = client.pollDeviceDaemon(pollWaitMillis, version)
                if (!online) { report("Online: waiting for sessions to host."); online = true }
                failures = 0
                val acted = mutex.withLock { answer.orders.count { handle(it) } }
                // Orders this daemon cannot act on yet (a host still starting, a retry later) come back at once: do not spin.
                if (answer.orders.isNotEmpty() && acted == 0) delay(IDLE_MILLIS)
            } catch (error: CancellationException) { throw error }
            catch (error: TeamCodeException) {
                online = false
                if (error.code == "cloud_session_credential") { refused = true; report("The server refuses this login as a device (a cloud session's credential); the daemon stays offline."); return }
                if (failures == 0) report(when {
                    error.statusCode == 401 -> "The server did not accept the saved login; sign in again with teamcode login."
                    error.code == "daemon_requires_device" -> "The saved login is not bound to this device; sign in with teamcode login (not teamcode configure)."
                    else -> "The server refused the daemon's poll (${error.describe()}); retrying."
                })
                delay(backoff(++failures))
            } catch (_: Exception) {
                online = false
                if (failures == 0) report("The server is unreachable; retrying.")
                delay(backoff(++failures))
            }
        }
    }

    private fun backoff(failures: Int) = (1_000L shl (failures - 1).coerceAtMost(6)).coerceAtMost(maxBackoffMillis)

    /** Acts on one order; false when it is left for later (its host is still starting, or a retry is not due). */
    private suspend fun handle(order: DaemonOrderView): Boolean {
        val id = order.sessionId
        if (hosted[id]?.let { it.process != null && it.process.exitCode() == null } == true) return false
        val now = now()
        retryAt[id]?.let { if (now < it) return false }
        val check = try { checkOrder(order.folderId, order.directoryName, order.agent, folders.read(), home, windows) }
            catch (_: Exception) { OrderCheck.Refused(RefusalReasons.FOLDER_NOT_CONFIGURED) }
        if (check is OrderCheck.Refused) { refuse(id, check.reason); return true }
        if (!startAllowed(id, now)) { refuse(id, RefusalReasons.HOST_FAILED); return true }
        accept(id, (check as OrderCheck.Accepted).directory, order.agent)
        return true
    }

    private suspend fun refuse(id: String, reason: String) {
        report("Refused session $id ($reason).")
        try { client.refuseDeviceDaemonSession(id, reason) }
        catch (error: CancellationException) { throw error }
        catch (error: TeamCodeException) { report("The refusal of session $id was not recorded (${error.describe()})."); retryAt[id] = now() + retryMillis }
        catch (_: Exception) { retryAt[id] = now() + retryMillis }
    }

    private fun startAllowed(id: String, now: Long): Boolean {
        val recent = starts.getOrPut(id) { mutableListOf() }
        recent.removeAll { now - it > startWindowMillis }
        return recent.size < maxStarts
    }

    /** Opens the session's epoch on this device, records it, and starts its host. */
    private suspend fun accept(id: String, directory: String, agent: String) {
        starts.getOrPut(id) { mutableListOf() }.add(now())
        val host: CloudHostView = try { client.hostCloudSession(id) }
            catch (error: CancellationException) { throw error }
            catch (error: TeamCodeException) { report("Session $id could not be hosted (${error.describe()})."); retryAt[id] = now() + retryMillis; return }
            catch (_: Exception) { retryAt[id] = now() + retryMillis; return }
        retryAt.remove(id)
        records.save(RemoteSessionRecord(id, directory, agent))
        sessions.put(DaemonSession(id, host.epoch, DaemonSession.HOSTING))
        val process = try { launcher.start(id, host.epoch, host.teamId, records.log(id)) }
            catch (error: CancellationException) { throw error }
            catch (error: CliOperationException) { report("The host of session $id could not start (${error.code})."); null }
        // Without a process, the epoch is lost after 120 s and the session waits for a host again.
        if (process != null) hosted[id] = Hosted(id, host.epoch, process)
        report("Hosting session $id (epoch ${host.epoch}) in ${folderLabel(directory, home)}.")
    }

    /** Every [tickMillis]: republish changed folders, resume what the previous daemon paused, watch the hosts. */
    private suspend fun supervise() {
        while (true) {
            mutex.withLock {
                publishIfChanged()
                resumePending()
                watchHosts()
            }
            delay(tickMillis)
        }
    }

    private suspend fun publishIfChanged() {
        val current = try { published(folders.read(), home) } catch (_: Exception) { return }
        if (current == published) return
        try {
            client.publishDeviceFolders(current)
            if (published != null) report("Published ${current.size} folder${if (current.size == 1) "" else "s"}.")
            published = current
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Published at the next tick. */ }
    }

    /** Decision 5: what this device was hosting when its daemon stopped starts again by itself. */
    private suspend fun resumePending() {
        if (pending.isEmpty()) return
        val left = mutableListOf<DaemonSession>()
        for (entry in pending) {
            val view = try { client.cloudSession(entry.sessionId) }
                catch (error: CancellationException) { throw error }
                catch (error: TeamCodeException) { if (error.statusCode == 404) { sessions.remove(entry.sessionId); continue } else { left += entry; continue } }
                catch (_: Exception) { left += entry; continue }
            val paused = view.state == "paused" && view.desiredState == "paused"
            val lost = paused && view.failure?.code == HOST_OFFLINE
            when {
                (entry.state == DaemonSession.PAUSED && paused) || (entry.state == DaemonSession.HOSTING && lost) -> try {
                    client.resumeCloudSession(entry.sessionId)
                    sessions.remove(entry.sessionId)
                    report("Resumed session ${entry.sessionId}.")
                } catch (error: CancellationException) { throw error }
                catch (error: TeamCodeException) { report("Session ${entry.sessionId} could not be resumed (${error.describe()})."); sessions.remove(entry.sessionId) }
                catch (_: Exception) { left += entry }
                // A host of the previous daemon still runs it: paused with the others when this daemon stops.
                entry.state == DaemonSession.HOSTING && view.desiredState == "running" && view.currentEpoch == entry.epoch && view.host?.online == true ->
                    hosted[entry.sessionId] = Hosted(entry.sessionId, entry.epoch, null)
                else -> sessions.remove(entry.sessionId)
            }
        }
        pending = left
    }

    private suspend fun watchHosts() {
        for (entry in hosted.values.toList()) {
            val code = entry.process?.exitCode() ?: continue
            val view = try { client.cloudSession(entry.sessionId) }
                catch (error: CancellationException) { throw error }
                catch (error: TeamCodeException) { if (error.statusCode == 404) { forget(entry.sessionId) }; continue }
                catch (_: Exception) { continue }
            val stillOurs = view.desiredState == "running" && view.state != "terminated" && view.currentEpoch == entry.epoch
            if (!stillOurs) { forget(entry.sessionId); continue }
            val record = records.load(entry.sessionId)
            hosted.remove(entry.sessionId)
            if (record == null || !startAllowed(entry.sessionId, now())) {
                report("The host of session ${entry.sessionId} stopped (exit code $code); not starting it again.")
                sessions.remove(entry.sessionId)
                continue
            }
            report("The host of session ${entry.sessionId} stopped (exit code $code); starting it again.")
            accept(entry.sessionId, record.directory, record.agent)
        }
    }

    private fun forget(id: String) {
        hosted.remove(id)
        sessions.remove(id)
    }

    /** Pauses every hosted session, waits for their hosts, then goes offline. */
    private suspend fun shutdown() {
        val live = mutex.withLock { hosted.values.filter { it.process == null || it.process.exitCode() == null } }
        if (live.isNotEmpty()) report("Stopping: pausing ${live.size} session${if (live.size == 1) "" else "s"}; ${if (live.size == 1) "it resumes" else "they resume"} when the daemon starts again.")
        for (entry in live) {
            // A host of a previous daemon: this one cannot see it exit, so it asks the server whether it still runs.
            if (entry.process == null && !stillRunning(entry)) { sessions.remove(entry.sessionId); continue }
            sessions.put(DaemonSession(entry.sessionId, entry.epoch, DaemonSession.PAUSED))
            try { client.pauseCloudSession(entry.sessionId) }
            catch (error: CancellationException) { throw error }
            catch (error: TeamCodeException) { report("Session ${entry.sessionId} could not be paused (${error.describe()}).") }
            catch (_: Exception) { report("Session ${entry.sessionId} could not be paused: the server is unreachable; its host pauses it when it loses the server.") }
        }
        val deadline = now() + stopWaitMillis
        while (live.any { it.process?.exitCode() == null && it.process != null } && now() < deadline) delay(STOP_POLL_MILLIS)
        if (live.any { it.process != null && it.process.exitCode() == null }) report("Some hosts are still checkpointing; stopping anyway.")
        if (refused) return
        try { client.stopDeviceDaemon(); report("Offline.") }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { report("The server was not told the daemon stopped; it shows it offline within a minute.") }
    }

    private suspend fun stillRunning(entry: Hosted): Boolean = try {
        client.cloudSession(entry.sessionId).let { it.desiredState == "running" && it.currentEpoch == entry.epoch }
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { true }

    private companion object {
        const val STOP_POLL_MILLIS = 250L
        const val IDLE_MILLIS = 1_000L
        const val HOST_OFFLINE = "host_offline"
    }
}
