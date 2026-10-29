package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.cli.cloud.agent.RunnerAgentTools
import me.nathanfallet.teamcode.cli.launch.ARTIFACTS_INSTRUCTIONS
import me.nathanfallet.teamcode.cli.launch.ArtifactsMcp
import me.nathanfallet.teamcode.cli.launch.WAITS_INSTRUCTIONS
import me.nathanfallet.teamcode.cli.launch.WAIT_ANSWERED_PREFIX
import me.nathanfallet.teamcode.cli.launch.WaitsMcp
import me.nathanfallet.teamcode.cli.launch.agentLaunch
import me.nathanfallet.teamcode.cli.remote.HostAgentTools
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*

/** How the wait tools reach an agent: the launch arguments, and which sessions get them. */
class WaitsLaunchTest {
    private val token = "synthetic-local-token-long-enough-for-tests"
    private val run = "11111111-2222-4333-8444-555555555555"
    private val session = "00000000-0000-4000-8000-0000000000cc"
    private val waits = WaitsMcp("/opt/teamcode/teamcode", session)

    @Test fun claudeStartsTheWaitServerAsStdioWithTheTokenOnlyInItsEnvironment() {
        val launch = agentLaunch("claude", listOf("--", "prompt"), 4242, token, runId = run, waits = waits)
        val index = launch.arguments.indexOf("--mcp-config")
        val servers = Json.parseToJsonElement(launch.arguments[index + 1]).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(setOf("teamcode_waits"), servers.keys)
        val server = servers["teamcode_waits"]!!.jsonObject
        assertEquals("stdio", server["type"]!!.jsonPrimitive.content); assertEquals("/opt/teamcode/teamcode", server["command"]!!.jsonPrimitive.content)
        assertEquals(listOf("mcp", "waits", "--session", session, "--run", run), server["args"]!!.jsonArray.map { it.jsonPrimitive.content })
        val env = server["env"]!!.jsonObject
        assertEquals("http://127.0.0.1:4242", env["TEAMCODE_WAITS_URL"]!!.jsonPrimitive.content)
        assertEquals("TEAMCODE_WAITS_TOKEN", env["TEAMCODE_WAITS_TOKEN_VAR"]!!.jsonPrimitive.content)
        assertEquals("\${TEAMCODE_MCP_TOKEN}", env["TEAMCODE_WAITS_TOKEN"]!!.jsonPrimitive.content)
        assertFalse(launch.arguments.any { it.contains(token) }, "The local token is on no command line")
        assertEquals(token, launch.environment["TEAMCODE_MCP_TOKEN"])
        assertEquals(WAITS_INSTRUCTIONS, launch.arguments[launch.arguments.indexOf("--append-system-prompt") + 1])
        assertFalse(launch.arguments.any { it.startsWith("--disallowedTools") }, "Waits alone do not deny Claude Code's artifact tools")
        assertEquals(listOf("--", "prompt"), launch.arguments.takeLast(2))
    }

    @Test fun codexStartsTheWaitServerWithTheTokenVariableNamedNotGiven() {
        val launch = agentLaunch("codex", listOf("exec", "--", "prompt"), 4242, token, waits = WaitsMcp("C:\\Tools\\team\"code.exe", session))
        val overrides = launch.arguments.zipWithNext().filter { it.first == "-c" }.map { it.second }
        assertEquals("mcp_servers.teamcode_waits={command=\"C:\\\\Tools\\\\team\\\"code.exe\",args=[\"mcp\",\"waits\",\"--session\",\"$session\"]," +
            "env={TEAMCODE_WAITS_URL=\"http://127.0.0.1:4242\",TEAMCODE_WAITS_TOKEN_VAR=\"TEAMCODE_LOCAL_TOKEN\"},env_vars=[\"TEAMCODE_LOCAL_TOKEN\"]}",
            overrides.single { it.startsWith("mcp_servers.teamcode_waits=") })
        assertEquals("developer_instructions=\"" + WAITS_INSTRUCTIONS.replace("\"", "\\\"") + "\"", overrides.single { it.startsWith("developer_instructions=") })
        assertFalse(launch.arguments.any { it.contains(token) })
        assertEquals(listOf("--", "prompt"), launch.arguments.takeLast(2))
        assertFalse(agentLaunch("codex", listOf("exec", "-c", "developer_instructions=\"mine\""), 4242, token, waits = waits).arguments
            .any { it.startsWith("developer_instructions=") && "TeamCode" in it }, "The caller's own developer instructions are kept")
    }

