package me.nathanfallet.teamcode.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Replaces every secret value of a process by [REDACTED], in text and in decoded JSON strings (keys included). */
class Scrubber(secrets: Collection<String>) {
    // Longest first, so a secret that contains another is replaced whole.
    private val secrets = secrets.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }

    fun scrub(text: String): String = secrets.fold(text) { current, secret -> current.replace(secret, REDACTED) }

    /** Scrubs decoded values, so the result stays valid JSON whatever characters the secret holds. */
    fun scrub(element: JsonElement): JsonElement = if (secrets.isEmpty()) element else when (element) {
        is JsonObject -> JsonObject(element.entries.associate { (key, value) -> scrub(key) to scrub(value) })
        is JsonArray -> JsonArray(element.map { scrub(it) })
        // A number or boolean equal to a secret becomes the redacted string too.
        is JsonPrimitive -> if (element is JsonNull || secrets.none { it in element.content }) element else JsonPrimitive(scrub(element.content))
    }

    companion object {
        const val REDACTED = "[REDACTED]"
    }
}

/**
 * The last bytes a process wrote to stderr (and its log notifications), kept in memory only. Raw text is kept and
 * scrubbed when read, so a secret split across two reads is still caught; a truncated first line is dropped for the same reason.
 */
class StderrBuffer(private val capacity: Int, private val scrubber: Scrubber) {
    private val text = StringBuilder()
    private var truncated = false

    @Synchronized
    fun append(chunk: String) {
        text.append(chunk)
        if (text.length > capacity) {
            text.delete(0, text.length - capacity)
            truncated = true
        }
    }

    @Synchronized
    fun snapshot(): String {
        val kept = if (!truncated) text.toString() else text.indexOf("\n").let { if (it < 0) "" else text.substring(it + 1) }
        return scrubber.scrub(kept)
    }
}
