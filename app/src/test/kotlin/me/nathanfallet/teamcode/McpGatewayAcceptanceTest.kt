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
                    suspend fun rpc(token: String, method: String, params: JsonObject? = null): Pair<HttpStatusCode, JsonObject?> {
                        val response = client.post("/mcp") {
                            bearerAuth(token); contentType(ContentType.Application.Json); accept(ContentType.Application.Json); accept(ContentType.Text.EventStream)
                            setBody(buildJsonObject { put("jsonrpc", "2.0"); put("id", nextId++); put("method", method); params?.let { put("params", it) } }.toString())
                        }
                        val text = response.bodyAsText().also { bodies += it }
                        return response.status to text.takeIf { it.isNotEmpty() }?.let { Json.parseToJsonElement(it).jsonObject }
                    }
                    suspend fun tools(token: String) = rpc(token, "tools/list").second!!["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
                    suspend fun invoke(token: String, name: String, arguments: JsonObject = buildJsonObject { put("q", "synthetic-argument") }) =
                        rpc(token, "tools/call", buildJsonObject { put("name", name); put("arguments", arguments) }).second!!
                    fun JsonObject.text() = this["result"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
                    fun JsonObject.denied() = this["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int == -32602

                    // Handshake and transport rules.
                    val (status, init) = rpc(alice.token, "initialize", buildJsonObject { put("protocolVersion", "2025-06-18"); putJsonObject("capabilities") {}; putJsonObject("clientInfo") { put("name", "test"); put("version", "1") } })
                    assertEquals(HttpStatusCode.OK, status)
                    assertEquals("2025-06-18", init!!["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
                    assertEquals(HttpStatusCode.Accepted, client.post("/mcp") { bearerAuth(alice.token); contentType(ContentType.Application.Json); setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") }.status)
                    assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/mcp") { bearerAuth(alice.token) }.status)
                    assertEquals(HttpStatusCode.Unauthorized, client.post("/mcp") { contentType(ContentType.Application.Json); setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") }.status)
                    assertEquals(HttpStatusCode.Forbidden, client.post("/mcp") { bearerAuth(alice.token); header(HttpHeaders.Origin, "https://attacker.test"); contentType(ContentType.Application.Json); setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") }.status)
                    assertEquals(HttpStatusCode.Forbidden, client.post("/mcp") { bearerAuth(inferenceKey.accessToken); contentType(ContentType.Application.Json); setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") }.status)

                    // Two members with different assignments see different tools; a member without one sees nothing.
                    assertEquals(listOf("tracker__delete_issue", "tracker__search", "tracker__whoami", "wiki__page"), tools(alice.token))
                    assertEquals(listOf("tracker__search"), tools(bob.token))
                    assertEquals(listOf("tracker__search"), tools(mcpKey.accessToken), "A service key with the mcp scope acts for its owner")
                    assertEquals(emptyList(), tools(carol.token))

                    // Each member invokes only their own tools; direct calls to anything else fail before reaching the upstream.
                    assertEquals("search({\"q\":\"synthetic-argument\"})", invoke(alice.token, "tracker__search").text())
                    assertEquals("page({\"q\":\"synthetic-argument\"})", invoke(alice.token, "wiki__page").text(), "Delegated credential over an SSE upstream session")
                    assertEquals("search({\"q\":\"synthetic-argument\"})", invoke(bob.token, "tracker__search").text())
                    val before = trackerCalls.size to wikiCalls.size
                    for (name in listOf("tracker__delete_issue", "wiki__page", "tracker__unknown", "delete_issue")) assertTrue(invoke(bob.token, name).denied(), name)
                    assertTrue(invoke(carol.token, "tracker__search").denied())
                    assertEquals(before, trackerCalls.size to wikiCalls.size, "Unauthorized calls never reach an upstream")

                    // Revocation and policy changes made on the other replica apply to the very next call.
                    adminVariants.unassign(admin, bob.principal.member.id, VariantTarget(VariantKind.MCP, "tracker"))
                    assertTrue(invoke(bob.token, "tracker__search").denied())
                    assertEquals(emptyList(), tools(bob.token))
                    adminAccess.setToolPolicy(admin, full.id, McpToolPolicy(McpToolMode.ALLOWLIST, setOf("search", "whoami")))
                    assertTrue(invoke(alice.token, "tracker__delete_issue").denied())
                    adminVariants.exclude(admin, alice.principal.member.id, VariantTarget(VariantKind.MCP, "wiki"))
                    assertTrue(invoke(alice.token, "wiki__page").denied())
                    assertEquals(listOf("tracker__search", "tracker__whoami"), tools(alice.token))

                    // Upstream credentials never reach the workstation, even when an upstream echoes them.
                    assertEquals("you are Bearer [REDACTED]", invoke(alice.token, "tracker__whoami").text())
                    team.updateMember(admin, alice.principal.member.id, Role.MEMBER, true)
                    assertEquals(HttpStatusCode.Unauthorized, rpc(alice.token, "tools/list").first, "A disabled member's credential stops working at once")
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
