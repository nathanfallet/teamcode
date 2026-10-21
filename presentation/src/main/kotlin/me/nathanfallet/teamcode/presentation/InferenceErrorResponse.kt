package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.gateway.InferenceErrors
import me.nathanfallet.teamcode.domain.gateway.WireProtocol

/**
 * A refusal on an inference route, in the envelope of the agent's own protocol so the agent prints the message.
 * `x-should-retry: false` marks a state retrying will not change (Claude Code and the Anthropic SDKs honor it), so the
 * user reads the cause instead of watching a retry loop. `Retry-After` says when retrying can succeed.
 */
internal suspend fun ApplicationCall.respondInferenceError(protocol: WireProtocol, status: Int, code: String, message: String,
    retryAfterSeconds: Long? = null, retryable: Boolean = false) {
    (retryAfterSeconds ?: if (status == 429) 60L else null)?.let { response.header(HttpHeaders.RetryAfter, it.toString()) }
    if (!retryable) response.header("x-should-retry", "false")
    response.header("x-teamcode-error", code)
    val type = InferenceErrors.type(protocol, status, code)
    val body = when (protocol) {
        WireProtocol.MESSAGES -> buildJsonObject {
            put("type", "error")
            putJsonObject("error") { put("type", type); put("message", message) }
        }
        WireProtocol.RESPONSES -> buildJsonObject {
            putJsonObject("error") { put("type", type); put("code", code); put("message", message); put("param", JsonNull) }
        }
    }
    respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(status))
}
