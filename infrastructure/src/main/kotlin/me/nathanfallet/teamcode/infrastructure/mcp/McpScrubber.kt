package me.nathanfallet.teamcode.infrastructure.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Removes an upstream's secret values from what the gateway returns, in case the upstream echoes one: the HTTPS
 * credential, or every secret variable of a hosted command. JSON is scrubbed on the decoded tree (string values, keys
 * and bare tokens), so the result stays valid JSON whatever the secret looks like.
 */
internal class McpScrubber(secrets: Collection<String>) {
    // Longest first, so a secret that contains another is replaced whole.
    private val secrets = secrets.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }

    fun scrub(text: String): String = secrets.fold(text) { scrubbed, secret -> scrubbed.replace(secret, REDACTED) }

    fun scrub(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.associate { (key, value) -> scrub(key) to scrub(value) })
        is JsonArray -> JsonArray(element.map { scrub(it) })
        is JsonPrimitive -> if (element is JsonNull || secrets.none { element.content.contains(it) }) element else JsonPrimitive(scrub(element.content))
    }

    fun scrub(element: JsonObject) = scrub(element as JsonElement) as JsonObject

    companion object {
        const val REDACTED = "[REDACTED]"
    }
}
