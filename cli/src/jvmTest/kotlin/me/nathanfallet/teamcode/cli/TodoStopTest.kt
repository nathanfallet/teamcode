package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.cloud.agent.cloudHookSettings
import me.nathanfallet.teamcode.cli.todo.*
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** The Stop hook: what it asks the server, what it runs on the agent's machine, and when it fails open. */
class TodoStopTest {
    private val session = "00000000-0000-4000-8000-0000000000cc"
    private val description = "Do this item completely: what, where, why and what is out of scope, enough for a new agent to do it alone."
    private val stop = """{"hook_event_name":"Stop","stop_hook_active":true,"last_assistant_message":"I am done for now"}"""

    private fun item(id: String, status: String, vararg checks: TodoCheckView, links: List<TodoLinkView> = emptyList()) =
        TodoItemView(id, "Item $id", description, TodoDoneWhenView("done", checks.toList()), emptyList(), "plan", null, status, null, emptyList(), links = links)
    private fun view(vararg items: TodoItemView) = TodoView("7f3a9c10-aaaa-4bbb-8ccc-000000000001", session, "The plan", emptyList(), "active", 0, items.toList(), 1, 2)
    private val merged = TodoCheckView("pr_merged", repo = "acme/x")
    private val command = TodoCheckView("command", run = "make verify")

    private class Api(val answers: MutableList<TodoStopView>) : TodoStopApi {
        val requests = mutableListOf<TodoStopRequest>()
        val links = mutableListOf<Pair<String, LinkTodoItemRequest>>()
        val errors = mutableListOf<String>()
        var fail: Exception? = null
        override suspend fun stop(request: TodoStopRequest): TodoStopView { fail?.let { throw it }; requests += request; return answers.removeAt(0) }
        override suspend fun link(itemId: String, link: LinkTodoItemRequest) { links += itemId to link }
        override suspend fun hookError(text: String) { errors += text }
    }
    private val ran = mutableListOf<List<String>>()
    private val checks = TodoChecks({ cmd, arguments, _, _ ->
        ran += listOf(cmd) + arguments
        when {
            cmd == "gh" && arguments.firstOrNull() == "pr" -> Captured(0, """[{"number":9,"state":"MERGED","url":"https://github.com/acme/x/pull/9","author":{"login":"ada"},"body":"<!-- teamcode-todo: 7f3a9c10/a -->","mergeCommit":{"oid":"abc"}}]""")
            cmd == "gh" && arguments.firstOrNull() == "api" -> Captured(0, "ada")
            else -> Captured(0, "ok")
        }
    }, session)

    @Test fun aStopThatIsNotAStopOrNotJsonIsLeftAlone() = runBlocking {
        val api = Api(mutableListOf())
        assertNull(TodoStop.decide("not json", false, api, checks))
        assertNull(TodoStop.decide("""{"hook_event_name":"SubagentStop"}""", false, api, checks))
        assertEquals(emptyList(), api.requests)
    }

    @Test fun aStopWithNothingToHoldBackAsksOnceAndPrintsNothing() = runBlocking {
        val api = Api(mutableListOf(TodoStopView("allow", why = "wait_open")))
        // stop_hook_active is true here: the hook never relaxes on it, the server decides.
        assertNull(TodoStop.decide(stop, false, api, checks))
        assertEquals(emptyList(), api.errors, "letting the stop pass is not a failure")
        assertEquals(1, api.requests.size)
        assertEquals("begin", api.requests.single().phase)
        assertEquals(emptyList(), ran)
    }

    @Test fun backgroundWorkIsReportedToTheServer() = runBlocking {
        val api = Api(mutableListOf(TodoStopView("allow", why = "background_task")))
        TodoStop.decide("""{"hook_event_name":"Stop","background_tasks":[{"id":"t1"}]}""", false, api, checks)
        assertTrue(api.requests.single().background)
    }

