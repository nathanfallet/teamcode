package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.cli.cloud.agent.RunnerAgentTools
import me.nathanfallet.teamcode.cli.launch.*
import me.nathanfallet.teamcode.cli.remote.HostAgentTools
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*

/** How the workflow tool reaches an agent: the launch arguments, and which sessions get it. */
class WorkflowLaunchTest {
    private val token = "synthetic-local-token-long-enough-for-tests"
    private val run = "11111111-2222-4333-8444-555555555555"
    private val session = "00000000-0000-4000-8000-0000000000cc"
    private val workflow = WorkflowMcp("/opt/teamcode/teamcode", session)

    @Test fun claudeStartsTheWorkflowServerAsStdioWithTheTokenOnlyInItsEnvironment() {
        val launch = agentLaunch("claude", listOf("--", "prompt"), 4242, token, runId = run, workflow = workflow)
        val index = launch.arguments.indexOf("--mcp-config")
        val servers = Json.parseToJsonElement(launch.arguments[index + 1]).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(setOf("teamcode_workflow"), servers.keys)
        val server = servers["teamcode_workflow"]!!.jsonObject
        assertEquals("stdio", server["type"]!!.jsonPrimitive.content); assertEquals("/opt/teamcode/teamcode", server["command"]!!.jsonPrimitive.content)
        assertEquals(listOf("mcp", "workflow", "--session", session, "--run", run), server["args"]!!.jsonArray.map { it.jsonPrimitive.content })
        val env = server["env"]!!.jsonObject
        assertEquals("http://127.0.0.1:4242", env["TEAMCODE_WORKFLOW_URL"]!!.jsonPrimitive.content)
        assertEquals("TEAMCODE_WORKFLOW_TOKEN", env["TEAMCODE_WORKFLOW_TOKEN_VAR"]!!.jsonPrimitive.content)
        assertEquals("\${TEAMCODE_MCP_TOKEN}", env["TEAMCODE_WORKFLOW_TOKEN"]!!.jsonPrimitive.content)
        assertFalse(launch.arguments.any { it.contains(token) }, "The local token is on no command line")
        assertEquals(token, launch.environment["TEAMCODE_MCP_TOKEN"])
        assertEquals(WORKFLOW_INSTRUCTIONS, launch.arguments[launch.arguments.indexOf("--append-system-prompt") + 1])
        assertEquals(listOf("--", "prompt"), launch.arguments.takeLast(2))
    }

    @Test fun codexStartsTheWorkflowServerWithTheTokenVariableNamedNotGiven() {
        val launch = agentLaunch("codex", listOf("exec", "--", "prompt"), 4242, token, workflow = WorkflowMcp("C:\\Tools\\team\"code.exe", session))
        val overrides = launch.arguments.zipWithNext().filter { it.first == "-c" }.map { it.second }
        assertEquals("mcp_servers.teamcode_workflow={command=\"C:\\\\Tools\\\\team\\\"code.exe\",args=[\"mcp\",\"workflow\",\"--session\",\"$session\"]," +
            "env={TEAMCODE_WORKFLOW_URL=\"http://127.0.0.1:4242\",TEAMCODE_WORKFLOW_TOKEN_VAR=\"TEAMCODE_LOCAL_TOKEN\"},env_vars=[\"TEAMCODE_LOCAL_TOKEN\"]}",
            overrides.single { it.startsWith("mcp_servers.teamcode_workflow=") })
        assertEquals("developer_instructions=\"" + WORKFLOW_INSTRUCTIONS.replace("\"", "\\\"") + "\"", overrides.single { it.startsWith("developer_instructions=") })
        assertFalse(launch.arguments.any { it.contains(token) })
        assertEquals(listOf("--", "prompt"), launch.arguments.takeLast(2))
        assertFalse(agentLaunch("codex", listOf("exec", "-c", "developer_instructions=\"mine\""), 4242, token, workflow = workflow).arguments
            .any { it.startsWith("developer_instructions=") && "TeamCode" in it }, "The caller's own developer instructions are kept")
    }

    @Test fun withoutTheWorkflowServerNothingIsAdded() {
        val claude = agentLaunch("claude", listOf("-p", "hi"), 4242, token)
        assertFalse(claude.arguments.any { "teamcode_workflow" in it || "deliver" in it }); assertNull(claude.environment["TEAMCODE_MCP_TOKEN"])
        val codex = agentLaunch("codex", listOf("exec"), 4242, token)
        assertFalse(codex.arguments.any { "teamcode_workflow" in it || "deliver" in it })
    }

