package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.domain.models.BackgroundTask
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.usecases.office.ActivityPeeks
import me.nathanfallet.teamcode.companion.domain.usecases.office.SessionActivity
import me.nathanfallet.teamcode.companion.domain.usecases.office.Steps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What an agent is doing, read from its newest events, and how often those are read. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionActivityTest {
    private fun call(seq: Long, name: String, summary: String?, input: String = "{}", parent: String? = null) = TranscriptEvent.ToolCall(seq, seq, "t$seq", name, input, summary, parent)
    private fun todo(seq: Long, vararg status: String) = call(
        seq, "TodoWrite", null,
        """{"todos":[${status.mapIndexed { i, s -> """{"content":"Step $i","status":"$s","activeForm":"Doing step $i"}""" }.joinToString(",")}]}""",
    )
    private fun said(seq: Long, text: String, parent: String? = null) = TranscriptEvent.AgentMessage(seq, seq, text, parent)

    @Test fun theLastToolTheAgentUsedIsWhatItIsDoing() {
        val activity = SessionActivity.of(listOf(call(1, "Read", "a.kt"), call(2, "Edit", "b.kt")))
        assertEquals("Edit", activity.tool)
        assertEquals("b.kt", activity.summary)
    }

    @Test fun aSubagentsToolCallIsNotTheAgentsOwn() {
        val activity = SessionActivity.of(listOf(call(1, "Edit", "b.kt"), call(2, "Grep", "x", parent = "agent1")))
        assertEquals("Edit", activity.tool)
    }

    @Test fun theChecklistCountsItsCompletedItemsAndNamesTheCurrentOne() {
        val activity = SessionActivity.of(listOf(todo(1, "completed", "completed", "in_progress", "pending")))
        assertEquals(Steps(2, 4, "Doing step 2"), activity.steps)
        assertNull(activity.tool)
    }

    @Test fun theNewestChecklistWins() {
        val activity = SessionActivity.of(listOf(todo(1, "pending", "pending"), todo(2, "completed", "in_progress")))
        assertEquals(Steps(1, 2, "Doing step 1"), activity.steps)
    }

    @Test fun anInputThatIsNotAChecklistGivesNoSteps() {
        assertNull(SessionActivity.of(listOf(call(1, "TodoWrite", null, "not json"))).steps)
        assertNull(SessionActivity.of(listOf(call(1, "TodoWrite", null, """{"todos":"none"}"""))).steps)
        assertNull(SessionActivity.of(listOf(call(1, "TodoWrite", null, """{"todos":[]}"""))).steps)
        assertNull(SessionActivity.of(listOf(call(1, "TodoWrite", null, """{"todos":[1,2]}"""))).steps)
    }

    @Test fun onlyRunningSubagentsOfTheLastReportAreCounted() {
        val agent = BackgroundTask("t1", "local_agent", "Explore", "Explore", null, null)
        val shell = BackgroundTask("t2", "local_bash", "npm test", null, null, null)
        val old = TranscriptEvent.Tasks(1, 1, listOf(agent, agent.copy(id = "t3")), true)
        val last = TranscriptEvent.Tasks(2, 2, listOf(agent, shell), true)
        assertEquals(1, SessionActivity.of(listOf(old, last)).subagents)
        assertEquals(0, SessionActivity.of(listOf(old, TranscriptEvent.Tasks(3, 3, emptyList(), true))).subagents)
    }

    @Test fun theLastWordsAreShortenedNotCut() {
        val activity = SessionActivity.of(listOf(said(1, "  First  "), said(2, "x".repeat(400))))
        assertEquals(161, activity.lastMessage?.length)
        assertEquals("First", SessionActivity.of(listOf(said(1, "  First  "))).lastMessage)
        assertNull(SessionActivity.of(listOf(said(1, "inside", parent = "agent1"))).lastMessage)
    }

    @Test fun nothingIsGuessedFromNoEvents() = assertEquals(SessionActivity(), SessionActivity.of(emptyList()))

    private fun page(vararg events: TranscriptEvent) = TranscriptPage(events.toList(), null, events.size.toLong())

    @Test fun aSessionIsReadOnceWhileItsReadingIsFresh() = runTest {
        var clock = 1_000L
        val reads = mutableListOf<String>()
        val peeks = ActivityPeeks({ _, id -> reads += id; page(call(1, "Edit", "a.kt")) }, this, { clock }, freshMillis = 20_000)
        peeks.peek("acc", "s1"); peeks.peek("acc", "s1")
        advanceTimeBy(1)
        assertEquals(listOf("s1"), reads)
        assertEquals("Edit", peeks.known.value["s1"]?.tool)
        clock += 5_000
        peeks.peek("acc", "s1")
        advanceTimeBy(1)
        assertEquals(1, reads.size)
        clock += 20_000
        peeks.peek("acc", "s1")
        advanceTimeBy(1)
        assertEquals(2, reads.size)
    }

    @Test fun eachSessionIsReadOnItsOwn() = runTest {
        val reads = mutableListOf<String>()
        val peeks = ActivityPeeks({ _, id -> reads += id; page(call(1, "Read", id)) }, this, { 0L })
        peeks.peek("acc", "s1"); peeks.peek("acc", "s2")
        advanceTimeBy(1)
        assertEquals(setOf("s1", "s2"), reads.toSet())
        assertEquals("s2", peeks.known.value["s2"]?.summary)
    }

    @Test fun aFailedReadShowsNothingAndIsNotRetriedAtOnce() = runTest {
        var reads = 0
        val peeks = ActivityPeeks({ _, _ -> reads++; throw CompanionException.Unsupported("cloud.transcript.v1") }, this, { 0L })
        peeks.peek("acc", "s1")
        advanceTimeBy(1)
        peeks.peek("acc", "s1")
        advanceTimeBy(1)
        assertEquals(1, reads)
        assertEquals(emptyMap(), peeks.known.value)
    }

    @Test fun theOldestReadsAreForgottenPastTheCapacity() = runTest {
        var clock = 0L
        val peeks = ActivityPeeks({ _, id -> page(call(1, "Read", id)) }, this, { clock }, freshMillis = 1, capacity = 2)
        for (id in listOf("s1", "s2", "s3")) { clock += 10; peeks.peek("acc", id); advanceTimeBy(1) }
        assertEquals(setOf("s2", "s3"), peeks.known.value.keys)
    }

    @Test fun anotherAccountIsStartedClean() = runTest {
        val peeks = ActivityPeeks({ _, id -> page(call(1, "Read", id)) }, this, { 0L })
        peeks.peek("acc", "s1")
        advanceTimeBy(1)
        peeks.clear()
        assertEquals(emptyMap(), peeks.known.value)
    }
}
