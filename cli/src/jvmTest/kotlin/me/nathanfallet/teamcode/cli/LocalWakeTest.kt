package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.cloud.agent.LOCAL_STOP_TIMEOUT_SECONDS
import me.nathanfallet.teamcode.cli.cloud.agent.cloudHookSettings
import me.nathanfallet.teamcode.cli.launch.*
import me.nathanfallet.teamcode.cli.todo.*
import me.nathanfallet.teamcode.client.TeamCodeException
import kotlin.test.*

/** The Stop hook of a local run: it holds the agent while a wait is open, and gives it the answer. */
class LocalWakeTest {
    private val session = "00000000-0000-4000-8000-0000000000dd"
    private val answered = "[TeamCode wait answered]\nwait: w1\naction: MySQL (mysql)\nanswered by: ada"

    private class Wake(val answers: MutableList<Result<TodoWakeView>>) : TodoWakeApi {
        var calls = 0
        val requests = mutableListOf<TodoWakeRequest>()
        override suspend fun wake(request: TodoWakeRequest): TodoWakeView { calls++; requests += request; return answers.removeAt(0).getOrThrow() }
    }
    private class Clock { var at = 0L; val now: () -> Long = { at }; val sleep: suspend (Long) -> Unit = { at += it } }

    @Test fun anAnswerBlocksTheStopWithTheMessageAsTheReason() = runBlocking {
        val api = Wake(mutableListOf(Result.success(TodoWakeView(true)), Result.success(TodoWakeView(true, listOf(answered)))))
        val clock = Clock()
        val out = TodoStop.hold(api, 120_000, clock.sleep) { clock.at += 20_000; clock.at }
        val json = Json.parseToJsonElement(assertNotNull(out)).jsonObject
        assertEquals("block", json["decision"]!!.jsonPrimitive.content)
        assertEquals(answered, json["reason"]!!.jsonPrimitive.content)
        assertEquals(2, api.calls)
        assertEquals(null, api.requests.first().sessionId, "The credential names the session")
    }

    @Test fun noOpenWaitLetsTheStopStand() = runBlocking {
        val api = Wake(mutableListOf(Result.success(TodoWakeView(false))))
        assertNull(TodoStop.hold(api, 120_000))
        assertEquals(1, api.calls)
    }

    @Test fun theHoldEndsWhenItsBudgetIsSpentAndTheAnswerWaitsForTheResume() = runBlocking {
        val api = Wake(MutableList(10) { Result.success(TodoWakeView(true)) })
        val clock = Clock()
        assertNull(TodoStop.hold(api, 50_000, clock.sleep) { clock.at += 20_000; clock.at })
        assertTrue(api.calls in 2..3, "bounded: ${api.calls}")
    }

    @Test fun anUnreachableServerLetsTheAgentStopAfterFewTries() = runBlocking {
        val api = Wake(MutableList(10) { Result.failure(java.io.IOException("down")) })
        val clock = Clock()
        assertNull(TodoStop.hold(api, 24 * 3_600_000L, clock.sleep, clock.now))
        assertEquals(6, api.calls)
    }

    @Test fun aRefusalLetsTheAgentStopAtOnce() = runBlocking {
        val api = Wake(mutableListOf(Result.failure(TeamCodeException(409, "ended", "session_not_running"))))
        assertNull(TodoStop.hold(api, 120_000))
        assertEquals(1, api.calls)
    }

    @Test fun theHookCommandAsksToHoldOnlyForALocalRun() {
        val plain = TodoStop.hookCommand("/opt/teamcode/teamcode", 4711, session, null)
        val local = TodoStop.hookCommand("/opt/teamcode/teamcode", 4711, session, "7f3a9c10-aaaa-4bbb-8ccc-000000000001", wake = true)
        assertFalse("--wake" in plain)
        assertTrue(local.endsWith(" --run 7f3a9c10-aaaa-4bbb-8ccc-000000000001 --wake"), local)
    }

    @Test fun theStopHookOfALocalRunMayRunForHoursAndTheContextHookIsShort() {
        val stop = TodoStop.hookCommand("/opt/teamcode/teamcode", 4711, session, null, wake = true)
        val context = TodoStop.contextCommand("/opt/teamcode/teamcode", 4711, session, null)
        val hooks = Json.parseToJsonElement(assertNotNull(cloudHookSettings(emptyList(), todoStop = stop, todoContext = context, todoStopTimeoutSeconds = LOCAL_STOP_TIMEOUT_SECONDS))).jsonObject["hooks"]!!.jsonObject
        val stopTimeout = hooks["Stop"]!!.jsonArray.single().jsonObject["hooks"]!!.jsonArray.single().jsonObject["timeout"]!!.jsonPrimitive.int
        assertEquals(LOCAL_STOP_TIMEOUT_SECONDS, stopTimeout)
        assertTrue(stopTimeout > 3_600)
        assertEquals("compact|resume", hooks["SessionStart"]!!.jsonArray.single().jsonObject["matcher"]!!.jsonPrimitive.content)
    }

    @Test fun theLocalSessionGivesTheAgentTheWaitTodoAndArtifactTools() {
        val plan = agentLaunch("claude", emptyList(), 4711, "synthetic-local-token-long-enough", runId = "7f3a9c10-aaaa-4bbb-8ccc-000000000001",
            waits = WaitsMcp("/opt/teamcode/teamcode", session), todo = TodoMcp("/opt/teamcode/teamcode", session))
        val config = Json.parseToJsonElement(plan.arguments[plan.arguments.indexOf("--mcp-config") + 1]).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(setOf("teamcode_waits", "teamcode_todo"), config.keys)
        assertTrue("--run" in config["teamcode_waits"]!!.jsonObject["args"]!!.jsonArray.map { it.jsonPrimitive.content })
        val none = agentLaunch("claude", emptyList(), 4711, "synthetic-local-token-long-enough", runId = "7f3a9c10-aaaa-4bbb-8ccc-000000000001")
        assertFalse("--mcp-config" in none.arguments, "Without a session, no wait or todo tool")
    }
}