    @Test fun itRunsTheCheapChecksAndTheDoingItemButNeverACommandOfATodoItemThenBlocksWithTheServersMessage() = runBlocking {
        val list = view(item("a", "doing", merged), item("b", "todo", merged), item("c", "todo", command), item("d", "done", merged))
        val api = Api(mutableListOf(TodoStopView("verify", list), TodoStopView("block", list, reason = "[TeamCode todo] 1 of 4 items done.")))
        val out = TodoStop.decide(stop, false, api, checks)
        assertEquals("""{"decision":"block","reason":"[TeamCode todo] 1 of 4 items done."}""", out)
        val verify = api.requests[1]
        assertEquals("verify", verify.phase)
        assertEquals(listOf("a", "b"), verify.items.map { it.itemId })
        assertEquals(true, verify.items.first().checks.single().passed)
        assertEquals("I am done for now", verify.lastMessage)
        assertTrue(ran.none { it.contains("make verify") }, "a command is not run for an item that is not in progress")
        assertEquals(listOf("a"), api.links.map { it.first }, "the PR found by its marker is bound to its item, and only to it")
    }

    @Test fun theDoingItemRunsItsCommandToo() = runBlocking {
        val api = Api(mutableListOf(TodoStopView("verify", view(item("a", "doing", command))), TodoStopView("allow")))
        TodoStop.decide(stop, false, api, checks)
        assertTrue(ran.any { it.contains("make verify") })
    }

    @Test fun inObservationItNeverPrintsABlockButTellsTheServer() = runBlocking {
        val list = view(item("a", "doing", merged))
        val api = Api(mutableListOf(TodoStopView("verify", list), TodoStopView("allow", list, why = "observation", wouldBlock = true)))
        assertNull(TodoStop.decide(stop, true, api, checks))
        assertEquals(emptyList(), api.errors)
        assertTrue(api.requests.all { it.observe })
        // Even if an older or odd server answered block, observation prints nothing.
        val odd = Api(mutableListOf(TodoStopView("verify", list), TodoStopView("block", list, reason = "x")))
        assertNull(TodoStop.decide(stop, true, odd, checks))
    }

    @Test fun whenEverythingIsClosedItRunsEveryCheckOfTheDoneItemsAndSettles() = runBlocking {
        val list = view(item("a", "done", merged, command), item("b", "done", merged))
        val api = Api(mutableListOf(TodoStopView("verify", list), TodoStopView("settle", list), TodoStopView("allow", list, why = "settled")))
        assertNull(TodoStop.decide(stop, false, api, checks))
        assertEquals(emptyList(), api.errors)
        assertEquals(listOf("begin", "verify", "settle"), api.requests.map { it.phase })
        assertEquals(listOf("a", "b"), api.requests[2].items.map { it.itemId })
        assertEquals(2, api.requests[2].items.first().checks.size)
        assertTrue(ran.any { it.contains("make verify") }, "the final check includes commands")
    }

    @Test fun aFailureLetsTheStopPassAndIsRecorded() = runBlocking {
        val api = Api(mutableListOf()).apply { fail = IllegalStateException("boom") }
        assertNull(TodoStop.decide(stop, false, api, checks))
        assertEquals(listOf("The stop hook failed (IllegalStateException)."), api.errors)
        // An empty script (the server answered nothing usable) is also a failure, not a block.
        val empty = Api(mutableListOf())
        assertNull(TodoStop.decide(stop, false, empty, checks))
        assertEquals(1, empty.errors.size)
    }

    @Test fun theSessionsPullRequestsAreReadFromItsTranscript() {
        val directory = createTempDirectory("transcript").toString().toPath()
        val file = directory / "t.jsonl"
        fun tool(id: String, command: String) = """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"$id","name":"Bash","input":{"command":${JsonPrimitive(command)}}}]}}"""
        fun result(id: String, text: String, error: Boolean = false) = """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"$id","is_error":$error,"content":${JsonPrimitive(text)}}]}}"""
        FileSystem.SYSTEM.write(file) {
            writeUtf8(listOf(
                tool("1", "gh pr create --title x"), result("1", "https://github.com/acme/x/pull/7\n"),
                tool("2", "gh pr create --title y"), result("2", "failed: https://github.com/acme/x/pull/8", error = true),
                tool("3", "gh pr view 5"), result("3", "https://github.com/acme/x/pull/5"),
                tool("4", "gh pr create"), result("4", "https://github.com/acme/y/pull/12"),
                "not json",
            ).joinToString("\n"))
        }
        try {
            assertEquals(listOf("https://github.com/acme/x/pull/7", "https://github.com/acme/y/pull/12"), TodoStop.pullRequestsOf(file.toString()))
            assertEquals(emptyList(), TodoStop.pullRequestsOf((directory / "missing").toString()))
        } finally { FileSystem.SYSTEM.deleteRecursively(directory) }
    }

