package me.nathanfallet.teamcode

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.isExecutable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.mcp.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.variants.*
import me.nathanfallet.teamcode.infrastructure.database.*
import me.nathanfallet.teamcode.infrastructure.mcp.HostMcpUpstream
import me.nathanfallet.teamcode.infrastructure.mcp.HttpMcpUpstream
import me.nathanfallet.teamcode.infrastructure.mcp.TransportMcpUpstream
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.mcp.HostConfig
import me.nathanfallet.teamcode.mcp.Supervisor
import me.nathanfallet.teamcode.mcp.mcpHost
import me.nathanfallet.teamcode.presentation.configureGateway
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/**
 * Acceptance of hosted MCP servers (transport stdio) end to end: real MySQL, two replica pools, the HTTP gateway, and the
 * real MCP host (`:mcp`) on a loopback port, whose `npx` and `uvx` are its Node.js stdio fixture server, so every
 * operation goes through the production launch path, the host protocol and the stdio transport. No real network.
 */
class HostedMcpAcceptanceTest {
    private val hostToken = "synthetic-host-token-" + "h".repeat(32)
    private val sharedSecret = "synthetic-shared-fixture-secret"
    private val aliceSecret = "synthetic-alice-fixture-secret"
    private val bobSecret = "synthetic-bob-fixture-secret"
    private val rotatedSecret = "synthetic-rotated-fixture-secret"

    private val node: String by lazy {
        System.getenv("TEAMCODE_MCP_TEST_NODE")?.takeIf { it.isNotBlank() }
            ?: System.getenv("PATH").orEmpty().split(':').map { Path(it, "node") }.firstOrNull { it.isExecutable() }?.absolutePathString()
            ?: error("The hosted MCP acceptance test needs Node.js on PATH (or TEAMCODE_MCP_TEST_NODE).")
    }

    /** A real host on [port] (0: any), its processes under [root]. */
    private inner class RunningHost(root: Path, port: Int = 0) : AutoCloseable {
        val config = HostConfig(
            token = hostToken, host = "127.0.0.1", port = if (port == 0) 8090 else port, dataDir = root.resolve("data"),
            npx = root.resolve("bin/npx").absolutePathString(), uvx = root.resolve("bin/uvx").absolutePathString(),
            prlimit = "/usr/bin/prlimit".takeIf { Path(it).isExecutable() }, path = "${Path(node).parent}:/usr/bin:/bin",
            drainMinMillis = 0, tickMillis = 100, closeStdinWaitMillis = 500, terminateWaitMillis = 1_000, retainGraceMillis = 0,
        )
        val supervisor = Supervisor(config)
        private val server = embeddedServer(Netty, host = "127.0.0.1", port = port) { mcpHost(config, supervisor) }.start()
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        override fun close() {
            runBlocking { supervisor.shutdown() }
            server.stop(0, 2_000)
        }
    }

