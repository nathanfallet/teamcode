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
import me.nathanfallet.teamcode.cli.todo.TodoDocs
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*

/** How the todo tools reach an agent: the launch arguments, and which sessions get them. */
class TodoLaunchTest {
    private val token = "synthetic-local-token-long-enough-for-tests"
    private val run = "11111111-2222-4333-8444-555555555555"
    private val session = "00000000-0000-4000-8000-0000000000cc"
    private val todo = TodoMcp("/opt/teamcode/teamcode", session)

    @Test fun claudeStartsTheTodoServerAsStdioWithTheTokenOnlyInItsEnvironment() {
        val launch = agentLaunch("claude", listOf("--", "prompt"), 4242, token, runId = run, todo = todo)
        val servers = Json.parseToJsonElement(launch.arguments[launch.arguments.indexOf("--mcp-config") + 1]).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(setOf("teamcode_todo"), servers.keys)
        val server = servers["teamcode_todo"]!!.jsonObject
        assertEquals("stdio", server["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("mcp", "todo", "--session", session, "--run", run), server["args"]!!.jsonArray.map { it.jsonPrimitive.content })
        val env = server["env"]!!.jsonObject
        assertEquals("http://127.0.0.1:4242", env["TEAMCODE_TODO_URL"]!!.jsonPrimitive.content)
        assertEquals("\${TEAMCODE_MCP_TOKEN}", env["TEAMCODE_TODO_TOKEN"]!!.jsonPrimitive.content)
        assertFalse(launch.arguments.any { it.contains(token) })
        assertEquals(token, launch.environment["TEAMCODE_MCP_TOKEN"])
        assertEquals(TodoDocs.PROMPT, launch.arguments[launch.arguments.indexOf("--append-system-prompt") + 1])
    }

    @Test fun codexStartsTheTodoServerWithTheTokenVariableNamedNotGiven() {
        val launch = agentLaunch("codex", listOf("exec", "--", "prompt"), 4242, token, todo = TodoMcp("C:\\Tools\\team\"code.exe", session))
        val overrides = launch.arguments.zipWithNext().filter { it.first == "-c" }.map { it.second }
        assertEquals("mcp_servers.teamcode_todo={command=\"C:\\\\Tools\\\\team\\\"code.exe\",args=[\"mcp\",\"todo\",\"--session\",\"$session\"]," +
            "env={TEAMCODE_TODO_URL=\"http://127.0.0.1:4242\",TEAMCODE_TODO_TOKEN_VAR=\"TEAMCODE_LOCAL_TOKEN\"},env_vars=[\"TEAMCODE_LOCAL_TOKEN\"]}",
            overrides.single { it.startsWith("mcp_servers.teamcode_todo=") })
        assertFalse(launch.arguments.any { it.contains(token) })
    }

    @Test fun withoutTheTodoServerNothingIsAdded() {
        assertFalse(agentLaunch("claude", listOf("-p", "hi"), 4242, token).arguments.any { "teamcode_todo" in it || "todo_create" in it })
        assertFalse(agentLaunch("codex", listOf("exec"), 4242, token).arguments.any { "teamcode_todo" in it })
        assertFailsWith<IllegalArgumentException> { TodoMcp("/opt/teamcode/teamcode", "../other") }
    }

    @Test fun thePromptSaysWhenToCreateTheListAndWhenAStopIsAllowed() {
        assertTrue("`todo_create`" in TodoDocs.PROMPT && "`teamcode_todo`" in TodoDocs.PROMPT && "cannot end a turn" in TodoDocs.PROMPT && "wait is open" in TodoDocs.PROMPT)
    }

    private fun client(features: List<String>, read: Pair<HttpStatusCode, String> = HttpStatusCode.OK to """{"todo":null}""", seen: MutableList<String> = mutableListOf()) =
        TeamCodeClient("https://team.example", { "tcex_x" }) { configure ->
            HttpClient(MockEngine { request ->
                seen += "${request.method.value} ${request.url.encodedPath}"
                val (status, body) = when (request.url.encodedPath) {
                    "/api/v1/capabilities" -> HttpStatusCode.OK to buildJsonObject { put("serverVersion", "test"); put("mode", "team"); putJsonArray("features") { features.forEach { add(it) } } }.toString()
                    "/api/v1/todos/session" -> read
                    else -> HttpStatusCode.NotFound to "{}"
                }
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }) { configure(this) }
        }

    @Test fun aCloudSessionGetsTheTodoToolsOnlyWhenItsCredentialMayKeepATodo() = runBlocking {
        fun tools(client: TeamCodeClient, binary: String? = "/opt/teamcode/teamcode", sessionId: String? = session) =
            RunnerAgentTools(client, { throw IllegalStateException("no container") }, binary = binary, sessionId = sessionId)
        val seen = mutableListOf<String>()
        val started = assertNotNull(tools(client(listOf("todos.v1"), seen = seen)).todo())
        assertEquals(session, started.sessionId)
        assertEquals(listOf("GET /api/v1/capabilities", "GET /api/v1/todos/session"), seen, "The capability, then a read that changes nothing")
        assertNull(tools(client(emptyList())).todo(), "A server without todos.v1")
        assertNull(tools(client(listOf("todos.v1"), HttpStatusCode.Forbidden to """{"error":{"type":"credential_scope","message":"No."}}""")).todo(), "A credential from before todo:session")
        assertNull(tools(client(listOf("todos.v1"), HttpStatusCode.ServiceUnavailable to "{}")).todo())
        assertNull(tools(client(listOf("todos.v1")), binary = null).todo())
        assertNull(tools(client(listOf("todos.v1")), sessionId = null).todo(), "No session, no todo tools")
    }

    @Test fun aRemoteSessionGetsTheTodoToolsWhenTheServerServesTodos() = runBlocking {
        fun tools(features: List<String>, sessionId: String? = session) =
            HostAgentTools(client(features), "/work", features, null, {}, sessionId = sessionId, executable = { "/usr/local/bin/teamcode" })
        assertEquals("/usr/local/bin/teamcode", assertNotNull(tools(listOf("todos.v1")).todo()).command)
        assertNull(tools(emptyList()).todo())
        assertNull(tools(listOf("todos.v1"), sessionId = null).todo())
    }
}
