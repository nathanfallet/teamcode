package me.nathanfallet.teamcode.push

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*

/** The largest request the relay reads: a token, an encrypted payload under the FCM limit and a few fields. */
const val MAX_BODY_BYTES = 8_192

/** The relay's HTTP surface. Nothing about a request is logged: not its token, its payload or its instance. */
fun Application.relayModule(relay: Relay) {
    routing {
        get("/healthz") { call.respondText("ok") }
        post(PushRelay.REGISTER_PATH) {
            val body = call.boundedBody() ?: return@post call.respond(HttpStatusCode.PayloadTooLarge)
            val request = try { Serialization.json.decodeFromString<PushRelayRegisterRequest>(body.decodeToString()) } catch (_: Exception) { null }
            val id = request?.let { relay.register(it.publicKey) }
                ?: return@post call.respondJson(HttpStatusCode.BadRequest, Serialization.json.encodeToString(PushRelaySendResponse("rejected", error = "invalid_key")))
            call.respondJson(HttpStatusCode.OK, Serialization.json.encodeToString(PushRelayRegisterResponse(id)))
        }
        post(PushRelay.SEND_PATH) {
            val body = call.boundedBody() ?: return@post call.respond(HttpStatusCode.PayloadTooLarge)
            val headers = call.request.headers
            val answer = relay.send(headers[PushRelay.INSTANCE_HEADER], headers[PushRelay.TIMESTAMP_HEADER], headers[PushRelay.SIGNATURE_HEADER], body) { bytes ->
                try { Serialization.json.decodeFromString<PushRelaySendRequest>(bytes.decodeToString()) } catch (_: Exception) { null }
            }
            answer.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
            call.respondJson(HttpStatusCode.fromValue(answer.status), Serialization.json.encodeToString(answer.body))
        }
    }
}

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, json: String) = respondText(json, ContentType.Application.Json, status)

/** The body, or null when it is larger than [MAX_BODY_BYTES], declared or not. */
private suspend fun ApplicationCall.boundedBody(): ByteArray? {
    if ((request.contentLength() ?: 0) > MAX_BODY_BYTES) return null
    val bytes = receiveChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
    return bytes.takeIf { it.size <= MAX_BODY_BYTES }
}
