package me.nathanfallet.teamcode.infrastructure.providers

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.gateway.ProviderAccountAction

/** Claude has no confirmed machine-readable subcode for this 400. Fail closed on unknown wording and shapes. */
internal object ClaudeAccountRefusals {
    const val MAX_BYTES = 4096
    const val READ_TIMEOUT_MILLIS = 2000L

    private val whitespace = Regex("\\s+")
    // Full provider wording reported in https://github.com/anthropics/claude-code/issues/57569.
    // Never classify an arbitrary request error merely because it quotes these words.
    private val termsMessage = normalize("We've updated our Consumer Terms and Privacy Policy. You'll need to accept them in claude.ai with the email in /status to continue.")
    private fun normalize(message: String) = message.trim().lowercase().replace(whitespace, " ")

    fun classify(bytes: ByteArray): ProviderAccountAction? {
        if (bytes.size > MAX_BYTES) return null
        val value = try { Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }
            catch (_: IllegalArgumentException) { null } ?: return null
        if (value["type"] != JsonPrimitive("error")) return null
        val error = value["error"] as? JsonObject ?: return null
        if (error["type"] != JsonPrimitive("invalid_request_error")) return null
        val message = (error["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (normalize(message) != termsMessage) return null
        return ProviderAccountAction.ACCEPT_CLAUDE_TERMS
    }
}
