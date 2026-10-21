package me.nathanfallet.teamcode.presentation

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.domain.gateway.*
import kotlin.test.*

class GatewayTest {
    private val accessToken = "synthetic-teamcode-token-for-tests-only"
    private class StubGateway(private val expectedHeaders: Map<String, String> = emptyMap()) : GatewayService {
        var requests = 0
        override suspend fun status() = GatewaySnapshot("fixture", 0, requests.toLong())
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) {
            requests++
            assertFalse(request.headers.containsKey("authorization"))
            expectedHeaders.forEach { (name, value) -> assertEquals(value, request.headers[name]) }
            receive(GenerationHead(200, "text/event-stream"), flowOf("data: first\n\n".encodeToByteArray(), "data: last\n\n".encodeToByteArray()))
        }
    }
    @Test fun `real SDK authenticates and reads typed status and capabilities`() = testApplication {
        val gateway = StubGateway()
        application { configureGateway(gateway, accessToken) }
        val sdk = TeamCodeClient("http://localhost", { accessToken }) { config -> createClient {
            config(this)
            // Ktor 3.6 test-host's writer timeout child waits for its own parent completion.
            // Real socket timeouts remain enabled in the SDK and native smoke tests.
            install(HttpTimeout) { socketTimeoutMillis = null }
        } }
        try {
            assertEquals(1, sdk.capabilities().apiVersion)
            assertEquals(GatewayStatus("fixture", 0, 0), sdk.status())
        } finally { sdk.close() }
    }
    @Test fun `unkeyed loopback is refused before any provider work`() = testApplication {
        val gateway = StubGateway()
        application { configureGateway(gateway, accessToken) }
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/status").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/messages").status)
        assertEquals(0, gateway.requests)
    }
    @Test fun `stream traverses SDK unchanged and private headers do not reach gateway`() = testApplication {
        application { configureGateway(StubGateway(mapOf("session-id" to "codex-session")), accessToken) }
        val sdk = TeamCodeClient("http://localhost", { accessToken }) { config -> createClient {
            config(this)
            // Ktor 3.6 test-host's writer timeout child waits for its own parent completion.
            // Real socket timeouts remain enabled in the SDK and native smoke tests.
            install(HttpTimeout) { socketTimeoutMillis = null }
        } }
        try {
            sdk.forwardMessages("""{"model":"fixture","stream":true,"messages":[]}""".encodeToByteArray(), mapOf("session-id" to "codex-session")) {
                assertEquals(HttpStatusCode.OK, it.status)
                assertEquals("data: first\n\ndata: last\n\n", it.bodyAsText())
            }
        } finally { sdk.close() }
    }
    @Test fun `oversized and malformed inputs are rejected before provider work`() = testApplication {
        val gateway = StubGateway()
        application { configureGateway(gateway, accessToken) }
        for ((body, expected) in listOf("{bad" to HttpStatusCode.BadRequest, "x".repeat(2 * 1024 * 1024 + 1) to HttpStatusCode.PayloadTooLarge)) {
            assertEquals(expected, client.post("/v1/messages") { bearerAuth(accessToken); contentType(ContentType.Application.Json); setBody(body) }.status)
        }
        assertEquals(0, gateway.requests)
    }
    private class RefusingGateway(private val failure: me.nathanfallet.teamcode.domain.team.TeamFailure) : GatewayService {
        override suspend fun status() = GatewaySnapshot("fixture", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = throw failure
    }
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.refuse(path: String, body: String) = client.post(path) {
        bearerAuth(accessToken); contentType(ContentType.Application.Json); setBody(body)
    }
    @Test fun `an unusable pool reaches Claude Code as a readable Messages error it does not retry`() = testApplication {
        val failure = me.nathanfallet.teamcode.domain.capacity.DescribePoolUnavailableUseCase()
            .failure(me.nathanfallet.teamcode.domain.capacity.PoolStanding("anthropic", pendingHandover = 6), System.currentTimeMillis())
        application { configureGateway(RefusingGateway(failure), accessToken) }
        val response = refuse("/v1/messages", """{"model":"claude-test","stream":true,"messages":[]}""")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("false", response.headers["x-should-retry"])
        assertEquals("pool_unavailable", response.headers["x-teamcode-error"])
        assertNull(response.headers[HttpHeaders.RetryAfter])
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        val body = kotlinx.serialization.json.Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("error", body["type"]!!.jsonPrimitive.content)
        assertEquals("api_error", body["error"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(failure.message, body["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertTrue("6 accounts are waiting for a handover" in failure.message!!)
    }
    @Test fun `a daily budget is a billing error with its reset and the Responses envelope for Codex`() = testApplication {
        val failure = me.nathanfallet.teamcode.domain.capacity.RetryWindows.dailyBudget("member_allowance", "Your daily TeamCode allowance", "Ask an administrator.", System.currentTimeMillis())
        application { configureGateway(RefusingGateway(failure), accessToken) }
        val messages = refuse("/v1/messages", """{"model":"claude-test","stream":true,"messages":[]}""")
        assertEquals(HttpStatusCode.TooManyRequests, messages.status)
        assertEquals(failure.retryAfterSeconds.toString(), messages.headers[HttpHeaders.RetryAfter])
        assertEquals("false", messages.headers["x-should-retry"])
        val error = kotlinx.serialization.json.Json.parseToJsonElement(messages.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("billing_error", error["type"]!!.jsonPrimitive.content)
        assertEquals(failure.message, error["message"]!!.jsonPrimitive.content)

        val responses = refuse("/v1/responses", """{"model":"gpt-test","stream":true,"input":"hi"}""")
        assertEquals(HttpStatusCode.TooManyRequests, responses.status)
        assertEquals(failure.retryAfterSeconds.toString(), responses.headers[HttpHeaders.RetryAfter])
        val body = kotlinx.serialization.json.Json.parseToJsonElement(responses.bodyAsText()).jsonObject
        assertNull(body["type"])
        val openai = body["error"]!!.jsonObject
        assertEquals("rate_limit_error", openai["type"]!!.jsonPrimitive.content)
        assertEquals("member_allowance", openai["code"]!!.jsonPrimitive.content)
        assertEquals(failure.message, openai["message"]!!.jsonPrimitive.content)
    }
    @Test fun `a transient refusal stays retryable with its Retry-After`() = testApplication {
        application { configureGateway(RefusingGateway(me.nathanfallet.teamcode.domain.team.TeamFailure("queue_turn", 429, "Not your turn.", retryAfterSeconds = 5)), accessToken) }
        val response = refuse("/v1/messages", """{"model":"claude-test","stream":true,"messages":[]}""")
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("5", response.headers[HttpHeaders.RetryAfter])
        assertNull(response.headers["x-should-retry"])
        assertEquals("rate_limit_error", kotlinx.serialization.json.Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // Request validation on an inference route uses the same envelope and is never retried.
        val invalid = refuse("/v1/messages", """{"model":"claude-test","messages":[]}""")
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals("invalid_request_error", kotlinx.serialization.json.Json.parseToJsonElement(invalid.bodyAsText()).jsonObject["error"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("false", invalid.headers["x-should-retry"])
    }
}
