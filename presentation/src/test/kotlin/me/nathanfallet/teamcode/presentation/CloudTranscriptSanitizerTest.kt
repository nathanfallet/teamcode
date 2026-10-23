package me.nathanfallet.teamcode.presentation

import kotlinx.serialization.json.*
import kotlin.test.*

/** The marker that replaces an event too large to store keeps the short fields a client links events with. */
class CloudTranscriptSanitizerTest {
    private fun encode(data: JsonObject) = Json.parseToJsonElement(CloudTranscriptSanitizer.encode(data)).jsonObject

    @Test
    fun aCutEventKeepsItsIdentity() {
        val marker = encode(buildJsonObject {
            put("id", "toolu_01"); put("parentToolUseId", "toolu_parent"); put("isError", true); put("exitCode", 1)
            put("name", "Bash"); put("toolUseId", "toolu_02"); put("requestId", "req-1"); put("messageId", "msg_01"); put("inputId", "in-1"); put("index", 2)
            // Several strings near the 16,000-character cut, so the event is above 60,000 bytes once encoded.
            repeat(5) { put("output$it", "z".repeat(15_000)) }
        })
        assertEquals(true, marker["truncated"]?.jsonPrimitive?.boolean)
        assertTrue(marker.getValue("originalBytes").jsonPrimitive.int > 60_000)
        for ((key, value) in listOf("id" to "toolu_01", "parentToolUseId" to "toolu_parent", "name" to "Bash", "toolUseId" to "toolu_02",
            "requestId" to "req-1", "messageId" to "msg_01", "inputId" to "in-1")) assertEquals(value, marker[key]?.jsonPrimitive?.content, key)
        assertEquals(true, marker["isError"]?.jsonPrimitive?.boolean)
        assertEquals(2, marker["index"]?.jsonPrimitive?.int)
        assertEquals(setOf("truncated", "originalBytes", "id", "parentToolUseId", "name", "toolUseId", "requestId", "messageId", "inputId", "isError", "index"), marker.keys,
            "Never another field: the exit code and the output stay out")
        assertEquals(listOf("truncated", "originalBytes"), marker.keys.take(2), "The marker still starts as before, so no request lookup mistakes it for a request")
    }

    @Test
    fun onlyShortWellTypedIdentityFieldsAreKept() {
        val marker = encode(buildJsonObject {
            put("id", "x".repeat(129)); put("name", "n".repeat(201)); put("isError", "true"); put("requestId", 7); put("messageId", "m".repeat(128))
            put("index", "2"); putJsonObject("parentToolUseId") { put("id", "nested") }
            repeat(5) { put("output$it", "z".repeat(15_000)) }
        })
        assertEquals(setOf("truncated", "originalBytes", "messageId"), marker.keys, "Too long, not a string, or not a boolean: left out")
    }

    @Test
    fun identityIsMaskedLikeTheRestOfTheTranscript() {
        val secret = "sk-ant-1" + "x".repeat(40)
        val marker = encode(buildJsonObject { put("id", secret.take(100)); repeat(5) { put("output$it", "z".repeat(15_000)) } })
        assertFalse(marker["id"]?.jsonPrimitive?.content.orEmpty().contains("x".repeat(40)), "A kept field is the sanitized value")
    }

    @Test
    fun anEventUnderTheLimitIsUnchanged() {
        val data = buildJsonObject { put("id", "toolu_01"); put("output", "short") }
        assertEquals(data, encode(data))
    }
}
