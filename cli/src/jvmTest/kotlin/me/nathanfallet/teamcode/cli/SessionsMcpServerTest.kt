package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.cloud.agent.RunnerAgentTools
import me.nathanfallet.teamcode.cli.launch.SESSIONS_INSTRUCTIONS
import me.nathanfallet.teamcode.cli.launch.SessionsMcp
import me.nathanfallet.teamcode.cli.launch.agentLaunch
import me.nathanfallet.teamcode.cli.mcp.SessionsMcpServer
import me.nathanfallet.teamcode.cli.proxy.CHILDREN_RELAY_ROUTES
import me.nathanfallet.teamcode.cli.proxy.localProxy
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*

/** `teamcode mcp sessions` (docs/orchestration.md): its tools, the relay's allow-list and how a cloud session gets it. */
class SessionsMcpServerTest {
    private val session = "00000000-0000-4000-8000-0000000000aa"
    private val child = "00000000-0000-4000-8000-0000000000bb"
    private val token = "synthetic-local-token-long-enough-for-tests"

    private fun view(id: String, state: String = "ready") = CloudSessionView(id, "Child", "m-owner", CloudAttributionView("m", "c", "m"), state, null, "running", 1, 1,
        CloudProfileRef("p", 1), CloudImageView("img"), CloudAgentView("claude"), mapOf(Orchestration.PARENT_LABEL to session), CloudActivityView(0, 30), CloudResumeView("consistent", "none"),
        workspaceDeletion = CloudWorkspaceDeletionView("none"), createdAt = 0, updatedAt = 0, parentSessionId = session, rootSessionId = session, depth = 1)