    @Test fun hostedServersAreDeclaredListedCalledAndRevokedThroughTheRealHost() { runBlocking {
        val root = Files.createTempDirectory("teamcode-hosted-mcp")
        val bin = Files.createDirectories(root.resolve("bin"))
        val fixture = Files.readString(Path(System.getProperty("teamcode.mcpFixture")))
        for (name in listOf("npx", "uvx")) bin.resolve(name).also { Files.writeString(it, fixture); it.toFile().setExecutable(true) }
        var host = RunningHost(root)
        val hostHttp = HttpClient(CIO) { followRedirects = false; install(HttpTimeout) { connectTimeoutMillis = 5_000 } }
        try {
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
                val cipher = CredentialCipher(ByteArray(32) { 47 })
                val url = "http://127.0.0.1:${host.port}"
                val upstreamA = HostMcpUpstream(hostHttp, url, hostToken)
                val upstreamB = HostMcpUpstream(hostHttp, url, hostToken)

                // Declaration asks the host which launchers it offers; administration goes through replica B.
                val adminVariants = SqlVariantService(b, cipher, { upstreamB.launchers() }); val adminAccess = SqlMcpAccessStore(b)
                val shared = adminVariants.create(admin, NewVariant(VariantKind.MCP, "everything", "team", VariantBinding.SHARED, null,
                    mapOf("FIXTURE_MODE" to "banner"), StdioEnvironment.encode(mapOf("FIXTURE_SECRET" to sharedSecret)), McpTransport.STDIO,
                    StdioCommand(StdioLauncher.NPM, "fixture@1.0.0", listOf("--token", "\${FIXTURE_SECRET}"))))
                val personal = adminVariants.create(admin, NewVariant(VariantKind.MCP, "personal", "own", VariantBinding.PER_MEMBER, null,
                    emptyMap(), null, McpTransport.STDIO, StdioCommand(StdioLauncher.PYPI, "fixture==1.0.0")))
                adminAccess.setToolPolicy(admin, shared.id, McpToolPolicy(McpToolMode.ALLOWLIST, setOf("echo", "env", "secret", "stats")))
                for (member in listOf(alice, bob)) adminVariants.assign(admin, member.principal.member.id, shared.id)
                adminVariants.assign(admin, alice.principal.member.id, personal.id, StdioEnvironment.encode(mapOf("FIXTURE_SECRET" to aliceSecret)))
                adminVariants.assign(admin, bob.principal.member.id, personal.id, StdioEnvironment.encode(mapOf("FIXTURE_SECRET" to bobSecret)))

                testApplication {
                    fun gateway(database: Database, host: HostMcpUpstream) = McpGateway(
                        ResolveEffectiveVariantsUseCase(SqlVariantService(database, cipher), hostedCommands = true), SqlMcpAccessStore(database),
                        TransportMcpUpstream(HttpMcpUpstream(createClient { followRedirects = false }), host), McpLimits(), hostedStates = SqlHostedProcessStateStore(database))
                    val gatewayA = gateway(a, upstreamA)
                    val gatewayB = gateway(b, upstreamB)
                    val inference = object : GatewayService {
                        override suspend fun status() = GatewaySnapshot("test", 0, 0)
                        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("unused")
                    }
                    application { configureGateway(inference, team = team, publicOrigin = "http://localhost", variants = SqlVariantService(a, cipher), mcp = gatewayA, mcpAccess = SqlMcpAccessStore(a),
                        hostedMcp = HostedMcpAdministration(SqlVariantService(a, cipher), SqlHostedProcessStateStore(a), upstreamA, SqlVariantService(a, cipher))) }
                    val bodies = mutableListOf<String>()
                    var nextId = 1
                    suspend fun rpc(token: String, server: String, method: String, params: JsonObject? = null): Pair<HttpStatusCode, JsonObject?> {
                        val response = client.post("/mcp/$server") {
                            bearerAuth(token); contentType(ContentType.Application.Json); accept(ContentType.Application.Json)
                            setBody(buildJsonObject { put("jsonrpc", "2.0"); put("id", nextId++); put("method", method); params?.let { put("params", it) } }.toString())
                        }
                        val text = response.bodyAsText().also { bodies += it }
                        return response.status to text.takeIf { it.isNotEmpty() }?.let { Json.parseToJsonElement(it).jsonObject }
                    }
                    suspend fun tools(token: String, server: String) = rpc(token, server, "tools/list").let { (status, body) ->
                        if (status == HttpStatusCode.NotFound) null else body!!["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
                    }
                    suspend fun servers(token: String) = Json.parseToJsonElement(client.get("/api/v1/mcp/servers") { bearerAuth(token) }.bodyAsText().also { bodies += it })
                        .jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
                    suspend fun invoke(token: String, server: String, name: String, arguments: JsonObject = buildJsonObject {}) =
                        rpc(token, server, "tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
                    fun Pair<HttpStatusCode, JsonObject?>.result() = second!!["result"]!!.jsonObject
                    fun Pair<HttpStatusCode, JsonObject?>.text() = result()["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
                    fun Pair<HttpStatusCode, JsonObject?>.stats() = Json.parseToJsonElement(text()).jsonObject
                    fun Pair<HttpStatusCode, JsonObject?>.denied() = second?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.int ==
                        if (first == HttpStatusCode.NotFound) -32600 else -32602
                    suspend fun eventually(what: String, condition: suspend () -> Boolean) {
                        val deadline = System.currentTimeMillis() + 20_000
                        while (!condition()) { check(System.currentTimeMillis() < deadline) { what }; delay(100) }
                    }

                    // The server announces hosted commands; clients see one more HTTP server per assignment, nothing else.
                    assertTrue(client.get("/api/v1/capabilities") { bearerAuth(adminSession.token) }.bodyAsText().contains("mcp.gateway.stdio.v1"))
                    assertEquals(listOf("everything", "personal"), servers(alice.token))
                    assertEquals(emptyList(), servers(carol.token))
                    // Reading the list warmed both of alice's processes in the background.
                    eventually("warm-up started alice's processes") { host.supervisor.status().processes.size == 2 }

                    // Listing and calling through replica A (HTTP) and replica B (the domain object), same processes.
                    assertEquals(listOf("echo", "env", "secret", "stats"), tools(alice.token, "everything"), "The variant's tool policy applies to a hosted server")
                    assertEquals(15, tools(alice.token, "personal")!!.size)
                    assertEquals(listOf("echo", "env", "secret", "stats"), gatewayB.listTools(bob.principal, "everything").map { it.name })
                    assertNull(tools(carol.token, "everything"))
                    assertEquals("hello", invoke(alice.token, "everything", "echo", buildJsonObject { put("text", "hello") }).text())
                    val aliceShared = invoke(alice.token, "everything", "stats").stats()
                    val bobShared = (gatewayB.callTool(bob.principal, "everything", "stats", null) as McpCallOutcome.Completed).result.result
                        .let { Json.parseToJsonElement(it).jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content }.let { Json.parseToJsonElement(it).jsonObject }
                    assertEquals(aliceShared["pid"], bobShared["pid"], "SHARED: one process for the team")
                    assertEquals(1, bobShared["initialize"]!!.jsonPrimitive.int, "initialize ran once for the process")
                    assertEquals(listOf("--yes", "--", "fixture@1.0.0", "--token", "[REDACTED]"), aliceShared["argv"]!!.jsonArray.map { it.jsonPrimitive.content },
                        "npx runs the pinned package; the host substituted the secret, which never comes back")
                    val alicePersonal = invoke(alice.token, "personal", "stats").stats()
                    val bobPersonal = invoke(bob.token, "personal", "stats").stats()
                    assertNotEquals(alicePersonal["pid"], bobPersonal["pid"], "PER_MEMBER: one process per member")
                    assertEquals("the secret is [REDACTED]", invoke(alice.token, "personal", "secret").text())
                    val environment = Json.parseToJsonElement(invoke(alice.token, "everything", "env").text()).jsonObject
                    assertEquals("[REDACTED]", environment["FIXTURE_SECRET"]!!.jsonPrimitive.content)
                    assertEquals("banner", environment["FIXTURE_MODE"]!!.jsonPrimitive.content)
                    assertTrue(environment.keys.none { it.startsWith("TEAMCODE_") || it.startsWith("JAVA") }, environment.keys.toString())
                    assertTrue(invoke(alice.token, "everything", "crash").denied(), "A tool outside the policy never reaches the process")
                    assertEquals(aliceShared["pid"], invoke(alice.token, "everything", "stats").stats()["pid"])
                    val failed = invoke(alice.token, "personal", "fail")
                    assertEquals(true, failed.result()["isError"]!!.jsonPrimitive.boolean)
                    assertTrue(failed.text().contains("[REDACTED]") && !failed.text().contains(aliceSecret), failed.text())

                    // Revocation on replica B applies to the next call at once; the retain sweep then stops the process.
                    val bobKey = StdioProcessKey.of("default", personal.id, personal.version, VariantBinding.PER_MEMBER, bob.principal.member.id,
                        adminVariants.assignments(admin, bob.principal.member.id).single().assignments.single { it.target.name == "personal" }.assignedAt)
                    assertTrue(host.supervisor.status().processes.any { it.key == bobKey })
                    adminVariants.unassign(admin, bob.principal.member.id, VariantTarget(VariantKind.MCP, "personal"))
                    assertTrue(invoke(bob.token, "personal", "echo").denied())
                    val retain = RetainHostedMcpProcessesUseCase(SqlVariantService(b, cipher), upstreamB)
                    assertEquals(1, (retain() as HostedMcpSweep.Done).stopped)
                    eventually("the retain sweep stopped bob's process") { host.supervisor.status().processes.none { it.key == bobKey } }
                    assertEquals(2, host.supervisor.status().processes.size, "alice's two processes stay")

                    // A rotated shared secret is a new version: a new process, and the sweep stops the old one.
                    val rotated = adminVariants.update(admin, shared.id, VariantUpdate(shared.version, "team", shared.config, StdioEnvironment.encode(mapOf("FIXTURE_SECRET" to rotatedSecret))))
                    val afterRotation = invoke(alice.token, "everything", "stats").stats()
                    assertNotEquals(aliceShared["pid"], afterRotation["pid"])
                    assertEquals(1, (retain() as HostedMcpSweep.Done).stopped)
                    assertEquals(0, (retain() as HostedMcpSweep.Done).stopped)
                    assertEquals(2, rotated.version)

                    // The host goes away: calls fail as tool errors, lists are empty; once it is back, the next list starts the process again.
                    host.close()
                    val down = invoke(alice.token, "everything", "echo", buildJsonObject { put("text", "x") })
                    assertEquals(true, down.result()["isError"]!!.jsonPrimitive.boolean)
                    assertEquals("The MCP host is unreachable.", down.text())
                    assertEquals(emptyList(), tools(alice.token, "everything"))
                    host = RunningHost(root, host.port)
                    assertEquals(listOf("echo", "env", "secret", "stats"), tools(alice.token, "everything"))
                    assertNotEquals(afterRotation["pid"], invoke(alice.token, "everything", "stats").stats()["pid"])

                    // State: a command that cannot start is recorded as failed by the sweep, with one mcp.stdio.failed event.
                    val broken = adminVariants.create(admin, NewVariant(VariantKind.MCP, "broken", "team", VariantBinding.SHARED, null,
                        mapOf("FIXTURE_MODE" to "exit-at-start"), StdioEnvironment.encode(mapOf("FIXTURE_SECRET" to sharedSecret)), McpTransport.STDIO,
                        StdioCommand(StdioLauncher.NPM, "fixture@1.0.0", listOf("--token", "\${FIXTURE_SECRET}"))))
                    adminVariants.assign(admin, alice.principal.member.id, broken.id)
                    assertEquals(emptyList(), tools(alice.token, "broken"), "A command that does not start lists no tool")
                    val sweep = RetainHostedMcpProcessesUseCase(SqlVariantService(b, cipher), upstreamB, SqlHostedProcessStateStore(b))
                    assertTrue((sweep() as HostedMcpSweep.Done).changed >= 2)
                    val listed = Json.parseToJsonElement(client.get("/api/v1/mcp/servers") { bearerAuth(alice.token) }.bodyAsText().also { bodies += it }).jsonArray
                        .associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject }
                    assertEquals(listOf("stdio", "failed", "start_failed"), listOf("transport", "state", "failure").map { listed.getValue("broken")[it]!!.jsonPrimitive.content })
                    assertEquals("ready", listed.getValue("everything")["state"]!!.jsonPrimitive.content)
                    val events = SqlTeamEventStore(a).read("default", TeamEventAudience(admin.member.id, true, true, true, true), 0, setOf(TeamEventType.MCP_STDIO_FAILED), 100).events
                    assertEquals(listOf(broken.id), events.map { it.subjectId })
                    assertTrue(events.single().data.contains("\"server\":\"broken\"") && events.single().data.contains("start_failed"), events.single().data)
                    // Reading the list warmed processes meanwhile; once they settle, a sweep writes nothing and the failure is not reported twice.
                    eventually("the states settled") { (sweep() as HostedMcpSweep.Done).changed == 0 }
                    assertEquals(1, SqlTeamEventStore(a).read("default", TeamEventAudience(admin.member.id, true, true, true, true), 0, setOf(TeamEventType.MCP_STDIO_FAILED), 100).events.size)
                    // The dashboard shows each hosted process, its state and stderr, and restarts one.
                    val page = client.get("/dashboard/mcp") { header(HttpHeaders.Cookie, "teamcode_session=${adminSession.token}") }.bodyAsText().also { bodies += it }
                    assertTrue(page.contains("Hosted commands") && page.contains("start_failed") && page.contains("Restart") && page.contains("shared by the team"), page)
                    assertTrue(page.contains("member <strong>alice</strong>"), "A PER_MEMBER process names its member")
                    assertEquals(HttpStatusCode.Forbidden, client.get("/dashboard/mcp") { header(HttpHeaders.Cookie, "teamcode_session=${alice.token}") }.status)
                    val restart = client.post("/dashboard/mcp/hosted/restart") {
                        header(HttpHeaders.Cookie, "teamcode_session=${adminSession.token}"); header(HttpHeaders.Origin, "http://localhost")
                        contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=${admin.tokenId}&variantId=${broken.id}")
                    }
                    assertEquals(HttpStatusCode.Found, restart.status)
                    assertEquals("idle", host.supervisor.status().processes.single { it.key == StdioProcessKey.of("default", broken.id, broken.version, VariantBinding.SHARED, "", 0) }.state,
                        "The restart lifted the failed start")

                    // No secret in any answer; the journal records the outcomes, metadata only.
                    for (body in bodies) for (secret in listOf(sharedSecret, aliceSecret, bobSecret, rotatedSecret, hostToken)) assertFalse(body.contains(secret), body)
                    val journal = client.get("/api/v1/mcp/calls") { bearerAuth(adminSession.token) }.bodyAsText()
                    assertTrue(journal.contains("\"outcome\":\"UPSTREAM_ERROR\"") && journal.contains("\"outcome\":\"OK\"") && journal.contains("\"outcome\":\"TOOL_ERROR\""), journal)
                    assertFalse(journal.contains("synthetic"))
                    val dashboard = client.get("/dashboard/variants") { header(HttpHeaders.Cookie, "teamcode_session=${adminSession.token}") }.bodyAsText()
                    assertTrue(dashboard.contains("one process shared by every assigned member"), "The dashboard says a SHARED command's state is shared")
                }
            } }
        }
        } finally {
            host.close()
            hostHttp.close()
            root.toFile().deleteRecursively()
        }
    } }
}
