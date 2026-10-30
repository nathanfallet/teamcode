package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.WorkflowPendingDeliverableView
import me.nathanfallet.teamcode.api.models.WorkflowSessionPendingView
import me.nathanfallet.teamcode.cli.cloud.agent.WorkflowStop
import kotlin.test.*

/** The Stop hook of a workflow step's agent: send it back once, and fail open on everything else. */
class WorkflowStopTest {
    private val stop = """{"session_id":"abc","hook_event_name":"Stop","stop_hook_active":false,"last_assistant_message":"I am done."}"""
    private val pending = WorkflowSessionPendingView(true, "r1", "Analyze the ticket", WorkflowPendingDeliverableView("artifact"))

    private fun block(output: String?) = Json.parseToJsonElement(assertNotNull(output)).jsonObject.also { assertEquals(setOf("decision", "reason"), it.keys) }

    @Test fun blocksOnceWithAReasonNamingTheStepTheDeliverableAndTheTwoWaysOut() = runTest {
        val answer = block(WorkflowStop.decide(stop) { pending })
        assertEquals("block", answer["decision"]!!.jsonPrimitive.content)
        val reason = answer["reason"]!!.jsonPrimitive.content
        assertTrue("Analyze the ticket" in reason, reason)
        assertTrue("id of an artifact" in reason, reason)
        assertTrue("`deliver`" in reason && "teamcode_workflow" in reason, reason)
        assertTrue("`ask_human`" in reason && "person's answer" in reason, reason)
        assertTrue("end your turn" in reason, reason)
        // Without the field (an older Claude Code) the first stop is the one that is answered.
        assertNotNull(WorkflowStop.decide("""{"hook_event_name":"Stop"}""") { pending })
        assertNotNull(WorkflowStop.decide("""{}""") { pending })
    }

    @Test fun neverTrapsTheAgentTwice() = runTest {
        var asked = false
        assertNull(WorkflowStop.decide("""{"hook_event_name":"Stop","stop_hook_active":true}""") { asked = true; pending })
        assertFalse(asked, "the relay is not even asked")
        assertNull(WorkflowStop.decide("""{"stop_hook_active":"true"}""") { pending })
    }

    @Test fun printsNothingWhenThereIsNothingToDo() = runTest {
        assertNull(WorkflowStop.decide(stop) { WorkflowSessionPendingView(false) })
        assertNull(WorkflowStop.decide(stop) { null }, "no relay, or a relay that answers nothing")
        // An input that is not a Stop hook object says nothing about this turn.
        assertNull(WorkflowStop.decide("""{"hook_event_name":"SubagentStop","stop_hook_active":false}""") { pending })
    }

    @Test fun failsOpenOnGarbageInput() = runTest {
        for (input in listOf("", "not json", "[1,2]", "\"Stop\"", "{\"stop_hook_active\":", "\u0000\u0001")) {
            var asked = false
            assertNull(WorkflowStop.decide(input) { asked = true; pending }, input)
            assertFalse(asked, "garbage is never answered with a question to the server")
        }
    }

    @Test fun failsOpenOnAnyErrorOfTheRelay() = runTest {
        assertNull(WorkflowStop.decide(stop) { throw IllegalStateException("relay down") })
        assertNull(WorkflowStop.decide(stop) { throw me.nathanfallet.teamcode.client.TeamCodeException(404, "not found", "not_found") }, "an older server")
        assertNull(WorkflowStop.decide(stop) { throw me.nathanfallet.teamcode.client.TeamCodeException(403, "scope", "credential_scope") })
    }

    @Test fun aRelayThatHangsIsGivenTwoSecondsThenIgnored() = runTest {
        val never = CompletableDeferred<WorkflowSessionPendingView>()
        val started = testScheduler.currentTime
        assertNull(WorkflowStop.decide(stop) { never.await() })
        assertEquals(WorkflowStop.TIMEOUT_MILLIS, testScheduler.currentTime - started, "the virtual time spent waiting")
        assertEquals(2_000L, WorkflowStop.TIMEOUT_MILLIS)
        assertNull(WorkflowStop.decide(stop) { delay(5_000); pending })
        // A slow but timely answer still counts.
        assertNotNull(WorkflowStop.decide(stop) { delay(1_900); pending })
    }

    @Test fun cancellationIsNotSwallowed() = runTest {
        assertFailsWith<kotlinx.coroutines.CancellationException> { WorkflowStop.decide(stop) { throw kotlinx.coroutines.CancellationException("stop") } }
    }

    @Test fun theReasonDescribesEachDeliverableAndCleansTheTitle() {
        fun reason(type: String?, domain: String? = null, title: String? = "Ship") =
            WorkflowStop.reason(WorkflowSessionPendingView(true, "r", title, type?.let { WorkflowPendingDeliverableView(it, domain) }))
        assertTrue("an https address on github.com" in reason("url", "github.com"))
        assertTrue("an https address" in reason("url") && " on " !in reason("url").substringAfter("expected"))
        assertTrue("the text" in reason("text")); assertTrue("JSON value" in reason("json"))
        assertTrue("instruction asked for" in reason(null))
        assertTrue("a workflow step" in reason("text", title = null))
        // The title is the team's: one line, bounded, without control characters.
        val cleaned = reason("text", title = "Line\nBreak\u0007" + "x".repeat(500))
        assertFalse('\n' in cleaned || '\u0007' in cleaned); assertTrue(cleaned.length < 800)
    }

    @Test fun theHookCommandNamesTheRelayTheSessionAndTheRunButNoToken() {
        val run = "11111111-2222-4333-8444-555555555555"
        assertEquals("'/opt/teamcode/teamcode' cloud workflow-stop --relay http://127.0.0.1:4242 --session s-1 --run $run", WorkflowStop.hookCommand("/opt/teamcode/teamcode", 4242, "s-1", run))
        assertEquals("'/opt/teamcode/teamcode' cloud workflow-stop --relay http://127.0.0.1:4242 --session s-1", WorkflowStop.hookCommand("/opt/teamcode/teamcode", 4242, "s-1", null))
        assertFailsWith<IllegalArgumentException> { WorkflowStop.hookCommand("/x", 0, "s-1", null) }
        assertFailsWith<IllegalArgumentException> { WorkflowStop.hookCommand("/x", 4242, "s 1; rm -rf /", null) }
        assertFailsWith<IllegalArgumentException> { WorkflowStop.hookCommand("/x", 4242, "s-1", "not a uuid") }
    }
}
