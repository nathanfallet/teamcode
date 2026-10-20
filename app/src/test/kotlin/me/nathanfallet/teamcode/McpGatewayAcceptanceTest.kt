package me.nathanfallet.teamcode

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.mcp.*
import me.nathanfallet.teamcode.infrastructure.database.EXECUTION_SCOPES
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.variants.*
import me.nathanfallet.teamcode.infrastructure.database.*
import me.nathanfallet.teamcode.infrastructure.mcp.HttpMcpUpstream
import me.nathanfallet.teamcode.infrastructure.mcp.McpAddressPolicy
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.presentation.configureGateway
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/**
 * Roadmap acceptance for the MCP gateway: real MySQL, two replica pools, the HTTP gateway and two local fake upstream
 * MCP servers served in-process by the Ktor test engine. No real network.
 */
class McpGatewayAcceptanceTest {
    private val trackerCredential = "synthetic-tracker-credential"
    private val wikiCredential = "synthetic-alice-wiki-credential"

    /** A minimal Streamable HTTP MCP server. [sse] answers with an event stream and requires a session. */
    private fun Route.fakeMcp(credential: String, tools: List<String>, sse: Boolean, calls: ConcurrentLinkedQueue<String>) {
        post("/mcp") {
            val presented = call.request.headers[HttpHeaders.Authorization]
            if (presented != "Bearer $credential") return@post call.respond(HttpStatusCode.Unauthorized)
            val message = Json.parseToJsonElement(call.receiveText()).jsonObject
            val method = message["method"]!!.jsonPrimitive.content
            if (sse && method != "initialize" && call.request.headers["Mcp-Session-Id"] != "wiki-session") return@post call.respond(HttpStatusCode.NotFound)
            val id = message["id"] ?: return@post call.respond(HttpStatusCode.Accepted)
            val result = when (method) {
                "initialize" -> buildJsonObject { put("protocolVersion", "2025-06-18"); putJsonObject("capabilities") { putJsonObject("tools") {} }; putJsonObject("serverInfo") { put("name", "fake"); put("version", "1") } }
                "tools/list" -> buildJsonObject { putJsonArray("tools") { tools.forEach { addJsonObject { put("name", it); put("description", "Fake $it"); putJsonObject("inputSchema") { put("type", "object") } } } } }
                "tools/call" -> {
                    val params = message["params"]!!.jsonObject
                    val tool = params["name"]!!.jsonPrimitive.content
                    calls += tool
                    // `whoami` misbehaves and echoes the gateway's credential: the gateway must scrub it.
                    val text = if (tool == "whoami") "you are $presented" else "$tool(${params["arguments"]})"
                    buildJsonObject { putJsonArray("content") { addJsonObject { put("type", "text"); put("text", text) } } }
                }
                else -> null
            }
            val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); if (result != null) put("result", result) else putJsonObject("error") { put("code", -32601); put("message", "nope") } }.toString()
            if (sse) {
                if (method == "initialize") call.response.header("Mcp-Session-Id", "wiki-session")
                call.respondText("event: message\ndata: $body\n\n", ContentType.Text.EventStream)
            } else call.respondText(body, ContentType.Application.Json)
        }
        delete("/mcp") { call.respond(HttpStatusCode.OK) }
    }

    @Test fun membersSeeAndCallOnlyAssignedToolsWithImmediateRevocationAndNoCredentialLeak() { runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                val team = SqlTeamService(a)
                val initial = team.bootstrap()!!
                team.changePassword(team.login("admin", initial, "MCP").principal, initial, "mcp administrator password")
                val adminSession = team.login("admin", "mcp administrator password", "MCP")
                val admin = adminSession.principal
                suspend fun member(name: String): LoginSession {
                    val created = team.createMember(admin, name, Role.MEMBER)
                    team.changePassword(team.login(name, created.temporaryPassword, "MCP").principal, created.temporaryPassword, "$name replacement password")
                    return team.login(name, "$name replacement password", "MCP")
                }
                val alice = member("alice"); val bob = member("bob"); val carol = member("carol")
                val cipher = CredentialCipher(ByteArray(32) { 45 })
                // Administration goes through replica B; the gateway serves from replica A.
                val adminVariants = SqlVariantService(b, cipher); val adminAccess = SqlMcpAccessStore(b)
                val full = adminVariants.create(admin, NewVariant(VariantKind.MCP, "tracker", "full", VariantBinding.SHARED, "https://tracker.example.test/mcp", sharedSecret = SecretValue(trackerCredential)))
                val readOnly = adminVariants.create(admin, NewVariant(VariantKind.MCP, "tracker", "read-only", VariantBinding.SHARED, "https://tracker.example.test/mcp", sharedSecret = SecretValue(trackerCredential)))
                adminAccess.setToolPolicy(admin, readOnly.id, McpToolPolicy(McpToolMode.ALLOWLIST, setOf("search")))
                val wiki = adminVariants.create(admin, NewVariant(VariantKind.MCP, "wiki", "personal", VariantBinding.PER_MEMBER, "https://wiki.example.test/mcp"))
                adminVariants.assign(admin, alice.principal.member.id, full.id)
                adminVariants.assign(admin, alice.principal.member.id, wiki.id, SecretValue(wikiCredential))
                adminVariants.assign(admin, bob.principal.member.id, readOnly.id)
                val inferenceKey = SqlServiceKeyService(a).create(admin, bob.principal.member.id, "Inference only", setOf(ServiceScope.INFERENCE), 1)
                val mcpKey = SqlServiceKeyService(a).create(admin, bob.principal.member.id, "MCP agent", setOf(ServiceScope.MCP), 1)
                // The scopes of a cloud execution's `tcex_` credential: its owner's inference, MCP servers and skills, nothing else.
                val executionKey = SqlServiceKeyService(a).create(admin, bob.principal.member.id, "Execution", EXECUTION_SCOPES.toSet(), 1)

                val trackerCalls = ConcurrentLinkedQueue<String>(); val wikiCalls = ConcurrentLinkedQueue<String>()
                testApplication {
                    externalServices {
                        hosts("https://tracker.example.test") { routing { fakeMcp(trackerCredential, listOf("search", "delete_issue", "whoami"), sse = false, trackerCalls) } }
                        hosts("https://wiki.example.test") { routing { fakeMcp(wikiCredential, listOf("page"), sse = true, wikiCalls) } }
                    }
                    val upstreamClient = createClient { followRedirects = false }
                    val gateway = McpGateway(ResolveEffectiveVariantsUseCase(SqlVariantService(a, cipher)), SqlMcpAccessStore(a),
                        HttpMcpUpstream(upstreamClient, McpAddressPolicy { }), McpLimits())
                    val inference = object : GatewayService {
                        override suspend fun status() = GatewaySnapshot("test", 0, 0)
                        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("unused")
                    }
                    application { configureGateway(inference, team = team, publicOrigin = "http://localhost", variants = SqlVariantService(a, cipher), mcp = gateway, mcpAccess = SqlMcpAccessStore(a)) }
                    val bodies = mutableListOf<String>()
                    var nextId = 1
                    suspend fun rpc(token: String, server: String, method: String, params: JsonObject? = null): Pair<HttpStatusCode, JsonObject?> {
                        val response = client.post("/mcp/$server") {
                            bearerAuth(token); contentType(ContentType.Application.Json); accept(ContentType.Application.Json); accept(ContentType.Text.EventStream)
                            setBody(buildJsonObject { put("jsonrpc", "2.0"); put("id", nextId++); put("method", method); params?.let { put("params", it) } }.toString())
                        }
                        val text = response.bodyAsText().also { bodies += it }
                        return response.status to text.takeIf { it.isNotEmpty() }?.let { Json.parseToJsonElement(it).jsonObject }
                    }
                    suspend fun tools(token: String, server: String) = rpc(token, server, "tools/list").let { (status, body) ->
                        if (status == HttpStatusCode.NotFound) null else body!!["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
                    }
                    suspend fun servers(token: String) = Json.parseToJsonElement(client.get("/api/v1/mcp/servers") { bearerAuth(token) }.bodyAsText())
                        .jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["path"]!!.jsonPrimitive.content }
                    suspend fun invoke(token: String, server: String, name: String, arguments: JsonObject = buildJsonObject { put("q", "synthetic-argument") }) =
                        rpc(token, server, "tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
                    fun Pair<HttpStatusCode, JsonObject?>.text() = second!!["result"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
                    // An unknown tool on an assigned server, or any request to a server the member cannot use.
                    fun Pair<HttpStatusCode, JsonObject?>.denied() = second?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.int ==
                        if (first == HttpStatusCode.NotFound) -32600 else -32602

                    // Handshake and transport rules.
                    val (status, init) = rpc(alice.token, "tracker", "initialize", buildJsonObject { put("protocolVersion", "2025-06-18"); putJsonObject("capabilities") {}; putJsonObject("clientInfo") { put("name", "test"); put("version", "1") } })
                    assertEquals(HttpStatusCode.OK, status)
                    assertEquals("2025-06-18", init!!["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
                    assertTrue(rpc(carol.token, "tracker", "initialize").denied(), "An unassigned server cannot even be initialized")
                    assertTrue(rpc(alice.token, "unknown", "ping").denied())
                    assertEquals(HttpStatusCode.Accepted, client.post("/mcp/tracker") { bearerAuth(alice.token); contentType(ContentType.Application.Json); setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") }.status)
                    assertEquals(HttpStatusCode.NotFound, client.post("/mcp/tracker") { bearerAuth(carol.token); contentType(ContentType.Application.Json); setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") }.status, "Notifications are authorized too")
                    assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/mcp/tracker") { bearerAuth(alice.token) }.status)
                    assertEquals(HttpStatusCode.NotFound, client.post("/mcp") { bearerAuth(alice.token); contentType(ContentType.Application.Json); setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") }.status, "No aggregated endpoint")
                    assertEquals(HttpStatusCode.Unauthorized, client.post("/mcp/tracker") { contentType(ContentType.Application.Json); setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") }.status)
                    assertEquals(HttpStatusCode.Forbidden, client.post("/mcp/tracker") { bearerAuth(alice.token); header(HttpHeaders.Origin, "https://attacker.test"); contentType(ContentType.Application.Json); setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") }.status)
                    assertEquals(HttpStatusCode.Forbidden, client.post("/mcp/tracker") { bearerAuth(inferenceKey.accessToken); contentType(ContentType.Application.Json); setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") }.status)
                    assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/mcp/servers") { bearerAuth(inferenceKey.accessToken) }.status)

                    // Two members with different assignments see different servers and tools; a member without one sees nothing.
                    assertEquals(listOf("tracker" to "/mcp/tracker", "wiki" to "/mcp/wiki"), servers(alice.token))
                    assertEquals(listOf("tracker" to "/mcp/tracker"), servers(bob.token))
                    assertEquals(listOf("tracker" to "/mcp/tracker"), servers(mcpKey.accessToken), "An mcp-scoped key lists its owner's servers")
                    assertEquals(listOf("tracker" to "/mcp/tracker"), servers(executionKey.accessToken), "A cloud execution reaches its owner's servers")
                    assertEquals(HttpStatusCode.OK, client.get("/api/v1/skill-delivery") { bearerAuth(executionKey.accessToken) }.status, "and the owner's delivered skills")
                    for (path in listOf("/api/v1/mcp/usage", "/api/v1/skills")) assertEquals(HttpStatusCode.Forbidden, client.get(path) { bearerAuth(executionKey.accessToken) }.status, path)
                    assertEquals(emptyList(), servers(carol.token))
                    assertEquals(listOf("delete_issue", "search", "whoami"), tools(alice.token, "tracker"))
                    assertEquals(listOf("page"), tools(alice.token, "wiki"), "Each source keeps its own endpoint and upstream tool names")
                    assertEquals(listOf("search"), tools(bob.token, "tracker"))
                    assertEquals(listOf("search"), tools(mcpKey.accessToken, "tracker"), "A service key with the mcp scope acts for its owner")
                    assertNull(tools(bob.token, "wiki"))
                    assertNull(tools(carol.token, "tracker"))

                    // Each member invokes only their own tools; direct calls to anything else fail before reaching the upstream.
                    assertEquals("search({\"q\":\"synthetic-argument\"})", invoke(alice.token, "tracker", "search").text())
                    assertEquals("page({\"q\":\"synthetic-argument\"})", invoke(alice.token, "wiki", "page").text(), "Delegated credential over an SSE upstream session")
                    assertEquals("search({\"q\":\"synthetic-argument\"})", invoke(bob.token, "tracker", "search").text())
                    val before = trackerCalls.size to wikiCalls.size
                    for ((server, name) in listOf("tracker" to "delete_issue", "wiki" to "page", "tracker" to "unknown", "tracker" to "tracker__search", "unknown" to "search"))
                        assertTrue(invoke(bob.token, server, name).denied(), "$server/$name")
                    assertTrue(invoke(carol.token, "tracker", "search").denied())
                    assertEquals(before, trackerCalls.size to wikiCalls.size, "Unauthorized calls never reach an upstream")

                    // Revocation and policy changes made on the other replica apply to the very next call.
                    adminVariants.unassign(admin, bob.principal.member.id, VariantTarget(VariantKind.MCP, "tracker"))
                    assertTrue(invoke(bob.token, "tracker", "search").denied())
                    assertNull(tools(bob.token, "tracker"))
                    assertEquals(emptyList(), servers(bob.token))
                    adminAccess.setToolPolicy(admin, full.id, McpToolPolicy(McpToolMode.ALLOWLIST, setOf("search", "whoami")))
                    assertTrue(invoke(alice.token, "tracker", "delete_issue").denied())
                    adminVariants.exclude(admin, alice.principal.member.id, VariantTarget(VariantKind.MCP, "wiki"))
                    assertTrue(invoke(alice.token, "wiki", "page").denied())
                    assertEquals(listOf("search", "whoami"), tools(alice.token, "tracker"))
                    assertEquals(listOf("tracker" to "/mcp/tracker"), servers(alice.token))

                    // Upstream credentials never reach the workstation, even when an upstream echoes them.
                    assertEquals("you are Bearer [REDACTED]", invoke(alice.token, "tracker", "whoami").text())
                    team.updateMember(admin, alice.principal.member.id, Role.MEMBER, true)
                    assertEquals(HttpStatusCode.Unauthorized, rpc(alice.token, "tracker", "tools/list").first, "A disabled member's credential stops working at once")
                    for (body in bodies) for (secret in listOf(trackerCredential, wikiCredential)) assertFalse(body.contains(secret), body)

                    // Usage per member is visible; the journal holds metadata only.
                    val usage = client.get("/api/v1/mcp/usage?days=1") { bearerAuth(adminSession.token) }.bodyAsText()
                    assertTrue(usage.contains("\"username\":\"alice\"") && usage.contains("\"username\":\"bob\""), usage)
                    assertFalse(client.get("/api/v1/mcp/usage") { bearerAuth(bob.token) }.bodyAsText().contains("alice"))
                    assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/mcp/calls") { bearerAuth(bob.token) }.status)
                    val journal = client.get("/api/v1/mcp/calls") { bearerAuth(adminSession.token) }.bodyAsText()
                    assertTrue(journal.contains("\"outcome\":\"DENIED\"") && journal.contains("\"tool\":\"delete_issue\""), journal)
                    assertFalse(journal.contains("synthetic"), "No arguments, results or credentials in the journal")
                    assertTrue(client.get("/api/v1/capabilities") { bearerAuth(adminSession.token) }.bodyAsText().contains("mcp.gateway.v1"))
                    val dashboard = client.get("/dashboard/mcp") { header(HttpHeaders.Cookie, "teamcode_session=${adminSession.token}") }
                    assertEquals(HttpStatusCode.OK, dashboard.status)
                    assertFalse(dashboard.bodyAsText().contains(trackerCredential))
                }
            } }
        }
    }
}
}