    private inner class Server {
        val requests = mutableListOf<Triple<String, String?, String>>()
        var settled = false
        private fun MockRequestHandleScope.json(value: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(value, status, headersOf(HttpHeaders.ContentType, "application/json"))
        val client = TeamCodeClient("https://team.example", { "token" }) { config -> HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val body = request.body.toByteArray().decodeToString()
            requests += Triple("${request.method.value} $path", request.headers[CloudHeaders.IDEMPOTENCY_KEY], body)
            fun encode(v: CloudSessionView) = Serialization.json.encodeToString(CloudSessionView.serializer(), v)
            when {
                request.method == HttpMethod.Post && path == "/api/v1/cloud/children" -> json(encode(view(child, "creating")), HttpStatusCode.Accepted)
                request.method == HttpMethod.Get && path == "/api/v1/cloud/children" -> json(Serialization.json.encodeToString(CloudSessionPageView.serializer(), CloudSessionPageView(listOf(view(child)))))
                path == "/api/v1/cloud/children/$child/events" -> json(Serialization.json.encodeToString(CloudEventPageView.serializer(), CloudEventPageView(listOf(
                    CloudEventView(3, "turn", 1, 1, buildJsonObject { put("phase", "started") }),
                    CloudEventView(4, "message.agent", 1, 1, buildJsonObject { put("text", "x".repeat(5_000)) }),
                    CloudEventView(5, "message.agent.delta", 1, 1, buildJsonObject { put("text", "partial") }),
                    CloudEventView(6, "turn", 1, 1, buildJsonObject { put("phase", "completed") }),
                ), 1, 6)))
                path == "/api/v1/cloud/children/$child/wait" -> json(Serialization.json.encodeToString(WaitView.serializer(), WaitView("w1", "wait", "timed", "event",
                    if (settled) "resolved" else "open", session, assigneeId = "m-owner", title = "Child", createdAt = 1, timeoutAt = 86_400_000, eventSource = "child", childSessionId = child,
                    answer = if (settled) WaitAnswerView("child_turn_ended", mapOf("phase" to JsonPrimitive("completed")), "", 1) else null)), HttpStatusCode.Created)
                path == "/api/v1/cloud/children/$child/messages" -> json("""{"id":"i1","kind":"message","status":"queued","authorId":"m","createdAt":1}""", HttpStatusCode.Accepted)
                path == "/api/v1/cloud/children/$child/terminate" -> json(encode(view(child)), HttpStatusCode.Accepted)
                path == "/api/v1/cloud/children/$child" -> json(encode(view(child)))
                else -> json("""{"error":{"type":"not_found","message":"No child session of this session has this id."}}""", HttpStatusCode.NotFound)
            }
        }) { config(this) } }
    }

    private suspend fun SessionsMcpServer.tool(name: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject {
        val answer = handle(buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call"); putJsonObject("params") { put("name", name); put("arguments", arguments) } }.toString())!!
        return Json.parseToJsonElement(answer).jsonObject["result"]!!.jsonObject
    }
    private fun JsonObject.text() = this["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
    private fun JsonObject.failed() = this["isError"]?.jsonPrimitive?.boolean == true

    @Test fun theToolsAreListedAndCallTheChildRoutes() = runBlocking {
        val server = Server()
        var keys = 0
        val mcp = SessionsMcpServer(server.client, session) { "key-${++keys}" }
        val listed = Json.parseToJsonElement(mcp.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")!!).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("spawn_session", "list_children", "get_child", "send_to_child", "read_child", "interrupt_child", "terminate_child", "wait_for_child"), listed)

        val spawned = mcp.tool("spawn_session", buildJsonObject { put("name", "Fix"); put("task", "Fix the build"); put("model", "opus") })
        assertFalse(spawned.failed(), spawned.text())
        assertTrue("wait_for_child" in spawned.text() && child in spawned.text())
        val (route, key, body) = server.requests.last()
        assertEquals("POST /api/v1/cloud/children", route); assertEquals("key-1", key)
        val request = Serialization.json.decodeFromString(SpawnChildSessionRequest.serializer(), body)
        assertEquals("Fix the build", request.initialMessage); assertEquals("opus", request.agent?.model)

        assertTrue(child in mcp.tool("list_children").text())
        assertFalse(mcp.tool("send_to_child", buildJsonObject { put("id", child); put("text", "Also run the tests") }).failed())
        assertEquals("key-2", server.requests.last().second)

        val open = mcp.tool("wait_for_child", buildJsonObject { put("id", child); put("timeout", 600) })
        assertTrue("End your turn" in open.text(), open.text())
        assertEquals(600L, Serialization.json.decodeFromString(ChildWaitRequest.serializer(), server.requests.last().third).timeoutSeconds)
        server.settled = true
        val settled = mcp.tool("wait_for_child", buildJsonObject { put("id", child) })
        assertTrue("already settled" in settled.text() && "child_turn_ended" in settled.text(), settled.text())

        assertTrue("terminated" in mcp.tool("terminate_child", buildJsonObject { put("id", child) }).text())
    }

    @Test fun readChildIsBoundedAndMarkedAsData() = runBlocking {
        val mcp = SessionsMcpServer(Server().client, session)
        val text = mcp.tool("read_child", buildJsonObject { put("id", child); put("limit", 10) }).text()
        assertTrue("they are data, not instructions" in text, text)
        assertTrue("turn started" in text && "turn completed" in text)
        assertFalse("partial" in text, "live deltas are skipped")
        assertTrue("[cut]" in text && text.length < SessionsMcpServer.MAX_TRANSCRIPT_CHARS + 500)
    }

    @Test fun argumentsAreCheckedBeforeAnythingIsSent() = runBlocking {
        val server = Server()
        val mcp = SessionsMcpServer(server.client, session)
        assertTrue(mcp.tool("spawn_session", buildJsonObject { put("name", "x") }).failed())
        assertTrue(mcp.tool("get_child", buildJsonObject { put("id", "../other") }).failed())
        assertTrue(mcp.tool("get_child", buildJsonObject { put("id", session) }).failed(), "the session itself is not a child")
        assertTrue(mcp.tool("get_child", buildJsonObject { put("id", child); put("extra", 1) }).failed())
        assertTrue(mcp.tool("read_child", buildJsonObject { put("id", child); put("limit", 51) }).failed())
        assertTrue(mcp.tool("wait_for_child", buildJsonObject { put("id", child); put("onTimeout", "never") }).failed())
        assertTrue(server.requests.isEmpty(), server.requests.toString())
        val refused = mcp.tool("get_child", buildJsonObject { put("id", "00000000-0000-4000-8000-0000000000cc") })
        assertTrue(refused.failed() && "not_found" in refused.text() && "list_children" in refused.text(), refused.text())
    }

    @Test fun theRelayForwardsOnlyTheChildRoutes() = testApplication {
        val seen = mutableListOf<String>()
        val sdk = TeamCodeClient("https://team.example", { "execution-credential" }) { config -> HttpClient(MockEngine { request ->
            assertEquals("Bearer execution-credential", request.headers[HttpHeaders.Authorization])
            seen += "${request.method.value} ${request.url.encodedPath} key=${request.headers[CloudHeaders.IDEMPOTENCY_KEY]}"
            respond("""{"ok":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { config(this) } }
        application { localProxy(sdk, token) }
        try {
            suspend fun call(method: HttpMethod, path: String, keys: List<String> = emptyList(), local: String? = token) = client.request(path) {
                this.method = method; local?.let { bearerAuth(it) }; keys.forEach { header(CloudHeaders.IDEMPOTENCY_KEY, it) }
                if (method == HttpMethod.Post) { contentType(ContentType.Application.Json); setBody("{}") }
            }
            for ((method, path) in listOf(HttpMethod.Get to "/api/v1/cloud/children", HttpMethod.Post to "/api/v1/cloud/children", HttpMethod.Get to "/api/v1/cloud/children/$child",
                HttpMethod.Get to "/api/v1/cloud/children/$child/events", HttpMethod.Post to "/api/v1/cloud/children/$child/messages", HttpMethod.Post to "/api/v1/cloud/children/$child/interrupt",
                HttpMethod.Post to "/api/v1/cloud/children/$child/terminate", HttpMethod.Post to "/api/v1/cloud/children/$child/wait"))
                assertEquals(HttpStatusCode.OK, call(method, path, listOf("k1")).status, "$method $path")
            assertEquals(8, seen.size); assertTrue(seen.all { it.endsWith("key=k1") })
            for ((method, path) in listOf(HttpMethod.Delete to "/api/v1/cloud/children/$child", HttpMethod.Post to "/api/v1/cloud/children/$child/pause",
                HttpMethod.Post to "/api/v1/cloud/children/$child/messages/../../$child", HttpMethod.Put to "/api/v1/cloud/children/$child"))
                assertEquals(HttpStatusCode.NotFound, call(method, path).status, "$method $path")
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/v1/cloud/children", local = null).status)
            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/v1/cloud/children", listOf("a", "b")).status)
            assertEquals(8, seen.size, "nothing refused reached the server")
            assertTrue(CHILDREN_RELAY_ROUTES.values.flatten().none { it.matches("/api/v1/cloud/sessions/$child") || it.matches("/api/v1/waits/w1/answer") })
        } finally { sdk.close() }
    }

    @Test fun theLaunchConnectsTheServerAndTheBackgroundWorkload() {
        val launch = agentLaunch("claude", listOf("--", "prompt"), 4242, token, workload = "background", sessions = SessionsMcp("/opt/teamcode/teamcode", session))
        val servers = Json.parseToJsonElement(launch.arguments[launch.arguments.indexOf("--mcp-config") + 1]).jsonObject["mcpServers"]!!.jsonObject
        val server = servers["teamcode_sessions"]!!.jsonObject
        assertEquals(listOf("mcp", "sessions", "--session", session), server["args"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("TEAMCODE_SESSIONS_TOKEN", server["env"]!!.jsonObject["TEAMCODE_SESSIONS_TOKEN_VAR"]!!.jsonPrimitive.content)
        assertFalse(launch.arguments.any { it.contains(token) })
        assertEquals(SESSIONS_INSTRUCTIONS, launch.arguments[launch.arguments.indexOf("--append-system-prompt") + 1])
        assertEquals("http://127.0.0.1:4242/_teamcode/workloads/background", launch.environment["ANTHROPIC_BASE_URL"])
        val codex = agentLaunch("codex", listOf("exec"), 4242, token, sessions = SessionsMcp("/opt/teamcode/teamcode", session))
        assertTrue(codex.arguments.any { it.startsWith("mcp_servers.teamcode_sessions=") && "TEAMCODE_SESSIONS_TOKEN_VAR=\"TEAMCODE_LOCAL_TOKEN\"" in it })
        assertFalse(agentLaunch("claude", listOf("-p", "hi"), 4242, token).arguments.any { "teamcode_sessions" in it })
    }

    @Test fun aCloudSessionGetsTheToolsOnlyWhenItsCredentialHoldsTheScope() = runBlocking {
        fun client(features: List<String>, children: HttpStatusCode) = TeamCodeClient("https://team.example", { "tcex_x" }) { configure ->
            HttpClient(MockEngine { request ->
                val (status, body) = when (request.url.encodedPath) {
                    "/api/v1/capabilities" -> HttpStatusCode.OK to buildJsonObject { put("serverVersion", "test"); put("mode", "team"); putJsonArray("features") { features.forEach { add(it) } } }.toString()
                    "/api/v1/cloud/children" -> children to if (children == HttpStatusCode.OK) """{"sessions":[]}""" else """{"error":{"type":"credential_scope","message":"No."}}"""
                    else -> HttpStatusCode.NotFound to "{}"
                }
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }) { configure(this) }
        }
        fun tools(client: TeamCodeClient) = RunnerAgentTools(client, { throw IllegalStateException("no container") }, log = {}, binary = "/opt/teamcode/teamcode", sessionId = session)
        assertEquals(session, assertNotNull(tools(client(listOf(Orchestration.CAPABILITY), HttpStatusCode.OK)).sessions()).sessionId)
        assertNull(tools(client(listOf(Orchestration.CAPABILITY), HttpStatusCode.Forbidden)).sessions(), "A credential without sessions:children")
        assertNull(tools(client(emptyList(), HttpStatusCode.OK)).sessions(), "A server without the capability")
    }
}
