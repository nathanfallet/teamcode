package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.cli.mcp.WorkflowMcpServer
import me.nathanfallet.teamcode.cli.proxy.WORKFLOW_RELAY_ROUTES
import me.nathanfallet.teamcode.cli.proxy.localProxy
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*

class WorkflowMcpServerTest {
    private val session = "00000000-0000-4000-8000-0000000000cc"
    private val run = """{"id":"r1","workflowId":"wf1","workflowName":"Fix","workflowVersion":1,"status":"running","startedById":"m1","inputs":{},"visits":[],"createdAt":1,"updatedAt":2}"""

    /** The delivery route as the server answers it, recording each request. */
    private inner class Server {
        val requests = mutableListOf<Pair<String, String>>()
        var failure: Pair<HttpStatusCode, String>? = null
        var offline = false
        val client = TeamCodeClient("https://team.example", { "token" }) { config -> HttpClient(MockEngine { request ->
            if (offline) throw java.io.IOException("down")
            requests += "${request.method.value} ${request.url.encodedPath}" to request.body.toByteArray().decodeToString()
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            failure?.let { respond(it.second, it.first, json) } ?: respond(run, HttpStatusCode.OK, json)
        }) { config(this) } }
        val mcp = WorkflowMcpServer(client, session)
    }

    private fun request(method: String, params: JsonObject = JsonObject(emptyMap()), id: Int = 1) =
        buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }.toString()
    private suspend fun Server.tool(name: String, arguments: JsonObject): Pair<Boolean, String> {
        val answer = Json.parseToJsonElement(mcp.handle(request("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) }))!!).jsonObject["result"]!!.jsonObject
        return (answer["isError"]?.jsonPrimitive?.boolean == true) to answer["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
    }

    @Test fun speaksMcpOverLinesAndServesOneTool() = runBlocking {
        val server = Server()
        val init = Json.parseToJsonElement(server.mcp.handle(request("initialize", buildJsonObject { put("protocolVersion", "2025-06-18") }))!!).jsonObject["result"]!!.jsonObject
        assertEquals("2025-06-18", init["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("teamcode-workflow", init["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val tools = Json.parseToJsonElement(server.mcp.handle(request("tools/list"))!!).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(listOf("deliver"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        val deliver = tools.single().jsonObject
        assertTrue("once" in deliver["description"]!!.jsonPrimitive.content && "exactly" in deliver["description"]!!.jsonPrimitive.content)
        assertEquals(listOf("output"), deliver["inputSchema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(server.mcp.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""), "a notification gets no answer")
        assertTrue("-32601" in server.mcp.handle(request("resources/list"))!!)
        assertTrue("Unknown tool" in server.mcp.handle(request("tools/call", buildJsonObject { put("name", "ask_human") }))!!)
        assertTrue("-32700" in server.mcp.handle("not json")!!)
        assertEquals(emptyList(), server.requests)
    }

    @Test fun deliversAStringOrAnyJsonValueForItsOwnSessionOnly() = runBlocking {
        val server = Server()
        val (error, text) = server.tool("deliver", buildJsonObject { put("output", "https://github.com/acme/app/pull/7") })
        assertFalse(error); assertTrue("\"delivered\":true" in text && "End your turn" in text, text)
        assertEquals("POST /api/v1/workflows/session/deliver", server.requests.single().first)
        assertEquals(buildJsonObject { put("sessionId", session); put("output", "https://github.com/acme/app/pull/7") }, Json.parseToJsonElement(server.requests.single().second))
        // The step asked for JSON: an object goes through as an object, not as a string.
        server.tool("deliver", buildJsonObject { putJsonObject("output") { put("verdict", "ok"); put("score", 3) } })
        assertEquals(buildJsonObject { put("verdict", "ok"); put("score", 3) }, Json.parseToJsonElement(server.requests.last().second).jsonObject["output"])
        // Nothing to deliver is refused here, before any call; a session named by the agent is ignored (the tool has no such argument).
        val before = server.requests.size
        val (missing, why) = server.tool("deliver", JsonObject(emptyMap()))
        assertTrue(missing && "output is required" in why, why); assertEquals(before, server.requests.size)
        server.tool("deliver", buildJsonObject { put("output", "x"); put("sessionId", "other") })
        assertEquals(session, Json.parseToJsonElement(server.requests.last().second).jsonObject["sessionId"]!!.jsonPrimitive.content)
    }

    @Test fun serverRefusalsAreReadable() = runBlocking {
        val server = Server()
        val cases = mapOf(
            (HttpStatusCode.BadRequest to "invalid_deliverable") to "Deliver exactly what the step asked for",
            (HttpStatusCode.Conflict to "nothing_to_deliver") to "end your turn",
            (HttpStatusCode.Forbidden to "credential_scope") to "say what you produced in your reply",
            (HttpStatusCode.NotFound to "not_found") to "No workflow run is bound",
            (HttpStatusCode.BadGateway to "relay_sign_in_refused") to "Retrying does not help",
        )
        for ((failure, expected) in cases) {
            server.failure = failure.first to """{"error":{"type":"${failure.second}","message":"Refused."}}"""
            val (error, text) = server.tool("deliver", buildJsonObject { put("output", "x") })
            assertTrue(error); assertTrue(expected in text && failure.second in text, "${failure.second}: $text")
        }
        server.failure = null; server.offline = true
        val (error, text) = server.tool("deliver", buildJsonObject { put("output", "x") })
        assertTrue(error); assertTrue("server_unreachable" in text && "Retry" in text, text)
    }

    @Test fun theRelayForwardsTheDeliveryAndNothingElse() = testApplication {
        val localToken = "synthetic-local-token-long-enough-for-tests"
        val seen = mutableListOf<String>()
        val sdk = TeamCodeClient("https://team.example", { "owner-credential" }) { config -> HttpClient(MockEngine { request ->
            assertEquals("Bearer owner-credential", request.headers[HttpHeaders.Authorization], "The local token is replaced by the proxy's credential")
            seen += "${request.method.value} ${request.url.encodedPath}" + (request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
            respond(run, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { config(this) } }
        application { localProxy(sdk, localToken) }
        try {
            suspend fun call(method: HttpMethod, path: String, token: String? = localToken, origin: String? = null, body: String = """{"output":"x"}""") =
                client.request(path) {
                    this.method = method; token?.let { bearerAuth(it) }; origin?.let { header(HttpHeaders.Origin, it) }
                    if (method == HttpMethod.Post) { contentType(ContentType.Application.Json); setBody(body) }
                }
            val delivered = call(HttpMethod.Post, "/api/v1/workflows/session/deliver")
            assertEquals(HttpStatusCode.OK, delivered.status); assertEquals("no-store", delivered.headers[HttpHeaders.CacheControl])
            assertEquals(listOf("POST /api/v1/workflows/session/deliver"), seen)

            // The starter's credential could do all of this: the relay is the guard.
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/workflows/runs/r1/deliver").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/workflows/runs/r1/cancel").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/workflows/wf1/runs").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/v1/workflows/runs").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/v1/workflows/session/deliver").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/workflows/session/pending").status, "the question is a read, never a write")
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/v1/workflows/session/pending/extra").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/v1/workflows/session/pending/../../runs").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/v1/workflows/session").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Put, "/api/v1/workflows/session/deliver").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/workflows/session/deliver/../../runs/r1/cancel").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/workflows/session/other").status)
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/api/v1/workflows/session/deliver", token = null).status)
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/api/v1/workflows/session/deliver", token = "x".repeat(43)).status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/workflows/session/deliver", origin = "https://evil.example").status)
            assertEquals(HttpStatusCode.PayloadTooLarge, call(HttpMethod.Post, "/api/v1/workflows/session/deliver", body = "{\"output\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}").status)
            assertEquals(1, seen.size, "Refused calls never reach the server")
            // The Stop hook's question is relayed, with its query, and is the only other call.
            val pending = call(HttpMethod.Get, "/api/v1/workflows/session/pending?sessionId=$session")
            assertEquals(HttpStatusCode.OK, pending.status); assertEquals("no-store", pending.headers[HttpHeaders.CacheControl])
            assertEquals(listOf("POST /api/v1/workflows/session/deliver", "GET /api/v1/workflows/session/pending?sessionId=$session"), seen)
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/v1/workflows/session/pending", token = null).status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/api/v1/workflows/session/pending", origin = "https://evil.example").status)
            assertEquals(2, seen.size)
            assertEquals(mapOf(HttpMethod.Get to 1, HttpMethod.Post to 1), WORKFLOW_RELAY_ROUTES.mapValues { it.value.size })
        } finally { sdk.close() }
    }

    @Test fun theServiceAnnouncesTheWorkflowRelay() = testApplication {
        val localToken = "synthetic-local-token-long-enough-for-tests"
        val sdk = TeamCodeClient("https://team.example", { "device-credential" }) { config -> HttpClient(MockEngine { respondOk() }) { config(this) } }
        application { localProxy(sdk, localToken, serviceId = "installation", serverUrl = "https://team.example") }
        try {
            val body = Json.parseToJsonElement(client.get("/_teamcode/service") { bearerAuth(localToken) }.bodyAsText()).jsonObject
            assertTrue(JsonPrimitive("workflow-relay-v1") in body["capabilities"]!!.jsonArray)
        } finally { sdk.close() }
    }
}