    @Test fun withoutTheWaitServerNothingIsAdded() {
        val claude = agentLaunch("claude", listOf("-p", "hi"), 4242, token)
        assertFalse(claude.arguments.any { "teamcode_waits" in it || "ask_human" in it }); assertNull(claude.environment["TEAMCODE_MCP_TOKEN"])
        val codex = agentLaunch("codex", listOf("exec"), 4242, token)
        assertFalse(codex.arguments.any { "teamcode_waits" in it || "ask_human" in it })
    }

    @Test fun theInstructionsFollowTheArtifactOnesAndNameTheAnswerMessage() {
        val launch = agentLaunch("claude", listOf("--append-system-prompt=Be brief.", "-p", "hi"), 4242, token,
            artifacts = ArtifactsMcp("/opt/teamcode/teamcode", "/work"), waits = waits, identity = "I am alice.")
        assertEquals("Be brief.\n\nI am alice.\n\n$ARTIFACTS_INSTRUCTIONS\n\n$WAITS_INSTRUCTIONS", launch.arguments[launch.arguments.indexOf("--append-system-prompt") + 1])
        assertEquals(1, launch.arguments.count { it == "--append-system-prompt" })
        assertTrue("`ask_human`" in WAITS_INSTRUCTIONS && "`teamcode_waits`" in WAITS_INSTRUCTIONS && WAIT_ANSWERED_PREFIX in WAITS_INSTRUCTIONS)
        assertEquals("[TeamCode wait answered]", WAIT_ANSWERED_PREFIX)
        assertFailsWith<IllegalArgumentException> { WaitsMcp("/opt/teamcode/teamcode", "../other") }
    }

    private fun client(features: List<String>, waits: Pair<HttpStatusCode, String> = HttpStatusCode.OK to """{"waits":[]}""", seen: MutableList<String> = mutableListOf()) =
        TeamCodeClient("https://team.example", { "tcex_x" }) { configure ->
            HttpClient(MockEngine { request ->
                seen += request.url.encodedPath
                val (status, body) = when (request.url.encodedPath) {
                    "/api/v1/capabilities" -> HttpStatusCode.OK to buildJsonObject {
                        put("serverVersion", "test"); put("mode", "team"); putJsonArray("features") { features.forEach { add(it) } }
                    }.toString()
                    "/api/v1/waits" -> waits
                    else -> HttpStatusCode.NotFound to "{}"
                }
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }) { configure(this) }
        }

    @Test fun aCloudSessionGetsTheWaitToolsOnlyWhenItsCredentialMayUseThem() = runBlocking {
        val logs = mutableListOf<String>()
        fun tools(client: TeamCodeClient, binary: String? = "/opt/teamcode/teamcode", sessionId: String? = session) =
            RunnerAgentTools(client, { throw IllegalStateException("no container") }, log = { logs += it }, binary = binary, sessionId = sessionId)
        val seen = mutableListOf<String>()
        val started = assertNotNull(tools(client(listOf("waits.v1"), seen = seen)).waits())
        assertEquals("/opt/teamcode/teamcode", started.command); assertEquals(session, started.sessionId)
        assertEquals(listOf("/api/v1/capabilities", "/api/v1/waits"), seen, "The capability, then one bounded read with the execution's credential")
        assertNull(tools(client(emptyList())).waits(), "A server without waits.v1")
        assertNull(tools(client(listOf("waits.v1"), HttpStatusCode.Forbidden to """{"error":{"type":"credential_scope","message":"No."}}""")).waits(),
            "An execution credential without the cloud rights waits need")
        assertTrue(logs.any { "cannot ask people" in it }, logs.toString())
        assertNull(tools(client(listOf("waits.v1"), HttpStatusCode.ServiceUnavailable to "{}")).waits(), "An unavailable server starts the agent without them")
        assertNull(tools(client(listOf("waits.v1")), binary = null).waits())
        assertNull(tools(client(listOf("waits.v1")), sessionId = null).waits(), "No session, no waits")
    }

    @Test fun aRemoteSessionGetsTheWaitToolsWhenTheServerServesWaits() = runBlocking {
        fun tools(features: List<String>, sessionId: String? = session) =
            HostAgentTools(client(features), "/work", features, null, {}, sessionId = sessionId, executable = { "/usr/local/bin/teamcode" })
        val started = assertNotNull(tools(listOf("waits.v1")).waits())
        assertEquals("/usr/local/bin/teamcode", started.command); assertEquals(session, started.sessionId)
        assertNull(tools(emptyList()).waits(), "A server without waits.v1")
        assertNull(tools(listOf("waits.v1"), sessionId = null).waits())
    }
}
