package me.nathanfallet.teamcode.companion.presentation

import me.nathanfallet.teamcode.companion.domain.models.BackgroundTask
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.presentation.transcript.SubagentState
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptItem
import me.nathanfallet.teamcode.companion.presentation.transcript.backgroundCommands
import me.nathanfallet.teamcode.companion.presentation.transcript.liveTasks
import me.nathanfallet.teamcode.companion.presentation.transcript.subagentsOf
import me.nathanfallet.teamcode.companion.presentation.transcript.threadEvents
import me.nathanfallet.teamcode.companion.presentation.transcript.transcriptItems
import kotlin.test.*

/** Subagents and background commands, derived from the transcript the app already loads: no request of their own. */
class SubagentsTest {
    private fun agent(seq: Long, id: String, type: String? = "Explore", description: String = "Map the code", background: Boolean = false, parent: String? = null, name: String = "Agent") =
        TranscriptEvent.ToolCall(seq, seq * 1_000, id, name,
            """{"description":"$description","prompt":"Look around"${type?.let { ""","subagent_type":"$it"""" } ?: ""}${if (background) ""","run_in_background":true""" else ""}}""",
            description, parent)
    private fun call(seq: Long, id: String, parent: String?) = TranscriptEvent.ToolCall(seq, seq * 1_000, id, "Bash", "{}", "ls", parent)
    private fun result(seq: Long, id: String, parent: String? = null, error: Boolean = false) = TranscriptEvent.ToolResult(seq, seq * 1_000, id, "ok", error, null, parent)
    private fun text(seq: Long, text: String, parent: String?) = TranscriptEvent.AgentMessage(seq, seq * 1_000, text, parent)
    private fun turn(seq: Long, phase: TurnPhase) = TranscriptEvent.Turn(seq, seq * 1_000, phase, null, null)
    private fun tasks(seq: Long, vararg tasks: BackgroundTask, stoppable: Boolean = true, scheduled: Int = 0) = TranscriptEvent.Tasks(seq, seq * 1_000, tasks.toList(), stoppable, scheduled)

    private val events = listOf(
        turn(1, TurnPhase.STARTED),
        TranscriptEvent.UserMessage(2, 2_000, "Review the repository", "i1", "m1"),
        agent(3, "a1"),
        call(4, "c1", parent = "a1"), result(5, "c1", parent = "a1"),
        text(6, "Found three modules.", parent = "a1"),
        agent(7, "a2", type = null, description = "Nested", parent = "a1"),
        call(8, "c2", parent = "a2"), result(9, "c2", parent = "a2"),
        result(10, "a2", parent = "a1"),
        result(11, "a1"),
        agent(12, "a3", type = "reviewer", description = "Check the tests"),
        result(13, "a3", error = true),
        agent(14, "a4", description = "Still going", name = "Task"),
        text(15, "Started two subagents.", parent = null),
    )

    @Test fun everyAgentCallIsASubagentWithItsTypeStateAndDuration() {
        val subagents = subagentsOf(events, running = true, tasks = null, now = 20_000)
        assertEquals(listOf("a1", "a2", "a3", "a4"), subagents.map { it.id })
        val (first, nested, failed, running) = subagents
        assertEquals("Explore", first.type); assertEquals("Map the code", first.description)
        assertEquals(SubagentState.DONE, first.state); assertEquals(8_000L, first.durationMs, "From its call to its result")
        assertEquals(0, first.depth); assertEquals(1, nested.depth, "A subagent's own subagent sits under it"); assertEquals("a1", nested.parentId)
        assertEquals("general-purpose", nested.type, "Claude Code's default type when none is named")
        assertEquals(SubagentState.FAILED, failed.state)
        assertEquals(SubagentState.RUNNING, running.state, "The older Task tool name counts too; no result yet while the turn runs")
        assertEquals(6_000L, running.durationMs, "Running: counted up to now")
        assertEquals(2, first.steps, "Its own tool uses, its subagent's card included, not that subagent's steps")
    }

    @Test fun aSubagentWithoutResultAfterItsTurnEndedWasStopped() {
        val stopped = subagentsOf(events + turn(16, TurnPhase.INTERRUPTED), running = false, tasks = null, now = 20_000)
        assertEquals(SubagentState.STOPPED, stopped.single { it.id == "a4" }.state)
    }

    @Test fun aBackgroundSubagentRunsWhileItsTaskIsLive() {
        val launched = listOf(agent(1, "b1", background = true), result(2, "b1"), text(3, "Working on it.", parent = "b1"))
        val live = BackgroundTask("t1", "local_agent", "Map the code", "Explore", "b1", startedAt = 1_000)
        assertEquals(SubagentState.RUNNING, subagentsOf(launched, running = true, tasks = listOf(live), now = 9_000).single().state, "Its result only said it was launched")
        assertEquals(SubagentState.DONE, subagentsOf(launched, running = true, tasks = emptyList(), now = 9_000).single().state, "Gone from the live set: it ended")
        assertEquals(SubagentState.BACKGROUND, subagentsOf(launched, running = true, tasks = null, now = 9_000).single().state, "Without task reports, the app cannot tell")
        assertEquals("t1", subagentsOf(launched, running = true, tasks = listOf(live), now = 9_000).single().taskId, "Its task, to stop it")
    }

