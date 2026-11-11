package me.nathanfallet.teamcode.cli.launch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
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
 * session lives as long as the launch: [close] deletes it, and a launch that is killed leaves it to the server, which ends it after 12 hours
 * without a heartbeat (a computer that sleeps or goes offline for less beats again and keeps its session).
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
            // A session whose delete is lost is ended by the server when its heartbeat has stopped for good.
            try { withTimeoutOrNull(3_000) { member.deleteCloudSession(sessionId) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { report("The local session could not be closed; the server ends it once its heartbeat has been silent long enough.") }
        }
    }

    companion object {
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
                val calls = object : RunnerCalls {
                    override suspend fun register() = member.runnerRegister(RunnerRegisterRequest(host.teamId, session.id, host.epoch))
                    override suspend fun heartbeat(ready: Boolean) = member.runnerHeartbeat(RunnerHeartbeatRequest(host.teamId, session.id, host.epoch, ready, false))
                    override suspend fun checkpoint(completed: Boolean) = member.runnerCheckpoint(RunnerCheckpointRequest(host.teamId, session.id, host.epoch, completed))
                }
                // Ready before the agent starts, so its first relay call finds a credential to hold; the loop then keeps the heartbeat.
                calls.register()
                calls.heartbeat(ready = true)
                val credential = remoteSessionCredential(member, features, host.teamId, session.id, host.epoch, report) ?: error("No session credential.")
                val confined = member.withCredential { credential.token() }
                val loop = RunnerLoop(calls, log = {}, agent = null)
                val heartbeats = scope.launch {
                    try {
                        loop.run()
                        // The loop only returns when the server ended or fenced this session: say so, or the tools just start failing.
                        report("The server ended this session; ask_human and the todo are unavailable until the agent is restarted with teamcode run.")
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
