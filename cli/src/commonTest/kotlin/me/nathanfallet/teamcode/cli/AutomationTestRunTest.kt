package me.nathanfallet.teamcode.cli

import com.github.ajalt.clikt.core.CliktError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.automations.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*

/** `automations run`: the payload, the dry run, the real run and its idempotency key, against a fake server. */
class AutomationTestRunTest {
    private class Seen(val method: String, val path: String, val body: String, val key: String?)
    private fun capabilities(features: List<String>) = Serialization.json.encodeToString(Capabilities(serverVersion = "1.0.0", mode = "team", features = features))
    private val run = AutomationRunView("r1", "a1", 2, "test", "queued", queuedAt = 0)

    private fun client(features: List<String> = listOf(Automations.CAPABILITY), seen: MutableList<Seen> = mutableListOf(), answer: (String) -> Pair<HttpStatusCode, String>) =
        TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
            HttpClient(MockEngine { request: HttpRequestData ->
                val path = request.url.encodedPath
                seen += Seen(request.method.value, path, request.body.toByteArray().decodeToString(), request.headers["Idempotency-Key"])
                val (status, body) = if (path.endsWith("/capabilities")) HttpStatusCode.OK to capabilities(features) else answer(path)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }) { configure(this) }
        }
    private val payload = JsonObject(mapOf("ticket" to JsonPrimitive(12)))

    @Test fun `a dry run asks for a preview and starts nothing`() = runTest {
        val seen = mutableListOf<Seen>()
        val outcome = client(seen = seen) { HttpStatusCode.OK to Serialization.json.encodeToString(AutomationPreviewView("Fix 12")) }.use { testAutomation(it, "a1", payload, true, null) }
        assertEquals("Fix 12", (outcome as TestOutcome.Preview).preview.renderedPrompt)
        val post = seen.last()
        assertEquals("/api/v1/automations/a1/run", post.path); assertContains(post.body, "\"dryRun\":true"); assertContains(post.body, "\"ticket\":12")
    }

    @Test fun `a real run sends the payload and the given idempotency key`() = runTest {
        val seen = mutableListOf<Seen>()
        val outcome = client(seen = seen) { HttpStatusCode.Accepted to Serialization.json.encodeToString(run) }.use { testAutomation(it, "a1", payload, false, "my-key") }
        assertEquals("r1", (outcome as TestOutcome.Started).run.id)
        assertEquals("my-key", seen.last().key); assertContains(seen.last().body, "\"dryRun\":false")
    }

    @Test fun `a run the server skipped is not presented as one to follow`() {
        assertContains(run.testHint("a1"), "Follow it")
        for (status in listOf("skipped_overlap", "skipped_budget", "invalid_payload")) {
            val hint = run.copy(status = status, reason = "overlap").testHint("a1")
            assertContains(hint, "No session was started"); assertFalse("Follow it" in hint)
        }
    }

    @Test fun `a real run without a key sends a random one`() = runTest {
        val seen = mutableListOf<Seen>()
        client(seen = seen) { HttpStatusCode.Accepted to Serialization.json.encodeToString(run) }.use { testAutomation(it, "a1", null, false, null) }
        assertTrue(seen.last().key.orEmpty().length >= 16, "The server requires a key for a real test")
    }

    @Test fun `a dry run refuses an idempotency key since it starts nothing`() = runTest {
        client { HttpStatusCode.OK to "{}" }.use { assertFailsWith<CliUserException> { testAutomation(it, "a1", null, true, "k") } }
    }

    @Test fun `a server without automations is named too old before any request about them`() = runTest {
        val seen = mutableListOf<Seen>()
        client(features = listOf("cloud.sessions.v1"), seen = seen) { HttpStatusCode.NotFound to "{}" }.use {
            val error = assertFailsWith<CliUserException> { testAutomation(it, "a1", null, false, null) }
            assertContains(error.message.orEmpty(), "too old"); assertContains(error.message.orEmpty(), "automations.v1")
        }
        assertTrue(seen.none { "/automations" in it.path }, "Nothing was sent to the missing routes")
    }

    @Test fun `a payload file must be a JSON object and never echoes its content`() {
        assertEquals(payload, parsePayload("""{"ticket": 12}"""))
        listOf("[1]", "42", "not json {secret-value", "").forEach {
            val error = assertFailsWith<CliUserException>(it) { parsePayload(it) }
            assertFalse("secret-value" in error.message.orEmpty())
        }
        assertContains(assertFailsWith<CliUserException> { parsePayload("{\"a\":\"" + "x".repeat(MAX_PAYLOAD_BYTES) + "\"}") }.message.orEmpty(), "64 KiB")
    }

    @Test fun `standard input is read up to a bound`() {
        val lines = ArrayDeque(listOf("""{"a":""", "1}"))
        assertEquals("{\"a\":\n1}", readStandardInput(100) { lines.removeFirstOrNull() })
        val endless = generateSequence { "x".repeat(1000) }.iterator()
        assertContains(assertFailsWith<CliUserException> { readStandardInput(4096) { endless.next() } }.message.orEmpty(), "larger than")
    }

    @Test fun `identifiers and keys the client would reject get a usage message`() {
        assertEquals("a1-b2", requireIdentifier("a1-b2"))
        listOf("my automation!", "a@b", "", "x".repeat(37)).forEach { assertFailsWith<com.github.ajalt.clikt.core.UsageError>(it) { requireIdentifier(it) } }
        assertNull(requireIdempotencyKey(null)); assertEquals("k:1_2.3-4", requireIdempotencyKey("k:1_2.3-4"))
        assertFailsWith<com.github.ajalt.clikt.core.UsageError> { requireIdempotencyKey("my key!") }
    }

    @Test fun `the payload comes from a file or standard input but not both`() {
        assertEquals(payload, readPayload(null, true) { """{"ticket":12}""" })
        assertNull(readPayload(null, false))
        assertFailsWith<CliktError> { readPayload("payload.json", true) }
        assertContains(assertFailsWith<CliUserException> { readPayload("/definitely/missing/payload.json", false) }.message.orEmpty(), "does not exist")
    }
}
