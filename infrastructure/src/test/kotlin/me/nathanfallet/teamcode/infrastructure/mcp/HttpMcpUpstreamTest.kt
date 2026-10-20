package me.nathanfallet.teamcode.infrastructure.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import java.net.InetAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.mcp.*
import me.nathanfallet.teamcode.domain.variants.EffectiveMcpServer
import me.nathanfallet.teamcode.domain.variants.SecretValue
import kotlin.test.*

/** A local fake MCP server behind Ktor's mock engine: no network, real gateway HTTP and framing code. */
class HttpMcpUpstreamTest {
    private val credential = "synthetic-upstream-\"credential\""
    private fun server(config: Map<String, String> = emptyMap(), secret: String? = credential) =
        EffectiveMcpServer("tracker", "variant", "team", 1, 0, "https://tracker.example.test/mcp", config, secret?.let(::SecretValue))
    private val allowAll = McpAddressPolicy { }
    private val limits = McpLimits(callTimeoutMillis = 2_000, listTimeoutMillis = 2_000, maxResponseBytes = 4_096)

    private class Fake(val sse: Boolean = false, val session: Boolean = true, val delayMillis: Long = 0, val toolResult: (JsonObject) -> String) {
        val methods = mutableListOf<String>()
        val headers = mutableListOf<Headers>()
        var deleted = false
        val engine = MockEngine { request ->
            headers += request.headers
            if (request.method == HttpMethod.Delete) { deleted = true; return@MockEngine respond("", HttpStatusCode.OK) }
            val message = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val method = message["method"]!!.jsonPrimitive.content
            methods += method
            if (session && method != "initialize") check(request.headers["Mcp-Session-Id"] == "session-1")
            val id = message["id"] ?: return@MockEngine respond("", HttpStatusCode.Accepted)
            val result = when (method) {
                "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}}"""
                "tools/list" -> if (message["params"]?.jsonObject?.get("cursor") == null) """{"tools":[{"name":"search","inputSchema":{"type":"object"}}],"nextCursor":"2"}"""
                    else """{"tools":[{"name":"delete","description":"Deletes"}]}"""
                else -> toolResult(message["params"]!!.jsonObject)
            }
            delay(delayMillis)
            val body = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
            val responseHeaders = headersOf(HttpHeaders.ContentType to listOf(if (sse) "text/event-stream" else "application/json"))
                .let { if (session && method == "initialize") Headers.build { appendAll(it); append("Mcp-Session-Id", "session-1") } else it }
            respond(if (sse) ": comment\n\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\ndata: $body\n\n" else body, HttpStatusCode.OK, responseHeaders)
        }
    }

    @Test fun listsAndCallsThroughStatelessSessionsWithTheCredentialAttachedAndScrubbed() = runBlocking {
        for (sse in listOf(false, true)) {
            val fake = Fake(sse = sse) { params -> """{"content":[{"type":"text","text":"${params["arguments"]!!.jsonObject["q"]!!.jsonPrimitive.content} by ${Json.encodeToString(credential).removeSurrounding("\"")}"}]}""" }
            val upstream = HttpMcpUpstream(HttpClient(fake.engine), allowAll)
            assertEquals(listOf("search", "delete"), upstream.listTools(server(), limits).map { it.name })
            assertTrue(fake.deleted, "Each operation closes its upstream session")
            val result = upstream.callTool(server(), "search", """{"q":"synthetic-argument"}""", limits)
            assertFalse(result.isError)
            assertTrue(result.result.contains("synthetic-argument by [REDACTED]"), result.result)
            assertFalse(result.result.contains("synthetic-upstream"))
            assertTrue(fake.headers.filter { it[HttpHeaders.Authorization] != null }.all { it[HttpHeaders.Authorization] == "Bearer $credential" })
            assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/list", "initialize", "notifications/initialized", "tools/call"), fake.methods)
            assertEquals("2025-06-18", fake.headers.last()["MCP-Protocol-Version"])
        }
    }

    @Test fun customCredentialHeaderAndNoCredential() = runBlocking {
        val fake = Fake(session = false) { """{"content":[],"isError":true}""" }
        val upstream = HttpMcpUpstream(HttpClient(fake.engine), allowAll)
        assertTrue(upstream.callTool(server(mapOf("credentialHeader" to "X-Api-Key")), "search", null, limits).isError)
        assertEquals(credential, fake.headers.last()["X-Api-Key"]); assertNull(fake.headers.last()[HttpHeaders.Authorization])
        assertFalse(fake.deleted, "Without a session there is nothing to delete")
        upstream.callTool(server(secret = null), "search", null, limits)
        assertNull(fake.headers.last()[HttpHeaders.Authorization])
        val refused = assertFailsWith<McpUpstreamFailure> { upstream.callTool(server(mapOf("credentialHeader" to "Host")), "search", null, limits) }
        assertEquals(McpOutcome.UPSTREAM_ERROR, refused.outcome)
    }

    @Test fun aBareTokenSecretIsScrubbedWithoutBreakingJson() = runBlocking {
        val upstream = HttpMcpUpstream(HttpClient(Fake { """{"content":[],"n":4242424242,"4242424242":true}""" }.engine), allowAll)
        val result = upstream.callTool(server(secret = "4242424242"), "search", null, limits)
        assertEquals("""{"content":[],"n":"[REDACTED]","[REDACTED]":true}""", result.result)
    }

    @Test fun boundsTimeAndSizeAndRefusesPrivateDestinations() = runBlocking {
        val slow = HttpMcpUpstream(HttpClient(Fake(delayMillis = 5_000) { "{}" }.engine), allowAll)
        assertEquals(McpOutcome.TIMEOUT, assertFailsWith<McpUpstreamFailure> { slow.callTool(server(), "search", null, limits.copy(callTimeoutMillis = 1_000)) }.outcome)
        val large = HttpMcpUpstream(HttpClient(Fake { """{"content":[{"type":"text","text":"${"x".repeat(8_000)}"}]}""" }.engine), allowAll)
        assertEquals(McpOutcome.TOO_LARGE, assertFailsWith<McpUpstreamFailure> { large.callTool(server(), "search", null, limits) }.outcome)
        val unauthorized = HttpMcpUpstream(HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) }), allowAll)
        val failure = assertFailsWith<McpUpstreamFailure> { unauthorized.listTools(server(), limits) }
        assertFalse(failure.message!!.contains("synthetic"))
        val private = HttpMcpUpstream(HttpClient(MockEngine { error("must not connect") }))
        assertFailsWith<McpUpstreamFailure> { private.listTools(server().copy(endpoint = "https://127.0.0.1/mcp"), limits) }
        for (address in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:10.0.0.1", "2002:a00:1::1", "2001::1", "64:ff9b::a9fe:a9fe"))
            assertFalse(McpAddressPolicy.isPublic(InetAddress.getByName(address)), address)
        for (address in listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111")) assertTrue(McpAddressPolicy.isPublic(InetAddress.getByName(address)), address)
    }

    @Test fun rejectsUnsupportedProtocolVersionsAndInsecureEndpoints() = runBlocking {
        val old = HttpMcpUpstream(HttpClient(MockEngine { respond("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05"}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }), allowAll)
        assertFailsWith<McpUpstreamFailure> { old.listTools(server(), limits) }
        val plain = HttpMcpUpstream(HttpClient(MockEngine { error("must not connect") }), allowAll)
        assertFailsWith<McpUpstreamFailure> { plain.listTools(server().copy(endpoint = "http://tracker.example.test/mcp"), limits) }
        Unit
    }
}
