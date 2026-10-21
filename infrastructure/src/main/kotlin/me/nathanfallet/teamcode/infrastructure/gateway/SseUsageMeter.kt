package me.nathanfallet.teamcode.infrastructure.gateway

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.routing.TokenUsage

/** Reads usage metadata only; no prompt or generated content is retained after a bounded event line. */
class SseUsageMeter {
    var lastEvent: String = "none"
        private set
    var failureCategory: String = "none"
        private set
    private val line = ByteArrayOutputStream()
    private var invalid = false
    private var dropping = false
    private var input: Long? = null
    private var cacheRead = 0L
    private var cacheCreate = 0L
    private var output: Long? = null
    private var responsesCached: Long? = null
    private var complete = false
    fun accept(bytes: ByteArray) {
        for (byte in bytes) {
            if (byte == 10.toByte()) {
                if (!dropping) consume(line.toString(Charsets.UTF_8))
                line.reset(); dropping = false
            } else if (!dropping) {
                if (line.size() >= 262_144) { invalid = true; dropping = true; line.reset() }
                else line.write(byte.toInt())
            }
        }
    }
    val settledUnits: Long? get() {
        if (!complete || invalid || input == null || output == null) return null
        return input!! + cacheRead + cacheCreate + output!! * 5
    }
    /**
     * The provider's raw tokens, available exactly when [settledUnits] is. Messages reports uncached input and the two
     * cache figures separately; Responses includes cached input in `input_tokens`, so its cached detail is split out
     * here (it has no cache write figure). Credits above are unchanged by this split.
     */
    val tokens: TokenUsage? get() {
        if (settledUnits == null) return null
        val cached = responsesCached ?: return TokenUsage(input!!, output!!, cacheRead, cacheCreate)
        return TokenUsage((input!! - cached).coerceAtLeast(0), output!!, minOf(cached, input!!), 0)
    }
    private fun consume(text: String) {
        if (!text.startsWith("data:")) return
        val data = text.removePrefix("data:").trim()
        if (data == "[DONE]") return
        val json = try { Json.parseToJsonElement(data) as? JsonObject } catch (_: IllegalArgumentException) { null }
        if (json == null) { invalid = true; return }
        val type = (json["type"] as? JsonPrimitive)?.contentOrNull
        if (type in setOf("message_start", "message_delta", "message_stop", "response.created", "response.in_progress", "response.output_text.delta", "response.completed", "response.incomplete", "response.failed", "error")) lastEvent = type!!
        if (type in setOf("error", "response.failed")) {
            val error = json["error"] ?: (json["response"] as? JsonObject)?.get("error") ?: json
            val diagnostic = error.toString().lowercase()
            failureCategory = listOf("quota", "rate_limit", "unauthorized", "unsupported", "model", "instructions", "overloaded", "invalid", "internal").firstOrNull { it in diagnostic } ?: "provider_error"
        }
        when (type) {
            "response.completed", "response.incomplete" -> {
                val usage = (json["response"] as? JsonObject)?.get("usage") as? JsonObject
                record(usage)
                responsesCached = ((usage?.get("input_tokens_details") as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.longOrNull
                    ?.takeIf { it in 0..10_000_000 } ?: 0
                complete = true // Responses input_tokens already includes cached tokens; do not add its detail again.
            }
            "error", "response.failed" -> invalid = true
            "message_stop" -> complete = true
            "message_start" -> record((json["message"] as? JsonObject)?.get("usage") as? JsonObject)
            "message_delta" -> record(json["usage"] as? JsonObject)
        }
    }
    private fun record(usage: JsonObject?) {
        if (usage == null) return
        fun field(name: String): Long? {
            val value = usage[name] ?: return null
            val number = (value as? JsonPrimitive)?.longOrNull
            if (number == null || number !in 0..10_000_000) { invalid = true; return null }
            return number
        }
        field("input_tokens")?.let { input = maxOf(input ?: 0, it) }
        field("output_tokens")?.let { output = maxOf(output ?: 0, it) }
        field("cache_read_input_tokens")?.let { cacheRead = maxOf(cacheRead, it) }
        field("cache_creation_input_tokens")?.let { cacheCreate = maxOf(cacheCreate, it) }
    }
}
