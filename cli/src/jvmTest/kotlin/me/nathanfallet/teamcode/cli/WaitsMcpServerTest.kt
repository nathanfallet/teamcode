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
import me.nathanfallet.teamcode.cli.mcp.WaitsMcpServer
import me.nathanfallet.teamcode.cli.proxy.WAIT_RELAY_ROUTES
import me.nathanfallet.teamcode.cli.proxy.localProxy
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*

class WaitsMcpServerTest {
    private val session = "00000000-0000-4000-8000-0000000000cc"
    private val other = "00000000-0000-4000-8000-0000000000dd"
    private val waitId = "00000000-0000-4000-8000-0000000000ee"

    /** The wait API as the server answers it, in memory, recording each request. */
    private inner class Server {
        val requests = mutableListOf<Triple<String, String?, String>>()
        var created: WaitInput? = null
        var failure: Pair<HttpStatusCode, String>? = null
        var offline = false
        var waits = mutableListOf(wait(waitId, session), wait("w-other", other), wait("perm-1", session, source = "session"))
        var nextBefore: String? = null

        fun wait(id: String, sessionId: String, status: String = "open", source: String = "wait") = WaitView(id, source, "human_request", status = status,
            sessionId = sessionId, assigneeId = "m-owner", assigneeName = "alice", createdById = "m-owner", title = "Which database?",
            fields = listOf(WaitFieldView("db", "choice", "Database", options = listOf(WaitOptionView("mysql", "MySQL")))), createdAt = 1)
        private fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) = Triple(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        private fun encode(wait: WaitView) = Serialization.json.encodeToString(WaitView.serializer(), wait)

        val client = TeamCodeClient("https://team.example", { "token" }) { config -> HttpClient(MockEngine { request ->
            if (offline) throw java.io.IOException("down")
            val path = request.url.encodedPath
            val body = request.body.toByteArray().decodeToString()
            requests += Triple("${request.method.value} $path${request.url.encodedQuery.let { if (it.isEmpty()) "" else "?$it" }}", request.headers[CloudHeaders.IDEMPOTENCY_KEY], body)
            val (text, status, headers) = failure?.let { json(it.second, it.first) } ?: when {
                request.method == HttpMethod.Post && path == "/api/v1/waits" -> {
                    val input = Serialization.json.decodeFromString(CreateWaitRequest.serializer(), body).wait
                    created = input
                    json(encode(wait("w-new", input.sessionId).copy(title = input.title)), HttpStatusCode.Created)
                }
                request.method == HttpMethod.Get && path == "/api/v1/waits" ->
                    json(Serialization.json.encodeToString(WaitPageView.serializer(), WaitPageView(waits, nextBefore)))
                request.method == HttpMethod.Post && path.endsWith("/cancel") ->
                    json(encode(waits.first { it.id == path.removePrefix("/api/v1/waits/").removeSuffix("/cancel") }.copy(status = "cancelled")))
                request.method == HttpMethod.Get && path.startsWith("/api/v1/waits/") ->
                    waits.firstOrNull { it.id == path.removePrefix("/api/v1/waits/") }?.let { json(encode(it)) }
                        ?: json("""{"error":{"type":"not_found","message":"Wait not found."}}""", HttpStatusCode.NotFound)
                else -> json("""{"error":{"type":"not_found","message":"No."}}""", HttpStatusCode.NotFound)
            }
            respond(text, status, headers)
        }) { config(this) } }
        var keys = 0
        val mcp = WaitsMcpServer(client, session) { "key-${++keys}" }
    }

