package me.nathanfallet.teamcode.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrubberTest {
    private val first = "first-secret-value"
    private val quoted = "with\"quote\\and-more"
    private val scrubber = Scrubber(listOf(first, quoted))

    @Test
    fun `text and decoded JSON strings lose every secret, and the JSON stays valid`() {
        assertEquals("a [REDACTED] b [REDACTED]", scrubber.scrub("a $first b $quoted"))
        val element = buildJsonObject {
            put("text", "token=$first")
            put(first, "a key")
            putJsonArray("list") { add(kotlinx.serialization.json.JsonPrimitive("x$quoted")) }
            put("number", 3)
        }
        val scrubbed = scrubber.scrub(element) as JsonObject
        val text = scrubbed.toString()
        assertFalse(first in text)
        assertFalse(Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(quoted)).trim('"') in text)
        assertEquals(scrubbed, Json.parseToJsonElement(text))
        assertEquals("3", scrubbed["number"].toString())
        assertTrue("[REDACTED]" in scrubbed.keys)
    }

    @Test
    fun `a process without secrets is returned unchanged`() {
        val element = buildJsonObject { put("a", "b") }
        assertEquals(element, Scrubber(emptyList()).scrub(element))
        assertEquals("a b", Scrubber(emptyList()).scrub("a b"))
    }

    @Test
    fun `stderr is kept bounded, scrubbed even when a secret arrives in two pieces, and never starts with a cut line`() {
        val buffer = StderrBuffer(64, scrubber)
        buffer.append("start ${first.take(5)}")
        buffer.append("${first.drop(5)} end\n")
        assertEquals("start [REDACTED] end\n", buffer.snapshot())
        repeat(10) { buffer.append("line number $it\n") }
        val snapshot = buffer.snapshot()
        assertTrue(snapshot.length <= 64)
        assertTrue(snapshot.startsWith("line number "), snapshot)
        assertTrue(snapshot.endsWith("line number 9\n"))
        // A secret cut by the truncation is not left half visible.
        val tail = StderrBuffer(24, scrubber)
        tail.append("xx $first yy\nzz\n")
        assertEquals("zz\n", tail.snapshot())
    }

    @Test
    fun `printing a request never shows a secret value`() {
        val spec = ProcessSpec(Launchers.NPM, "x@1.0.0", env = mapOf("API_URL" to "https://api.example.org"), secrets = mapOf("API_TOKEN" to first), declaredAt = 1)
        for (text in listOf(spec.toString(), OpsRequest("k", spec, "list", deadlineMs = 1, maxResponseBytes = 1).toString(), WarmRequest("k", spec).toString())) {
            assertFalse(first in text, text)
            assertTrue("API_TOKEN" in text)
        }
    }
}
