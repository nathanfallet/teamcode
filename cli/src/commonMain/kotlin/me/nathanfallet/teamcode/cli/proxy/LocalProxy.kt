package me.nathanfallet.teamcode.cli.proxy

import io.ktor.http.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.client.TeamCodeClient

/** Fixed inference and local guidance routes. No CONNECT or arbitrary destination. */
fun Application.localProxy(client: TeamCodeClient, localToken: String, maximumBodyBytes: Int = 2 * 1024 * 1024, serviceId: String? = null, serverUrl: String? = null,
    /** False while the upstream credential is unavailable: answer a retryable `503` rather than forward a request bound to be refused. */
    upstreamReady: (suspend () -> Boolean)? = null,
    /** Receives the cause of a TeamCode refusal, once until the next success or a different cause. */
    report: ((String) -> Unit)? = null) {
    require(localToken.length >= 32) { "A local proxy token of at least 32 characters is required." }
    val refusals = RefusalNotices(report)
    routing {
        guidanceHook(client, localToken)
        if (serviceId != null) get("/_teamcode/service") {
            if (!equalToken(call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer "), localToken)) {
                call.respond(HttpStatusCode.Unauthorized)
            } else if (call.request.local.serverHost !in setOf("localhost", "127.0.0.1", "::1") || call.request.header(HttpHeaders.Origin) != null) {
                call.respond(HttpStatusCode.Forbidden)
            } else {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText(buildJsonObject {
                    put("id", serviceId); put("serverUrl", serverUrl); put("version", me.nathanfallet.teamcode.cli.VERSION)
                    putJsonArray("capabilities") { add(JsonPrimitive("workload-routing-v1")); add(JsonPrimitive("run-routing-v1")); add(JsonPrimitive("mcp-relay-v1")); add(JsonPrimitive("local-token-header-v1")) }
                }.toString(), ContentType.Application.Json)
            }
        }
        mcpRelay(client, localToken, maximumBodyBytes)
        for (path in listOf("/v1/messages", "/v1/responses")) {
            for (mode in listOf("interactive", "background", "run")) {
                val prefix = when (mode) { "background" -> "/_teamcode/workloads/background"; "run" -> "/_teamcode/runs/{runId}/{workload}"; else -> "" }
                route(prefix + path) {
                    install(io.ktor.server.http.HttpRequestLifecycle) { cancelCallOnClose = true }
                    post {
                        val auth = call.request.header(HttpHeaders.Authorization)
                        val key = call.request.header("x-api-key")
                        // A Claude Code that keeps its claude.ai login sends the capability in LOCAL_TOKEN_HEADER and
                        // its own OAuth bearer, which is ignored here and never forwarded (only InferenceHeaders are).
                        val header = call.request.header(LOCAL_TOKEN_HEADER)
                        if (!equalToken(auth?.removePrefix("Bearer "), localToken) && !equalToken(key, localToken) && !equalToken(header, localToken)) {
                            call.respondText("A local TeamCode capability is required.", status = HttpStatusCode.Unauthorized)
                            return@post
                        }
                        val host = call.request.local.serverHost
                        if (host !in setOf("localhost", "127.0.0.1", "::1") || call.request.header(HttpHeaders.Origin) != null) {
                            call.respondText("Browser or non-loopback destinations are not accepted.", status = HttpStatusCode.Forbidden)
                            return@post
                        }
                        if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
                            call.respondText("A JSON body is required.", status = HttpStatusCode.UnsupportedMediaType)
                            return@post
                        }
                        if (upstreamReady?.invoke() == false) {
                            call.response.header(HttpHeaders.RetryAfter, "15")
                            call.respondText("TeamCode inference is temporarily unavailable.", status = HttpStatusCode.ServiceUnavailable)
                            return@post
                        }
                        val buffer = ByteArray(8192)
                        val chunks = mutableListOf<ByteArray>()
                        var size = 0
                        val channel = call.receiveChannel()
                        while (true) {
                            val count = channel.readAvailable(buffer, 0, minOf(buffer.size, maximumBodyBytes - size + 1))
                            if (count < 0) break
                            if (count == 0) continue
                            if (size + count > maximumBodyBytes) {
                                call.respondText("Request is too large.", status = HttpStatusCode.PayloadTooLarge)
                                return@post
                            }
                            size += count
                            chunks.add(buffer.copyOf(count))
                        }
                        val body = ByteArray(size)
                        var offset = 0
                        for (chunk in chunks) { chunk.copyInto(body, offset); offset += chunk.size }
                        val headers = me.nathanfallet.teamcode.api.InferenceHeaders.capture(call.request.headers::getAll).toMutableMap()
                        val launchWorkload = if (mode == "run") call.parameters["workload"] else mode.takeIf { it == "background" }
                        if (launchWorkload != null) {
                            if (launchWorkload !in setOf("interactive", "background") || headers["x-teamcode-workload"]?.let { it != launchWorkload } == true) {
                                call.respondText("The workload header conflicts with this launch.", status = HttpStatusCode.BadRequest)
                                return@post
                            }
                            headers["x-teamcode-workload"] = launchWorkload
                        }
                        if (mode == "run") {
                            val runId = call.parameters["runId"].orEmpty()
                            if (!me.nathanfallet.teamcode.api.models.RunIdentifiers.isValid(runId) || headers["x-teamcode-run-id"]?.let { it != runId } == true) {
                                call.respondText("Invalid or conflicting launch identifier.", status = HttpStatusCode.BadRequest)
                                return@post
                            }
                            headers["x-teamcode-run-id"] = runId
                        }
                        client.forwardInference(path, body, headers) { upstream ->
                            RELAYED_HEADERS.forEach { name -> upstream.headers[name]?.let { call.response.header(name, it) } }
                            call.response.header(HttpHeaders.CacheControl, "no-store")
                            call.response.header("X-Accel-Buffering", "no")
                            val source = upstream.bodyAsChannel()
                            // A TeamCode refusal is small JSON written by the server, never provider content.
                            val head = refusals.inspect(upstream.status.value, upstream.headers["x-teamcode-error"], source)
                            // Ktor may schedule the writer after respond returns. Keep the SDK response
                            // scope open until the downstream has actually consumed/cancelled the body.
                            val consumed = CompletableDeferred<Unit>()
                            call.respondBytesWriter(upstream.contentType() ?: ContentType.Application.OctetStream, upstream.status) {
                                try {
                                    if (head.isNotEmpty()) writeFully(head)
                                    source.copyTo(this)
                                    consumed.complete(Unit)
                                } catch (error: Throwable) {
                                    consumed.completeExceptionally(error)
                                    throw error
                                }
                            }
                            consumed.await()
                        }
                    }
                }
            }
        }
    }
}