    @Test fun aSubagentWhoseOpenToolUsesOnlyWaitIsParked() {
        fun bash(seq: Long, id: String, command: String) = TranscriptEvent.ToolCall(seq, seq * 1_000, id, "Bash", "{\"command\":\"$command\"}", command, "w")
        fun waiting(vararg calls: TranscriptEvent.ToolCall) = subagentsOf(listOf(agent(1, "w")) + calls, running = true, tasks = null, now = 9_000).single().waiting
        // The cr-orchestre panel's classification: a wait is a Monitor, a lone sleep, a sleep loop, watch, gh … --watch.
        assertTrue(waiting(bash(2, "c1", "until gh pr checks 1; do sleep 10; done")))
        assertTrue(waiting(TranscriptEvent.ToolCall(2, 2_000, "c1", "Monitor", "{}", "deploy", "w")))
        assertTrue(waiting(bash(2, "c1", "sleep 30")))
        assertFalse(waiting(bash(2, "c1", "sleep 2 && ./gradlew build")), "Work after a sleep is work")
        assertFalse(waiting(bash(2, "c1", "kubectl wait --for=condition=ready pod/x")))
        assertFalse(waiting(bash(2, "c1", "sleep 30"), bash(3, "c2", "./gradlew build")), "A build beside the wait is work")
        assertFalse(waiting(), "Thinking, no tool open: working")
    }

    @Test fun theMainThreadKeepsTheAgentCardAndEachSubagentItsOwnEvents() {
        val main = threadEvents(events, null)
        assertTrue(main.none { it is TranscriptEvent.ToolCall && it.parentToolUseId != null })
        val items = transcriptItems(main)
        assertEquals(listOf("User", "Subagent", "Subagent", "Subagent", "Agent"), items.map { it::class.simpleName })
        val card = assertIs<TranscriptItem.Subagent>(items[1])
        assertEquals("a1", card.call.id); assertEquals("a1", card.result?.id)
        assertEquals(listOf(3L, 11L), card.seqs, "The card shows its call and result: the reading anchor finds it")
        val thread = threadEvents(events, "a1")
        assertEquals(listOf(4L, 5L, 6L, 7L, 10L), thread.map { it.seq }, "Its own steps and its subagent's card, not its subagent's steps")
        assertTrue(transcriptItems(thread).none { it.nested }, "Shown at the top level of its own thread")
        assertEquals(listOf(8L, 9L), threadEvents(events, "a2").map { it.seq })
        assertTrue(threadEvents(events, "unknown").isEmpty())
    }

    @Test fun theLatestTaskReportListsTheBackgroundCommandsOfARunningSession() {
        val command = BackgroundTask("b1", "local_bash", "npm test", null, "tool-9", startedAt = 2_000)
        val agentTask = BackgroundTask("t1", "local_agent", "Review", "Explore", "a1", startedAt = 2_000)
        val withTasks = events + tasks(16, command, agentTask)
        assertNull(liveTasks(events, running = true), "No report yet: the runner does not report tasks")
        assertEquals(listOf(command, agentTask), liveTasks(withTasks, running = true)?.tasks)
        assertTrue(liveTasks(withTasks, running = true)!!.stoppable)
        assertEquals(emptyList(), liveTasks(withTasks, running = false)?.tasks, "A paused or ended session runs nothing")
        assertEquals(emptyList(), liveTasks(withTasks + tasks(17), running = true)?.tasks, "The last one ended")
        assertEquals(listOf("b1"), backgroundCommands(liveTasks(withTasks, running = true)!!.tasks, withTasks).map { it.task.id }, "Subagents are listed as subagents")
        val withCall = withTasks + TranscriptEvent.ToolCall(18, 18_000, "tool-9", "Bash", "{}", "npm test -- --watch", null)
        assertEquals("npm test -- --watch", backgroundCommands(liveTasks(withCall, running = true)!!.tasks, withCall).single().command, "The command its call ran, when loaded")
        assertFalse(backgroundCommands(liveTasks(withCall, running = true)!!.tasks, withCall).single().monitor)
        // A monitor is a `local_bash` task too: its call (the Monitor tool) tells them apart.
        val monitor = BackgroundTask("m1", "local_bash", "Watch the deploy", null, "tool-10", startedAt = 3_000)
        val withMonitor = withCall + TranscriptEvent.ToolCall(19, 19_000, "tool-10", "Monitor", "{}", "kubectl rollout status", null) + tasks(20, command, monitor, agentTask, scheduled = 2)
        val listed = backgroundCommands(liveTasks(withMonitor, running = true)!!.tasks, withMonitor)
        assertEquals(listOf(false, true), listed.map { it.monitor })
        assertEquals(2, liveTasks(withMonitor, running = true)!!.scheduled, "Scheduled wake-ups: only their number is reported")
    }
}
