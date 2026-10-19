package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.cli.proxy.localProxy
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*

class LocalProxyTest {
    private val localToken = "synthetic-local-token-long-enough-for-tests"
    private fun payload() = """{"model":"fixture","stream":true,"messages":[]}"""

    @Test fun `local gateway credential replaces caller auth and SSE bytes are preserved`() = testApplication {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals("Bearer server-token", request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["x-api-key"])
            assertEquals("codex-session", request.headers["session-id"])
            assertEquals("/v1/messages", request.url.encodedPath)
            respond("event: done\ndata: {}\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val sdk = TeamCodeClient("https://self.hosted.example", { "server-token" }) { config -> HttpClient(engine) { config(this) } }
        application { localProxy(sdk, localToken) }
        try {
            val response = client.post("/v1/messages") {
                contentType(ContentType.Application.Json)
                header("x-api-key", localToken)
                header("session-id", "codex-session")
                setBody(payload())
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("event: done\ndata: {}\n\n", response.bodyAsText())
            assertEquals(1, calls)
        } finally { sdk.close() }
    }

    @Test fun `unkeyed browser and oversized requests never reach upstream`() = testApplication {
        var calls = 0
        val sdk = TeamCodeClient("https://self.hosted.example", { "server-token" }) { config -> HttpClient(MockEngine {
            calls++; respond("unexpected")
        }) { config(this) } }
        application { localProxy(sdk, localToken, maximumBodyBytes = 64) }
        try {
            assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/messages") {
                contentType(ContentType.Application.Json); setBody(payload())
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/v1/messages") {
                header("x-api-key", localToken); header(HttpHeaders.Origin, "https://attacker.example")
                contentType(ContentType.Application.Json); setBody(payload())
            }.status)
            assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/v1/messages") {
                header("x-api-key", localToken); contentType(ContentType.Application.Json); setBody("x".repeat(65))
            }.status)
            assertEquals(0, calls)
        } finally { sdk.close() }
    }

    @Test fun `shared proxy isolates background launches and rejects conflicting metadata`() = testApplication {
        val forwarded = mutableListOf<Pair<String, String?>>()
        val sdk = TeamCodeClient("https://team.example", { "server-token" }) { config -> HttpClient(MockEngine { request ->
            assertEquals("Bearer server-token", request.headers[HttpHeaders.Authorization])
            forwarded += request.url.encodedPath to request.headers["x-teamcode-workload"]
            respond("data: {}\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { config(this) } }
        application { localProxy(sdk, localToken, serviceId = "shared", serverUrl = "https://team.example") }
        try {
            for (path in listOf("/v1/messages", "/v1/responses")) {
                val background = "/_teamcode/workloads/background$path"
                suspend fun request(url: String, workload: List<String> = emptyList(), authenticated: Boolean = true) = client.post(url) {
                    if (authenticated) bearerAuth(localToken)
                    workload.forEach { header("x-teamcode-workload", it) }
                    contentType(ContentType.Application.Json); setBody(payload())
                }
                assertEquals(HttpStatusCode.OK, request(background).status)
                assertEquals(HttpStatusCode.OK, request(path).status)
                assertEquals(listOf(path to "background", path to null), forwarded.takeLast(2))
                assertEquals(HttpStatusCode.BadRequest, request(background, listOf("interactive")).status)
                assertEquals(HttpStatusCode.BadRequest, request(background, listOf("background", "background")).status)
                assertEquals(HttpStatusCode.Unauthorized, request(background, authenticated = false).status)
            }
            assertEquals(4, forwarded.size, "Rejected requests must not reach the server")
            assertEquals(HttpStatusCode.NotFound, client.post("/_teamcode/workloads/urgent/v1/messages") { bearerAuth(localToken) }.status)
        } finally { sdk.close() }
    }

    @Test fun `MCP relay forwards each server to its own gateway endpoint with the TeamCode credential`() = testApplication {
        val forwarded = mutableListOf<Triple<String, String?, String>>()
        val sdk = TeamCodeClient("https://team.example", { "server-token" }) { config -> HttpClient(MockEngine { request ->
            forwarded += Triple(request.url.encodedPath, request.headers[HttpHeaders.Authorization], (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString())
            assertEquals("2025-06-18", request.headers["MCP-Protocol-Version"])
            when (request.url.encodedPath) {
                "/mcp/revoked" -> respond("", HttpStatusCode.Unauthorized)
                "/mcp/tracker" -> respond("""{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond("""{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"Unknown MCP server."}}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }) { config(this) } }
        application { localProxy(sdk, localToken, maximumBodyBytes = 256) }
        try {
            suspend fun relay(server: String, body: String = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token: String? = localToken, origin: String? = null) = client.post("/mcp/$server") {
                token?.let { bearerAuth(it) }; origin?.let { header(HttpHeaders.Origin, it) }
                header("MCP-Protocol-Version", "2025-06-18"); contentType(ContentType.Application.Json); setBody(body)
            }
            val listed = relay("tracker")
            assertEquals(HttpStatusCode.OK, listed.status)
            assertEquals("""{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}""", listed.bodyAsText())
            assertEquals(HttpStatusCode.NotFound, relay("wiki").status, "The gateway's refusal is relayed as is")
            assertEquals(HttpStatusCode.BadGateway, relay("revoked").status, "A TeamCode sign-in failure must not look like an MCP OAuth challenge")
            assertEquals(listOf("/mcp/tracker", "/mcp/wiki", "/mcp/revoked"), forwarded.map { it.first })
            assertTrue(forwarded.all { it.second == "Bearer server-token" && it.third.contains("tools/list") })
            assertEquals(HttpStatusCode.Unauthorized, relay("tracker", token = null).status)
            assertEquals(HttpStatusCode.Unauthorized, relay("tracker", token = "server-token").status)
            assertEquals(HttpStatusCode.Forbidden, relay("tracker", origin = "https://attacker.example").status)
            assertEquals(HttpStatusCode.NotFound, relay("Bad.Name").status)
            assertEquals(HttpStatusCode.PayloadTooLarge, relay("tracker", body = "x".repeat(257)).status)
            assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/mcp/tracker") { bearerAuth(localToken) }.status)
            assertEquals(HttpStatusCode.NotFound, client.post("/mcp") { bearerAuth(localToken); contentType(ContentType.Application.Json); setBody("{}") }.status)
            assertEquals(3, forwarded.size, "Rejected requests must not reach the server")
        } finally { sdk.close() }
    }
}
