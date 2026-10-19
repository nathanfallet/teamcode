package me.nathanfallet.teamcode.cli.proxy

import io.ktor.http.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.client.TeamCodeClient

/** Fixed inference and local guidance routes. No CONNECT or arbitrary destination. */
fun Application.localProxy(client: TeamCodeClient, localToken: String, maximumBodyBytes: Int = 2 * 1024 * 1024, serviceId: String? = null, serverUrl: String? = null) {
    require(localToken.length >= 32) { "A local proxy token of at least 32 characters is required." }
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
                    put("id", serviceId); put("serverUrl", serverUrl)
                    putJsonArray("capabilities") { add(JsonPrimitive("workload-routing-v1")); add(JsonPrimitive("mcp-relay-v1")) }
                }.toString(), ContentType.Application.Json)
            }
        }
        mcpRelay(client, localToken, maximumBodyBytes)
        for (path in listOf("/v1/messages", "/v1/responses")) {
            for (background in listOf(false, true)) {
                route((if (background) "/_teamcode/workloads/background" else "") + path) {
                    install(io.ktor.server.http.HttpRequestLifecycle) { cancelCallOnClose = true }
                    post {
                        val auth = call.request.header(HttpHeaders.Authorization)
                        val key = call.request.header("x-api-key")
                        if (!equalToken(auth?.removePrefix("Bearer "), localToken) && !equalToken(key, localToken)) {
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
                        if (background) {
                            if (headers["x-teamcode-workload"]?.let { it != "background" } == true) {
                                call.respondText("The workload header conflicts with this background launch.", status = HttpStatusCode.BadRequest)
                                return@post
                            }
                            headers["x-teamcode-workload"] = "background"
                        }
                        client.forwardInference(path, body, headers) { upstream ->
                            listOf("retry-after", "x-teamcode-request-id", "x-teamcode-model", "x-teamcode-provider").forEach { name -> upstream.headers[name]?.let { call.response.header(name, it) } }
                            call.response.header(HttpHeaders.CacheControl, "no-store")
                            call.response.header("X-Accel-Buffering", "no")
                            // Ktor may schedule the writer after respond returns. Keep the SDK response
                            // scope open until the downstream has actually consumed/cancelled the body.
                            val consumed = CompletableDeferred<Unit>()
                            call.respondBytesWriter(upstream.contentType() ?: ContentType.Application.OctetStream, upstream.status) {
                                try {
                                    upstream.bodyAsChannel().copyTo(this)
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