    @Test fun theHookIsInstalledOnStopWithItsOwnTimeoutAndObservationByDefault() {
        val command = TodoStop.hookCommand("/opt/teamcode/teamcode", 4711, session, null)
        assertEquals("'/opt/teamcode/teamcode' cloud todo-stop --relay http://127.0.0.1:4711 --session $session --mode observe", command)
        assertTrue(TodoStop.hookCommand("/t", 1, session, null, "block").endsWith("--mode block"))
        assertEquals("observe", TodoStop.modeOf(null)); assertEquals("observe", TodoStop.modeOf("nonsense")); assertEquals("block", TodoStop.modeOf(" Block "))
        assertFailsWith<IllegalArgumentException> { TodoStop.hookCommand("/t", 1, session, null, "off") }
        val stopHooks = Json.parseToJsonElement(assertNotNull(cloudHookSettings(emptyList(), todoStop = command))).jsonObject["hooks"]!!.jsonObject
        assertEquals(setOf("Stop"), stopHooks.keys)
        val entry = stopHooks["Stop"]!!.jsonArray.single().jsonObject["hooks"]!!.jsonArray.single().jsonObject
        assertEquals(command, entry["command"]!!.jsonPrimitive.content)
        assertEquals(150, entry["timeout"]!!.jsonPrimitive.int)
    }
}

/** The `SessionStart` reminder: only after a compaction or on resume, only with an active list, never in the way of a start. */
class TodoContextHookTest {
    private val session = "00000000-0000-4000-8000-0000000000cc"
    private fun start(source: String?) = """{"hook_event_name":"SessionStart"${source?.let { ""","source":"$it"""" } ?: ""}}"""

    @Test fun compactionAndResumeGetTheBlockAsAdditionalContext() = kotlinx.coroutines.runBlocking {
        for (source in listOf("compact", "resume")) {
            val out = Json.parseToJsonElement(assertNotNull(TodoStop.contextOutput(start(source)) { "[TeamCode todo] Your todo: 2 of 4 items done." })).jsonObject["hookSpecificOutput"]!!.jsonObject
            assertEquals("SessionStart", out["hookEventName"]!!.jsonPrimitive.content)
            assertEquals("[TeamCode todo] Your todo: 2 of 4 items done.", out["additionalContext"]!!.jsonPrimitive.content)
        }
    }

    @Test fun anotherStartAnEmptyListOrAFailureSaysNothing() = kotlinx.coroutines.runBlocking {
        var asked = 0
        for (source in listOf("startup", "clear", null)) assertNull(TodoStop.contextOutput(start(source)) { asked++; "block" })
        assertEquals(0, asked, "the server is not even asked at a fresh start")
        assertNull(TodoStop.contextOutput(start("compact")) { null })
        assertNull(TodoStop.contextOutput(start("compact")) { "  " })
        assertNull(TodoStop.contextOutput(start("compact")) { throw IllegalStateException("relay down") })
        assertNull(TodoStop.contextOutput("""{"hook_event_name":"Stop","source":"compact"}""") { "block" })
        assertNull(TodoStop.contextOutput("not json") { "block" })
    }

    @Test fun theHookIsInstalledOnSessionStartForCompactAndResumeOnly() {
        val command = TodoStop.contextCommand("/opt/teamcode/teamcode", 4711, session, null)
        assertEquals("'/opt/teamcode/teamcode' cloud todo-context --relay http://127.0.0.1:4711 --session $session", command)
        val stop = TodoStop.hookCommand("/opt/teamcode/teamcode", 4711, session, null)
        val hooks = Json.parseToJsonElement(assertNotNull(cloudHookSettings(emptyList(), todoStop = stop, todoContext = command))).jsonObject["hooks"]!!.jsonObject
        assertEquals(setOf("SessionStart", "Stop"), hooks.keys)
        val entry = hooks["SessionStart"]!!.jsonArray.single().jsonObject
        assertEquals("compact|resume", entry["matcher"]!!.jsonPrimitive.content)
        assertEquals(command, entry["hooks"]!!.jsonArray.single().jsonObject["command"]!!.jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { TodoStop.contextCommand("/t", 0, session, null) }
    }
}