    private fun request(method: String, params: JsonObject = JsonObject(emptyMap()), id: Int = 1) =
        buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }.toString()
    private suspend fun Server.tool(name: String, arguments: JsonObject): Pair<Boolean, String> {
        val answer = Json.parseToJsonElement(mcp.handle(request("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) }))!!).jsonObject["result"]!!.jsonObject
        return (answer["isError"]?.jsonPrimitive?.boolean == true) to answer["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
    }
    private fun args(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun speaksMcpOverLinesAndHasNoAnswerTool() = runBlocking {
        val server = Server()
        val init = Json.parseToJsonElement(server.mcp.handle(request("initialize", buildJsonObject { put("protocolVersion", "2025-06-18") }))!!).jsonObject["result"]!!.jsonObject
        assertEquals("2025-06-18", init["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("teamcode-waits", init["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue("[TeamCode wait answered]" in init["instructions"]!!.jsonPrimitive.content)
        val tools = Json.parseToJsonElement(server.mcp.handle(request("tools/list"))!!).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("ask_human", "list_waits", "get_wait", "cancel_wait"), tools)
        assertFalse(tools.any { "answer" in it }, "An agent never answers its own wait")
        assertNull(server.mcp.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""), "A notification gets no answer")
        assertEquals(-32700, Json.parseToJsonElement(server.mcp.handle("not json")!!).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32601, Json.parseToJsonElement(server.mcp.handle(request("resources/list"))!!).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        val unknown = Json.parseToJsonElement(server.mcp.handle(request("tools/call", buildJsonObject { put("name", "answer_wait") }))!!).jsonObject["error"]!!.jsonObject
        assertEquals(-32602, unknown["code"]!!.jsonPrimitive.int)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun askHumanSendsTheTypedRequestForThisSessionWithAFreshKeyPerCall() = runBlocking {
        val server = Server()
        val (error, text) = server.tool("ask_human", args("""{"title":"Review the plan","body":"See **plan.md**.","assignee":"m-product",
            "fields":[{"id":"feedback","type":"text","label":"Feedback","maxLength":2000},
                      {"id":"env","type":"choice","label":"Where","options":[{"value":"staging"},{"value":"prod","label":"Production","description":"Careful"}],"multiple":true,"allowOther":true,"default":["staging"]},
                      {"id":"ok","type":"confirm","label":"Tested?","required":"true"}],
            "actions":[{"id":"approve","label":"Approve","style":"primary"},{"id":"request_changes","label":"Request changes","requires":["feedback"],"style":"danger"}],
            "refs":[{"kind":"artifact","id":"00000000-0000-4000-8000-0000000000aa","title":"Plan"},{"kind":"url","url":"https://example.com/pr/1"}]}"""))
        assertFalse(error, text)
        val answer = args(text)
        assertEquals("w-new", answer["id"]!!.jsonPrimitive.content); assertEquals("open", answer["status"]!!.jsonPrimitive.content)
        assertTrue("End your turn now" in answer["instruction"]!!.jsonPrimitive.content && "[TeamCode wait answered]" in answer["instruction"]!!.jsonPrimitive.content)
        val input = assertNotNull(server.created)
        assertEquals(session, input.sessionId, "Always this session's"); assertEquals("m-product", input.assigneeId); assertEquals("See **plan.md**.", input.body)
        assertEquals(listOf("feedback", "env", "ok"), input.fields.map { it.id })
        assertEquals(2000, input.fields[0].maxLength)
        assertEquals(listOf(WaitOptionView("staging", "staging"), WaitOptionView("prod", "Production", "Careful")), input.fields[1].options)
        assertTrue(input.fields[1].multiple && input.fields[1].allowOther); assertEquals(JsonArray(listOf(JsonPrimitive("staging"))), input.fields[1].default)
        assertTrue(input.fields[2].required)
        assertEquals(listOf(WaitActionView("approve", "Approve", style = "primary"), WaitActionView("request_changes", "Request changes", listOf("feedback"), "danger")), input.actions)
        assertEquals(listOf(WaitRefView("artifact", id = "00000000-0000-4000-8000-0000000000aa", title = "Plan"), WaitRefView("url", url = "https://example.com/pr/1")), input.refs)
        assertEquals("human", input.resolver)
        assertEquals("POST /api/v1/waits" to "key-1", server.requests.single().let { it.first to it.second })

        assertFalse(server.tool("ask_human", args("""{"title":"Again"}""")).first)
        assertEquals("key-2", server.requests.last().second, "Each call has its own key")
        assertEquals(emptyList(), server.created!!.fields); assertNull(server.created!!.assigneeId, "No assignee asks the session's owner")
    }

    @Test fun askHumanRefusesBadArgumentsBeforeSendingAnything() = runBlocking {
        val server = Server()
        val cases = mapOf(
            """{}""" to "title",
            """{"title":3}""" to "title must be a string",
            """{"title":"t","fields":{"id":"a"}}""" to "fields is a list of objects",
            """{"title":"t","fields":[{"type":"text","label":"A"}]}""" to "fields[0].id is required",
            """{"title":"t","fields":[{"id":"1a","type":"text","label":"A"}]}""" to "fields[0].id starts with a letter",
            """{"title":"t","fields":[{"id":"a","type":"secret","label":"Key"}]}""" to "secret fields are not available yet",
            """{"title":"t","fields":[{"id":"a","type":"color","label":"A"}]}""" to "fields[0].type is one of",
            """{"title":"t","fields":[{"id":"a","type":"text"}]}""" to "fields[0].label is required",
            """{"title":"t","fields":[{"id":"a","type":"choice","label":"A"}]}""" to "give its options",
            """{"title":"t","fields":[{"id":"a","type":"text","label":"A","options":[{"value":"x"}]}]}""" to "its type is choice",
            """{"title":"t","fields":[{"id":"a","type":"text","label":"A","colour":"red"}]}""" to "unknown property colour",
            """{"title":"t","fields":[{"id":"a","type":"text","label":"A","required":"maybe"}]}""" to "required must be true or false",
            """{"title":"t","fields":[{"id":"a","type":"text","label":"A"},{"id":"a","type":"text","label":"B"}]}""" to "Two fields have the id a",
            """{"title":"t","actions":[{"id":"go"}]}""" to "actions[0].label is required",
            """{"title":"t","actions":[{"id":"go","label":"Go","style":"loud"}]}""" to "actions[0].style is primary",
            """{"title":"t","actions":[{"id":"go","label":"Go","requires":"a"}]}""" to "requires is a list",
            """{"title":"t","refs":[{"kind":"url","url":"http://example.com"}]}""" to "https://",
            """{"title":"t","refs":[{"kind":"artifact"}]}""" to "give its id",
            """{"title":"t","refs":[{"kind":"file","id":"x"}]}""" to "refs[0].kind is artifact, session or url",
            """{"title":"t","assignee":"alice@example.com"}""" to "assignee is a member id",
            """{"title":"t","fields":[${(1..33).joinToString(",") { """{"id":"f$it","type":"info","label":"I"}""" }}]}""" to "fields has at most 32",
        )
        for ((arguments, expected) in cases) {
            val (error, text) = server.tool("ask_human", args(arguments))
            assertTrue(error, arguments); assertTrue(expected in text, "$arguments: $text")
        }
        assertTrue(server.requests.isEmpty(), "Nothing reached the server: ${server.requests}")
    }

    @Test fun listsOnlyThisSessionsOwnWaitsBounded() = runBlocking {
        val server = Server()
        val (error, text) = server.tool("list_waits", JsonObject(emptyMap()))
        assertFalse(error, text)
        val listed = args(text)["waits"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf(waitId), listed, "Neither another session's wait nor this session's own permission request")
        assertTrue("warning" in args(text))
        assertEquals("GET /api/v1/waits?status=open&limit=100", server.requests.single().first)
        assertFalse(server.tool("list_waits", args("""{"status":"all"}""")).first)
        assertEquals("GET /api/v1/waits?status=all&limit=100", server.requests.last().first)
        assertTrue(server.tool("list_waits", args("""{"status":"closed"}""")).first)
        // A cursor that never ends stops after a few pages.
        server.requests.clear(); server.nextBefore = "older"
        val endless = args(server.tool("list_waits", JsonObject(emptyMap())).second)
        assertEquals(5, server.requests.size, "At most five pages are read"); assertTrue("more" in endless)
    }

    @Test fun getAndCancelActOnThisSessionsWaitsOnly() = runBlocking {
        val server = Server()
        val read = args(server.tool("get_wait", args("""{"id":"$waitId"}""")).second)
        assertEquals("Which database?", read["title"]!!.jsonPrimitive.content)
        assertEquals("db", read["fields"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("cancelled", args(server.tool("cancel_wait", args("""{"id":"$waitId"}""")).second)["status"]!!.jsonPrimitive.content)
        assertEquals(listOf("GET /api/v1/waits/$waitId", "GET /api/v1/waits/$waitId", "POST /api/v1/waits/$waitId/cancel"), server.requests.map { it.first })

        server.requests.clear()
        for (tool in listOf("get_wait", "cancel_wait")) {
            val (error, text) = server.tool(tool, args("""{"id":"w-other"}"""))
            assertTrue(error); assertTrue("not a wait of this session" in text, text)
        }
        assertTrue(server.tool("cancel_wait", args("""{"id":"perm-1"}""")).first, "A session's own permission request is answered in the session")
        assertTrue(server.requests.none { it.first.startsWith("POST") }, "Another session's wait is never cancelled")
        assertTrue("required" in server.tool("get_wait", JsonObject(emptyMap())).second)
        assertTrue("letters, digits" in server.tool("get_wait", args("""{"id":"../x"}""")).second)
        server.waits[0] = server.wait(waitId, session, status = "resolved")
        assertTrue("already resolved" in server.tool("cancel_wait", args("""{"id":"$waitId"}""")).second)
    }

    @Test fun serverRefusalsAreReadable() = runBlocking {
        val server = Server()
        val cases = mapOf(
            (HttpStatusCode.Conflict to "wait_limit") to "already has 20 open waits",
            (HttpStatusCode.BadRequest to "invalid_wait") to "Fix the arguments",
            (HttpStatusCode.BadRequest to "invalid_assignee") to "id of an active member",
            (HttpStatusCode.UnprocessableEntity to "not_available_yet") to "supported field type",
            (HttpStatusCode.Forbidden to "wait_session_owner_only") to "Only the session's owner",
            (HttpStatusCode.Forbidden to "credential_scope") to "ask in your reply instead",
            (HttpStatusCode.Conflict to "wait_closed") to "already answered or cancelled",
            (HttpStatusCode.BadGateway to "relay_sign_in_refused") to "Retrying does not help",
        )
        for ((failure, expected) in cases) {
            server.failure = failure.first to """{"error":{"type":"${failure.second}","message":"Refused."}}"""
            val (error, text) = server.tool("ask_human", args("""{"title":"t"}"""))
            assertTrue(error); assertTrue(expected in text && failure.second in text, "${failure.second}: $text")
        }
        server.failure = null; server.offline = true
        val (error, text) = server.tool("ask_human", args("""{"title":"t"}"""))
        assertTrue(error); assertTrue("server_unreachable" in text && "Retry" in text, text)
    }

    @Test fun theRelayNeverForwardsAnAnswer() = testApplication {
        val localToken = "synthetic-local-token-long-enough-for-tests"
        val seen = mutableListOf<String>()
        val sdk = TeamCodeClient("https://team.example", { "owner-credential" }) { config -> HttpClient(MockEngine { request ->
            assertEquals("Bearer owner-credential", request.headers[HttpHeaders.Authorization], "The local token is replaced by the proxy's credential")
            seen += "${request.method.value} ${request.url.encodedPath} key=${request.headers[CloudHeaders.IDEMPOTENCY_KEY]}"
            when (request.url.encodedPath) {
                "/api/v1/waits/$waitId" -> respond("""{"error":{"type":"unauthorized","message":"No."}}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond("""{"ok":true}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType to listOf("application/json"), CloudHeaders.IDEMPOTENCY_REPLAYED to listOf("true")))
            }
        }) { config(this) } }
        application { localProxy(sdk, localToken) }
        try {
            suspend fun call(method: HttpMethod, path: String, token: String? = localToken, origin: String? = null, keys: List<String> = emptyList()) =
                client.request(path) {
                    this.method = method; token?.let { bearerAuth(it) }; origin?.let { header(HttpHeaders.Origin, it) }
                    keys.forEach { header(CloudHeaders.IDEMPOTENCY_KEY, it) }
                    if (method == HttpMethod.Post) { contentType(ContentType.Application.Json); setBody("{}") }
                }
            val asked = call(HttpMethod.Post, "/api/v1/waits", keys = listOf("key-1"))
            assertEquals(HttpStatusCode.Created, asked.status); assertEquals("true", asked.headers[CloudHeaders.IDEMPOTENCY_REPLAYED]); assertEquals("no-store", asked.headers[HttpHeaders.CacheControl])
            assertEquals(HttpStatusCode.Created, call(HttpMethod.Get, "/api/v1/waits?status=open&limit=100").status)
            assertEquals(HttpStatusCode.Created, call(HttpMethod.Post, "/api/v1/waits/$waitId/cancel").status)
            val signIn = call(HttpMethod.Get, "/api/v1/waits/$waitId")
            assertEquals(HttpStatusCode.BadGateway, signIn.status); assertEquals("relay_sign_in_refused", signIn.headers["x-teamcode-error"])
            val relayed = seen.size

            // The owner's credential would answer: the relay is the guard.
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/waits/$waitId/answer").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/waits/$waitId/cancel/../answer").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/waits/$waitId%2Fanswer").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Put, "/api/v1/waits/$waitId").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/api/v1/waits/$waitId").status)
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/v1/waits", token = null).status)
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/v1/waits", token = "x".repeat(43)).status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/api/v1/waits", origin = "https://evil.example").status)
            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/v1/waits", keys = listOf("a", "b")).status)
            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/v1/waits", keys = listOf("bad key")).status)
            assertEquals(relayed, seen.size, "Refused calls never reach the server")
            assertEquals("POST /api/v1/waits key=key-1", seen.first())
            assertTrue(WAIT_RELAY_ROUTES.values.flatten().none { it.matches("/api/v1/waits/$waitId/answer") })
        } finally { sdk.close() }
    }

    @Test fun theServiceAnnouncesTheWaitRelay() = testApplication {
        val localToken = "synthetic-local-token-long-enough-for-tests"
        val sdk = TeamCodeClient("https://team.example", { "device-credential" }) { config -> HttpClient(MockEngine { respondOk() }) { config(this) } }
        application { localProxy(sdk, localToken, serviceId = "installation", serverUrl = "https://team.example") }
        try {
            val body = Json.parseToJsonElement(client.get("/_teamcode/service") { bearerAuth(localToken) }.bodyAsText()).jsonObject
            assertTrue(JsonPrimitive("wait-relay-v1") in body["capabilities"]!!.jsonArray)
        } finally { sdk.close() }
    }
}
