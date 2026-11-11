package me.nathanfallet.teamcode.cli.launch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.cloud.RunnerCalls
import me.nathanfallet.teamcode.cli.cloud.RunnerInference
import me.nathanfallet.teamcode.cli.cloud.RunnerLoop
import me.nathanfallet.teamcode.cli.remote.remoteSessionCredential
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * The server-side session of one `teamcode run` ([CloudSessionKinds.LOCAL]): what gives the launch its todo, its waits and its
 * artifacts (docs/cloud-sessions.md, "Local sessions"). [client] acts with the credential confined to this session, never with the
 * member's login: it is what the proxy's artifact, wait and todo relays use, so the agent cannot answer its own question. The
 * session lives as long as the launch: [close] deletes it. After 90 s without a heartbeat (the computer slept or lost its network) the server
 * pauses it and revokes its credential; the heartbeat loop then resumes it by itself, whenever the launch is back, and the credential is asked
 * again with the new epoch. A launch that is killed leaves it paused until the team's paused retention ends it.
 */
internal class LocalSession private constructor(
    val sessionId: String,
    val client: TeamCodeClient,
    private val member: TeamCodeClient,
    private val credential: RunnerInference,
    private val heartbeats: Job,
    private val report: (String) -> Unit,
) {
    suspend fun close() {
        withContext(NonCancellable) {
            heartbeats.cancelAndJoin()
            credential.clear()
            client.close()
            // A session whose delete is lost is paused by the server when its heartbeat stops.
            try { withTimeoutOrNull(3_000) { member.deleteCloudSession(sessionId) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { report("The local session could not be closed; the server pauses it after 90 seconds and ends it with the team's paused retention.") }
        }
    }

    companion object {
        /**
         * Keeps [calls] heartbeating, and resumes the session when the server ended its execution: after 90 s of silence (the computer slept or
         * lost its network) it paused the session and revoked the credential, and the launch, being alive, takes the next epoch ([takeEpoch],
         * null when the session is gone for good) and [adopt]s it. Returns only when the session cannot be resumed.
         */
        internal suspend fun keepHosted(calls: RunnerCalls, takeEpoch: suspend () -> Int?, report: (String) -> Unit, adopt: (Int) -> Unit) {
            while (true) {
                RunnerLoop(calls, log = {}, agent = null).run()
                report("The session went offline on the server; resuming it.")
                adopt(takeEpoch() ?: return)
                report("The session is back.")
            }
        }

        /**
         * Takes the next execution of the session (`POST /cloud/sessions/{id}/host`) and returns its epoch, retrying while the server or the
         * network is out of reach, however long that lasts. Null when the session is gone for good (deleted, or ended by its retention).
         */
        private suspend fun resume(member: TeamCodeClient, sessionId: String, report: (String) -> Unit): Int? {
            var wait = RESUME_RETRY_MILLIS
            while (true) {
                try { return member.hostCloudSession(sessionId).epoch }
                catch (error: CancellationException) { throw error }
                catch (error: TeamCodeException) {
                    if (error.statusCode == 404 || (error.statusCode == 409 && error.code == "session_not_running")) {
                        report("The session ended on the server and cannot be resumed; ask_human and the todo are unavailable until the agent is restarted with teamcode run.")
                        return null
                    }
                } catch (_: Exception) { /* No network yet: the computer is just waking up. */ }
                delay(wait)
                wait = (wait * 2).coerceAtMost(RESUME_RETRY_MAX_MILLIS)
            }
        }

        private const val RESUME_RETRY_MILLIS = 5_000L
        private const val RESUME_RETRY_MAX_MILLIS = 60_000L

        /**
         * Opens the session of launch [runId] (create, take the one execution, register, report ready) and starts its heartbeat in [scope].
         * Null, with a notice when something failed, for a server without local sessions, a launch the server did not record, a service
         * key (only an interactive login hosts a session), or any failure: the launch then runs without a session, as before.
         */
        suspend fun open(member: TeamCodeClient, features: List<String>, runId: String?, agent: String, scope: CoroutineScope, report: (String) -> Unit): LocalSession? {
            if (runId == null || CloudSessionKinds.LOCAL_CAPABILITY !in features) return null
            var created: String? = null
            try {
                val directory = try { FileSystem.SYSTEM.canonicalize(".".toPath()).name.take(80).ifBlank { null } } catch (_: Exception) { null }
                val name = (directory?.let { "$agent in $it" } ?: "$agent run").filterNot { it.isISOControl() }
                val session = member.createCloudSession(
                    CreateCloudSessionRequest(name = name, kind = CloudSessionKinds.LOCAL, runId = runId, agent = CloudAgentRequest(kind = agent)), idempotencyKey = runId)
                created = session.id
                val host = member.hostCloudSession(session.id)
                // The epoch changes when the session is resumed after the computer was offline: every call reads the current one.
                var epoch = host.epoch
                val calls = object : RunnerCalls {
                    override suspend fun register() = member.runnerRegister(RunnerRegisterRequest(host.teamId, session.id, epoch))
                    override suspend fun heartbeat(ready: Boolean) = member.runnerHeartbeat(RunnerHeartbeatRequest(host.teamId, session.id, epoch, ready, false))
                    override suspend fun checkpoint(completed: Boolean) = member.runnerCheckpoint(RunnerCheckpointRequest(host.teamId, session.id, epoch, completed))
                }
                // Ready before the agent starts, so its first relay call finds a credential to hold; the loop then keeps the heartbeat.
                calls.register()
                calls.heartbeat(ready = true)
                val credential = remoteSessionCredential(member, features, host.teamId, session.id, { epoch }, report) ?: error("No session credential.")
                val confined = member.withCredential { credential.token() }
                val heartbeats = scope.launch {
                    try {
                        keepHosted(calls, takeEpoch = { resume(member, session.id, report) }, report = report) { next -> epoch = next; credential.reset() }
                    }
                    catch (error: RunnerLoop.RunnerRefused) { report("The server refused the local session (HTTP ${error.status}); the agent goes on without it.") }
                }
                return LocalSession(session.id, confined, member, credential, heartbeats, report)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val why = (error as? TeamCodeException)?.let { "HTTP ${it.statusCode}${it.code?.let { code -> " $code" } ?: ""}" } ?: "network error"
                report("A TeamCode session for this run could not be opened ($why); the agent starts without ask_human and the todo.")
                created?.let { id -> try { withTimeoutOrNull(3_000) { member.deleteCloudSession(id) } } catch (inner: CancellationException) { throw inner } catch (_: Exception) { } }
                return null
            }
        }
    }
}
