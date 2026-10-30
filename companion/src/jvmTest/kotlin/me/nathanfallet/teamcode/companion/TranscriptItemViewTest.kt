package me.nathanfallet.teamcode.companion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptItem
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptItemView
import androidx.compose.ui.test.assertTextContains
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.presentation.screens.utcEpochDay
import kotlin.test.Test
import kotlin.test.assertTrue

/** A transcript row's first measure is its final one: the list keeps a scroll position on measured heights only. */
@OptIn(ExperimentalTestApi::class)
class TranscriptItemViewTest {
    @Test fun anAgentMessageIsLaidOutAtItsFullHeightOnItsFirstFrame() = runComposeUiTest {
        // No frame runs on its own: what is checked is the first layout, before anything parsed off the main thread
        // could come back. A row scrolled back into view is composed again, and laid out in that same frame.
        mainClock.autoAdvance = false
        val text = (1..6).joinToString("\n\n") { "Paragraph $it of the agent's answer." }
        setContent { TeamCodeTheme { Box(Modifier.width(400.dp)) { TranscriptItemView(TranscriptItem.Agent(TranscriptEvent.AgentMessage(1, 0, text, null)), memberId = null) } } }
        val height = onNodeWithTag("agent-1").getBoundsInRoot().let { it.bottom - it.top }
        // Six paragraphs are well over 100 dp; an empty placeholder is 0 dp tall and grows a frame later.
        assertTrue(height > 100.dp, "The message was $height tall on its first frame")
    }

    private fun tool(seq: Long, name: String, summary: String?) = TranscriptItem.Tool(
        TranscriptEvent.ToolCall(seq, 0, "t$seq", name, "{}", summary, null),
        TranscriptEvent.ToolResult(seq + 1, 0, "t$seq", "ok", false, 0, null),
    )

    private fun assertChevronAtRightEdge(item: TranscriptItem, row: String, chevron: String) = runComposeUiTest {
        setContent { TeamCodeTheme { Box(Modifier.width(400.dp)) { TranscriptItemView(item, memberId = null) } } }
        val rowBounds = onNodeWithTag(row).getBoundsInRoot()
        val chevronBounds = onNodeWithTag(chevron, useUnmergedTree = true).getBoundsInRoot()
        // The row pads 12 dp on its sides: the chevron ends there, not right after the label.
        assertTrue(rowBounds.right - chevronBounds.right <= 13.dp, "The chevron ends ${rowBounds.right - chevronBounds.right} before the row's right edge")
    }

    @Test fun theActionsHeaderPutsItsChevronAtTheRightEdge() =
        assertChevronAtRightEdge(TranscriptItem.Tools(listOf(tool(1, "Bash", "ls"), tool(3, "Read", "a.kt"))), "tools-1", "tools-chevron-1")

    @Test fun aToolRowWithoutSummaryPutsItsChevronAtTheRightEdge() =
        assertChevronAtRightEdge(tool(1, "Bash", null), "tool-1", "tool-chevron-1")

    @Test fun aToolRowWithASummaryPutsItsChevronAtTheRightEdge() =
        assertChevronAtRightEdge(tool(1, "Bash", "ls -la"), "tool-1", "tool-chevron-1")

    private val at = 1_793_221_200_000L // 2026-10-28 21:00 UTC

    @Test fun aTurnEndKeepsItsDurationAndShowsTheTimeOfDayOnTheSameDay() = runComposeUiTest {
        val turn = TranscriptItem.TurnEnd(TranscriptEvent.Turn(5, at, TurnPhase.COMPLETED, 61_000, 6))
        setContent { TeamCodeTheme { Box(Modifier.width(400.dp)) { TranscriptItemView(turn, memberId = null, today = utcEpochDay(at)) } } }
        onNodeWithText("Turn completed \u00b7 61 s \u00b7 6 steps \u00b7 21:00 UTC").assertExists()
    }

    @Test fun aTurnEndOfAnotherDayShowsTheDateToo() = runComposeUiTest {
        val turn = TranscriptItem.TurnEnd(TranscriptEvent.Turn(5, at, TurnPhase.FAILED, 2_000, null))
        setContent { TeamCodeTheme { Box(Modifier.width(400.dp)) { TranscriptItemView(turn, memberId = null, today = utcEpochDay(at) + 1) } } }
        onNodeWithText("Turn failed \u00b7 2 s \u00b7 2026-10-28 21:00 UTC").assertExists()
    }

    @Test fun everySystemLineShowsItsTime() = runComposeUiTest {
        val events = listOf(
            TranscriptEvent.Status(1, at, "claude", null, null),
            TranscriptEvent.Status(2, at, "claude", null, 1),
            TranscriptEvent.Error(3, at, "boom", "Broke"),
            TranscriptEvent.InputChanged(4, at, "i", "rejected", "busy"),
            TranscriptEvent.InputChanged(5, at, "i", "cancelled", "gone"),
            TranscriptEvent.PermissionRequest(6, at, "r", "Bash", false),
            TranscriptEvent.PermissionRequest(7, at, "q", "AskUserQuestion", true),
            TranscriptEvent.PermissionExpired(8, at, "r"),
            TranscriptEvent.Transferred(9, at, "a", "b"),
            TranscriptEvent.Truncated(10, at, "tool.result", 70_000),
        )
        setContent { TeamCodeTheme { androidx.compose.foundation.layout.Column(Modifier.width(400.dp)) { events.forEach { TranscriptItemView(TranscriptItem.System(it), memberId = null, today = utcEpochDay(at)) } } } }
        events.forEach { onNodeWithTag("system-time-${it.seq}").assertTextContains("21:00 UTC") }
    }

    @Test fun withoutAKnownDayTheDateIsAlwaysShown() = runComposeUiTest {
        setContent { TeamCodeTheme { Box(Modifier.width(400.dp)) { TranscriptItemView(TranscriptItem.System(TranscriptEvent.PermissionExpired(1, at, "r")), memberId = null) } } }
        onNodeWithTag("system-time-1").assertTextContains("2026-10-28 21:00 UTC")
    }
}