    @Test fun theInstructionsFollowTheWaitOnesAndSayWhenNotToDeliver() {
        val launch = agentLaunch("claude", listOf("-p", "hi"), 4242, token, waits = WaitsMcp("/opt/teamcode/teamcode", session), workflow = workflow, identity = "I am alice.")
        assertEquals("I am alice.\n\n$WAITS_INSTRUCTIONS\n\n$WORKFLOW_INSTRUCTIONS", launch.arguments[launch.arguments.indexOf("--append-system-prompt") + 1])
        assertEquals(setOf("teamcode_waits", "teamcode_workflow"), Json.parseToJsonElement(launch.arguments[launch.arguments.indexOf("--mcp-config") + 1]).jsonObject["mcpServers"]!!.jsonObject.keys)
        assertTrue("`deliver`" in WORKFLOW_INSTRUCTIONS && "`teamcode_workflow`" in WORKFLOW_INSTRUCTIONS && "once" in WORKFLOW_INSTRUCTIONS && "ordinary session" in WORKFLOW_INSTRUCTIONS)
        assertFailsWith<IllegalArgumentException> { WorkflowMcp("/opt/teamcode/teamcode", "../other") }
    }

    private fun client(features: List<String>, delivery: Pair<HttpStatusCode, String> = HttpStatusCode.BadRequest to """{"error":{"type":"invalid_body","message":"Invalid request body."}}""",
        seen: MutableList<String> = mutableListOf()) = TeamCodeClient("https://team.example", { "tcex_x" }) { configure ->
        HttpClient(MockEngine { request ->
            seen += "${request.method.value} ${request.url.encodedPath} ${(request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()}"
            val (status, body) = when (request.url.encodedPath) {
                "/api/v1/capabilities" -> HttpStatusCode.OK to buildJsonObject {
                    put("serverVersion", "test"); put("mode", "team"); putJsonArray("features") { features.forEach { add(it) } }
                }.toString()
                "/api/v1/workflows/session/deliver" -> delivery
                else -> HttpStatusCode.NotFound to "{}"
            }
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { configure(this) }
    }

    @Test fun aCloudSessionGetsTheWorkflowToolOnlyWhenItsCredentialMayDeliver() = runBlocking {
        fun tools(client: TeamCodeClient, binary: String? = "/opt/teamcode/teamcode", sessionId: String? = session) =
            RunnerAgentTools(client, { throw IllegalStateException("no container") }, binary = binary, sessionId = sessionId)
        val seen = mutableListOf<String>()
        val started = assertNotNull(tools(client(listOf("workflows.v1"), seen = seen)).workflow())
        assertEquals("/opt/teamcode/teamcode", started.command); assertEquals(session, started.sessionId)
        assertEquals(listOf("GET /api/v1/capabilities ", "POST /api/v1/workflows/session/deliver {}"), seen, "The capability, then an empty body that delivers nothing")
        assertNull(tools(client(emptyList())).workflow(), "A server without workflows.v1")
        assertNull(tools(client(listOf("workflows.v1"), HttpStatusCode.Forbidden to """{"error":{"type":"credential_scope","message":"No."}}""")).workflow(),
            "An execution credential from before the workflow:session scope")
        assertNull(tools(client(listOf("workflows.v1"), HttpStatusCode.NotFound to "{}")).workflow(), "An older server without the route")
        assertNull(tools(client(listOf("workflows.v1"), HttpStatusCode.ServiceUnavailable to "{}")).workflow(), "An unavailable server starts the agent without it")
        assertNull(tools(client(listOf("workflows.v1")), binary = null).workflow())
        assertNull(tools(client(listOf("workflows.v1")), sessionId = null).workflow(), "No session, no workflow tool")
    }

    @Test fun aRemoteSessionGetsTheWorkflowToolWhenTheServerServesWorkflows() = runBlocking {
        fun tools(features: List<String>, sessionId: String? = session) =
            HostAgentTools(client(features), "/work", features, null, {}, sessionId = sessionId, executable = { "/usr/local/bin/teamcode" })
        val started = assertNotNull(tools(listOf("workflows.v1")).workflow())
        assertEquals("/usr/local/bin/teamcode", started.command); assertEquals(session, started.sessionId)
        assertNull(tools(emptyList()).workflow(), "A server without workflows.v1")
        assertNull(tools(listOf("workflows.v1"), sessionId = null).workflow())
    }
}
