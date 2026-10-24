package me.nathanfallet.teamcode.cli.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.api.models.CloudSessionKinds
import me.nathanfallet.teamcode.api.models.CloudSessionView
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.cloud.attachToSession
import me.nathanfallet.teamcode.cli.platform.DetachedProcess
import me.nathanfallet.teamcode.cli.platform.servicePlatform
import me.nathanfallet.teamcode.cli.platform.startDetachedProcess
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException

/**
 * Starts `teamcode remote host SESSION` in the background (its own session, so the terminal's Ctrl-C or closing it
 * never reaches it; output appended to its private log) and waits until it hosts the session: an epoch after
 * [previousEpoch] whose execution is online. A host that exits first is an error naming its log; one still starting
 * after [HOST_START_MILLIS] is reported as such. The process is never signalled: it stops through the server's pause.
 */
internal suspend fun startBackgroundHost(client: TeamCodeClient, record: RemoteSessionRecord, previousEpoch: Int, report: (String) -> Unit,
    start: (String, List<String>, String) -> DetachedProcess = ::startDetachedProcess, executable: () -> String = { servicePlatform().executable },
    pollMillis: Long = 1_000, timeoutMillis: Long = HOST_START_MILLIS, sessions: RemoteSessions = RemoteSessions()): DetachedProcess {
    val log = sessions.log(record.sessionId)
    val process = try { start(executable(), listOf("remote", "host", record.sessionId), log) }
        catch (_: CliOperationException) { throw CliUserException("The host of session ${record.sessionId} could not be started in the background.") }
    var waited = 0L
    while (waited < timeoutMillis) {
        process.exitCode()?.let { code -> throw CliUserException("The host of session ${record.sessionId} stopped (exit code $code); see its log: $log") }
        val session = try { client.cloudSession(record.sessionId) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        if (session != null && hosted(session, previousEpoch)) {
            report("The host runs in the background (log: $log).")
            return process
        }
        delay(pollMillis); waited += pollMillis
    }
    report("The host is still starting in the background (log: $log).")
    return process
}

/** The background host took the session: a newer epoch, and its execution registered and heartbeating. */
internal fun hosted(session: CloudSessionView, previousEpoch: Int) = session.currentEpoch > previousEpoch && session.host?.online == true

/** `--attach`: this terminal follows the session it just hosted in the background; detaching leaves the host running. */
internal suspend fun attachSession(client: TeamCodeClient, sessionId: String, echo: (String) -> Unit, notice: (String) -> Unit): Nothing =
    attachToSession(client, sessionId, tail = 20, readOnly = false, noWake = false, echo = echo, notice = notice)

/**
 * For `teamcode attach` on a remote session recorded on this device: a callback that starts its host in the
 * background once a message woke it, for a session the daemon does not host. Null for a cloud session, another
 * device's session, one this device has no record of, or one its daemon hosts. Also says when the host is not running.
 */
internal suspend fun localHostWaker(client: TeamCodeClient, sessionId: String, notice: (String) -> Unit): (suspend () -> Unit)? {
    val record = RemoteSessions().load(sessionId) ?: return null
    val session = try { client.cloudSession(sessionId) } catch (error: CancellationException) { throw error } catch (_: TeamCodeException) { return null }
    if (session.kind != CloudSessionKinds.REMOTE) return null
    // Started through the device's daemon: a message that wakes it puts it back in the daemon's queue.
    if (session.host?.folderId != null) return null
    if (session.host?.online != true)
        notice("Its host is not running on this device: a message wakes the session and starts it here (or run teamcode remote resume $sessionId).")
    var started = false
    return {
        if (!started) try {
            val current = client.cloudSession(sessionId)
            if (current.host?.online != true) { startBackgroundHost(client, record, current.currentEpoch, notice); started = true }
        } catch (error: CancellationException) { throw error }
        catch (error: CliUserException) { notice(error.message) }
        catch (_: Exception) { notice("The host could not be started; run teamcode remote resume $sessionId.") }
    }
}

private const val HOST_START_MILLIS = 30_000L
