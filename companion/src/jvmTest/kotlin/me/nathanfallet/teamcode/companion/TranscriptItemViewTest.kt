package me.nathanfallet.teamcode.companion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptItem
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptItemView
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
}