private val RELAYED_HEADERS = listOf("retry-after", "x-should-retry", "x-teamcode-error", "x-teamcode-request-id", "x-teamcode-model", "x-teamcode-provider")

/**
 * Prints the cause of a TeamCode refusal once: agents retry the same refusal many times and often show only a generic
 * error meanwhile. A success, or a refusal with another code, allows the next notice.
 */
internal class RefusalNotices(private val report: ((String) -> Unit)?) {
    private val lock = Mutex()
    private var reported: String? = null

    /** Returns the body bytes it read, which the caller relays before the rest of [body]. */
    suspend fun inspect(status: Int, code: String?, body: ByteReadChannel): ByteArray {
        if (report == null) return ByteArray(0)
        if (status in 200..299) { lock.withLock { reported = null }; return ByteArray(0) }
        if (code == null || !CODE.matches(code)) return ByteArray(0)
        val key = "$status $code"
        val first = lock.withLock { (reported != key).also { reported = key } }
        if (!first) return ByteArray(0)
        val head = body.readRemaining(MAXIMUM_BODY_BYTES.toLong()).readByteArray()
        val message = if (!body.isClosedForRead) null else try {
            val error = (Json.parseToJsonElement(head.decodeToString()) as? JsonObject)?.get("error") as? JsonObject
            (error?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
        } catch (_: IllegalArgumentException) { null }
        report("TeamCode refused the request (HTTP $status, $code): ${message?.let(::printable) ?: "see the agent's error."}")
        return head
    }

    private fun printable(text: String) = text.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(600)

    private companion object {
        val CODE = Regex("[a-z_]{1,64}")
        const val MAXIMUM_BODY_BYTES = 16 * 1024
    }
}

/** Carries the local capability beside an agent's own login (Claude Code: through ANTHROPIC_CUSTOM_HEADERS). */
internal const val LOCAL_TOKEN_HEADER = "x-teamcode-local-token"

/**
 * Relays an agent's MCP messages to the gateway endpoint of one assigned server, `/mcp/<server>`. The agent holds
 * only the local launch token; the TeamCode credential is added here and upstream MCP credentials never leave the
 * server. Authorization is the gateway's, on every message.
 */
private fun Routing.mcpRelay(client: TeamCodeClient, localToken: String, maximumBodyBytes: Int) {
    route("/mcp/{server}") {
        install(io.ktor.server.http.HttpRequestLifecycle) { cancelCallOnClose = true }
        post {
            if (!equalToken(call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer "), localToken)) {
                call.respondText("A local TeamCode capability is required.", status = HttpStatusCode.Unauthorized)
                return@post
            }
            if (call.request.local.serverHost !in setOf("localhost", "127.0.0.1", "::1") || call.request.header(HttpHeaders.Origin) != null) {
                call.respondText("Browser or non-loopback destinations are not accepted.", status = HttpStatusCode.Forbidden)
                return@post
            }
            val server = call.parameters["server"].orEmpty()
            if (!TeamCodeClient.MCP_SERVER_NAME.matches(server)) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
                call.respondText("A JSON body is required.", status = HttpStatusCode.UnsupportedMediaType)
                return@post
            }
            val body = call.receiveChannel().readRemaining(maximumBodyBytes + 1L).readByteArray()
            if (body.size > maximumBodyBytes) {
                call.respondText("Request is too large.", status = HttpStatusCode.PayloadTooLarge)
                return@post
            }
            val relayed = try {
                client.forwardMcp(server, body, call.request.header("MCP-Protocol-Version"))
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                call.respondText("The TeamCode server could not be reached.", status = HttpStatusCode.BadGateway)
                return@post
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            // A 401/403 here is about the TeamCode sign-in, not the MCP server: agents would otherwise start an
            // OAuth flow against the local relay.
            if (relayed.status == 401 || relayed.status == 403) {
                call.respondText("TeamCode refused the MCP request; run `teamcode login` or check your access.", status = HttpStatusCode.BadGateway)
                return@post
            }
            if (relayed.body.isEmpty()) call.respond(HttpStatusCode.fromValue(relayed.status))
            else call.respondBytes(relayed.body, ContentType.Application.Json, HttpStatusCode.fromValue(relayed.status))
        }
        // The gateway is stateless: no server-initiated stream and no session to delete.
        get { call.response.header(HttpHeaders.Allow, "POST"); call.respond(HttpStatusCode.MethodNotAllowed) }
        delete { call.response.header(HttpHeaders.Allow, "POST"); call.respond(HttpStatusCode.MethodNotAllowed) }
    }
}

internal fun equalToken(presented: String?, expected: String): Boolean {
    if (presented == null) return false
    var difference = presented.length xor expected.length
    for (index in expected.indices) {
        difference = difference or (expected[index].code xor (presented.getOrNull(index)?.code ?: 0))
    }
    return difference == 0
}
