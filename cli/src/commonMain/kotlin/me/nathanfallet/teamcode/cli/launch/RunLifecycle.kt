package me.nathanfallet.teamcode.cli.launch

import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.api.models.StartRunRequest
import me.nathanfallet.teamcode.client.RunLifecycleClient
import me.nathanfallet.teamcode.client.TeamCodeException

/** Observe only this wrapper. A missing heartbeat never authorizes killing any process. */
internal suspend fun trackedRun(client: RunLifecycleClient, agent: String, workload: String, supported: Boolean,
    heartbeatMillis: Long = 30_000, report: (String) -> Unit, labels: Map<String, String> = emptyMap(), execute: suspend (String?) -> Int): Int = coroutineScope {
    if (!supported) return@coroutineScope execute(null)
    require(heartbeatMillis in 1..60_000)
    val id = Uuid.random().toString()
    var heartbeat: Job? = null
    var exitCode: Int? = null
    var registered = false
    try {
        val lease = client.startRun(StartRunRequest(id, agent, workload, labels))
        check(lease.id == id && lease.state == "RUNNING") { "The server did not establish this launch." }
        registered = true
        heartbeat = launch {
            var warned = false
            while (isActive) {
                delay(heartbeatMillis)
                try {
                    val renewed = withTimeoutOrNull(5_000) { client.heartbeatRun(id) }
                    if (renewed == null) {
                        if (!warned) report("TeamCode launch heartbeat is temporarily unavailable.")
                        warned = true
                    } else warned = false
                } catch (error: CancellationException) { throw error }
                catch (error: TeamCodeException) {
                    if (!warned) report("TeamCode launch heartbeat is unavailable; inference permissions remain enforced by the server.")
                    warned = true
                    if (error.statusCode in setOf(401, 403, 404, 409)) break
                } catch (_: Exception) {
                    if (!warned) report("TeamCode launch heartbeat is temporarily unavailable.")
                    warned = true
                }
            }
        }
        execute(id).also { exitCode = it }
    } finally {
        withContext(NonCancellable) {
            heartbeat?.cancelAndJoin()
            // Also close a start whose response was lost. The UUID is unique to this invocation.
            try {
                val ended = withTimeoutOrNull(3_000) { client.endRun(id, exitCode) }
                if (registered && ended == null) report("TeamCode could not confirm launch completion; its heartbeat will become stale.")
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (registered) report("TeamCode could not confirm launch completion; its heartbeat will become stale.") }
        }
    }
}
