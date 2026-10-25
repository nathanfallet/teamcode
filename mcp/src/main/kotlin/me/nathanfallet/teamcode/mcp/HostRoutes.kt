package me.nathanfallet.teamcode.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * The host's HTTP surface: host protocol v1 behind the token (`/v1/…`), and the unauthenticated probes `/health` (the
 * JVM answers) and `/ready` (not draining, launcher commands present). Only the TeamCode server's pods reach it (NetworkPolicy).
 */
fun Application.mcpHost(config: HostConfig, supervisor: Supervisor) {
    val expected = sha256(config.token)
    // SIGTERM: drain before the engine closes its connections, then stop every process.
    monitor.subscribe(ApplicationStopPreparing) { runBlocking { supervisor.drain(); supervisor.shutdown() } }
    routing {
        get("/health") { call.respondText("ok") }
        get("/ready") {
            if (supervisor.ready()) call.respondText("ready") else call.respondText("not ready", status = HttpStatusCode.ServiceUnavailable)
        }
        route(HostProtocol.OPS) {
            // A closed connection is the gateway's cancellation: the call's coroutine is cancelled, and the process told.
            install(io.ktor.server.http.HttpRequestLifecycle) { cancelCallOnClose = true }
            post {
                val request = call.admit(config, expected, OpsRequest.serializer()) ?: return@post
                call.handle { supervisor.op(request).let { call.json(OpsResponse.serializer(), it) } }
            }
        }
        post(HostProtocol.WARM) {
            val request = call.admit(config, expected, WarmRequest.serializer()) ?: return@post
            call.handle { call.json(KeyState.serializer(), supervisor.warm(request), HttpStatusCode.Accepted) }
        }
        post(HostProtocol.RETAIN) {
            val request = call.admit(config, expected, RetainRequest.serializer()) ?: return@post
            call.handle { call.json(RetainResponse.serializer(), RetainResponse(supervisor.retain(request.keys))) }
        }
        post(HostProtocol.STOP) {
            val request = call.admit(config, expected, StopRequest.serializer()) ?: return@post
            call.handle { call.json(StopResponse.serializer(), StopResponse(supervisor.stop(request.key))) }
        }
        get(HostProtocol.STATUS) {
            if (!call.authorized(expected)) return@get
            call.json(HostStatus.serializer(), supervisor.status())
        }
    }
}

/** Token (constant time), protocol version, bounded body and its decoding; null once a refusal was answered. */
private suspend fun <T> ApplicationCall.admit(config: HostConfig, expected: ByteArray, serializer: KSerializer<T>): T? {
    if (!authorized(expected)) return null
    val bytes = try { receiveChannel().readBounded(config.maxRequestBytes) } catch (_: TooLarge) {
        refuse(HttpStatusCode.PayloadTooLarge, HostErrors.BAD_REQUEST, "The request exceeds ${config.maxRequestBytes} bytes.")
        return null
    }
    return try { HostProtocol.json.decodeFromString(serializer, bytes.decodeToString()) } catch (_: SerializationException) {
        refuse(HttpStatusCode.BadRequest, HostErrors.BAD_REQUEST, "The request is not valid host protocol JSON.")
        null
    } catch (_: IllegalArgumentException) {
        refuse(HttpStatusCode.BadRequest, HostErrors.BAD_REQUEST, "The request is not valid host protocol JSON.")
        null
    }
}

private suspend fun ApplicationCall.authorized(expected: ByteArray): Boolean {
    val presented = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
    // Hashing both sides gives equal lengths, so the comparison leaks neither the token nor its length.
    if (presented == null || !MessageDigest.isEqual(sha256(presented), expected)) {
        refuse(HttpStatusCode.Unauthorized, HostErrors.UNAUTHORIZED, "A valid host token is required.")
        return false
    }
    val protocol = request.headers[HostProtocol.HEADER]
    if (protocol != HostProtocol.VERSION) {
        refuse(HttpStatusCode.BadRequest, HostErrors.UNSUPPORTED_PROTOCOL,
            "This TeamCode MCP host speaks host protocol ${HostProtocol.VERSION}, the request sent ${protocol ?: "none"}: deploy the server and the mcp host at the same version.")
        return false
    }
    return true
}

private suspend fun ApplicationCall.handle(block: suspend () -> Unit) {
    try { block() } catch (failure: HostFailure) {
        val status = if (failure.code == HostErrors.SHUTTING_DOWN) HttpStatusCode.ServiceUnavailable else HttpStatusCode.BadRequest
        refuse(status, failure.code, failure.message.orEmpty())
    }
}

private suspend fun ApplicationCall.refuse(status: HttpStatusCode, code: String, message: String) =
    json(HostErrorResponse.serializer(), HostErrorResponse(HostError(code, message)), status)

@kotlinx.serialization.Serializable
private data class HostErrorResponse(val error: HostError)

private suspend fun <T> ApplicationCall.json(serializer: KSerializer<T>, value: T, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(HostProtocol.json.encodeToString(serializer, value), ContentType.Application.Json, status)

private class TooLarge : Exception()

private suspend fun ByteReadChannel.readBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = readAvailable(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) break
        if (count == 0) continue
        if (output.size() + count > limit) throw TooLarge()
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
