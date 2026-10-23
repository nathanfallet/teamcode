package me.nathanfallet.teamcode.cli.proxy

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import me.nathanfallet.teamcode.cli.agents.ActivityReporter
import me.nathanfallet.teamcode.cli.agents.HookActivity
import me.nathanfallet.teamcode.cli.agents.HookGuidance
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tool inputs and transcript paths stop here. Only the session identifier reaches TeamCode, and, when the launch's plugin
 * names its run in `X-TeamCode-Run`, whether the agent works or waits for a person (see [HookActivity]).
 */
internal fun Route.guidanceHook(client: TeamCodeClient, localToken: String, notice: ((String) -> Unit)? = null,
    activityRetryDelays: List<Duration> = listOf(2.seconds, 5.seconds)) {
    val activity = ActivityReporter(client, notice, activityRetryDelays)
    post("/_teamcode/hooks/claude") {
        if (!equalToken(call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer "), localToken)) {
            call.respond(HttpStatusCode.Unauthorized); return@post
        }
        if (call.request.local.serverHost !in setOf("localhost", "127.0.0.1", "::1") || call.request.header(HttpHeaders.Origin) != null) {
            call.respond(HttpStatusCode.Forbidden); return@post
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val bridge = HookGuidance(client)
        var emitted: HookGuidance.Emission? = null
        var output = "{}"
        var state: String? = null
        try {
            withTimeoutOrNull(2_500) {
                if (call.request.contentType().withoutParameters() != ContentType.Application.Json) return@withTimeoutOrNull
                val channel = call.receiveChannel()
                val buffer = ByteArray(65_537)
                var size = 0
                while (size < buffer.size) {
                    val count = channel.readAvailable(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                val payload = buffer.copyOf(size)
                // Too large for guidance (a big tool result), but its event still changes the state.
                state = HookActivity.state(payload)
                if (size > 65_536) return@withTimeoutOrNull
                emitted = bridge.prepare(payload)
                output = emitted?.output ?: "{}"
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Advice failures never block work or expose diagnostics to model context. */ }
        call.respondText(output, ContentType.Application.Json)
        emitted?.let { bridge.acknowledge(it) }
        // After the answer: the agent never waits for this report, and a failure leaves no trace in its context.
        val run = HookActivity.runId(call.request.header("X-TeamCode-Run"))
        if (state != null && run != null) try { activity.report(run, state!!) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
    }
}
