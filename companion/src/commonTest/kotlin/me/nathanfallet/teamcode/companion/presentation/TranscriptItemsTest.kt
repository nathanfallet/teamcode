package me.nathanfallet.teamcode.companion.presentation

import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.presentation.components.isWebLink
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptItem
import me.nathanfallet.teamcode.companion.presentation.transcript.transcriptItems
import kotlin.test.*

class TranscriptItemsTest {
    private fun call(seq: Long, id: String, parent: String? = null) = TranscriptEvent.ToolCall(seq, seq, id, "Bash", "{}", "ls", parent)
    private fun result(seq: Long, id: String, parent: String? = null) = TranscriptEvent.ToolResult(seq, seq, id, "ok", false, null, parent)

    @Test fun resultsJoinTheirCallsAndRunsOfToolsFold() {
        val items = transcriptItems(listOf(
            TranscriptEvent.Turn(1, 1, TurnPhase.STARTED, null, null),
            TranscriptEvent.UserMessage(2, 2, "Go", "i1", "m1"),
            call(3, "a"), result(4, "a"), call(5, "b"), result(6, "b"),
            TranscriptEvent.AgentMessage(7, 7, "Done.", null),
            call(8, "c"),
            TranscriptEvent.Turn(9, 9, TurnPhase.COMPLETED, 2_000, 3),
            TranscriptEvent.Unknown(10, 10, "something.new"),
            // A phase this version does not know does not end the turn.
            TranscriptEvent.Turn(11, 11, TurnPhase.of("paused"), null, null),
        ))
        assertEquals(listOf("User", "Tools", "Agent", "Tool", "TurnEnd"), items.map { it::class.simpleName })
        val folded = assertIs<TranscriptItem.Tools>(items[1])
        assertEquals(listOf("a", "b"), folded.tools.map { it.call?.id }); assertEquals(listOf("a", "b"), folded.tools.map { it.result?.id })
        // Still running: no result yet.
        assertNull(assertIs<TranscriptItem.Tool>(items[3]).result)
        assertEquals(listOf(2L, 3, 7, 8, 9), items.map { it.key })
    }

    @Test fun aResultWhoseCallIsNotLoadedShowsAloneAndSubagentStepsFoldApart() {
        val items = transcriptItems(listOf(result(1, "old"), call(2, "agent"), call(3, "inner", parent = "agent"), result(4, "inner", parent = "agent"), result(5, "agent")))
        assertEquals(2, items.size)
        val main = assertIs<TranscriptItem.Tools>(items[0]).tools
        assertNull(main[0].call); assertEquals("old", main[0].result?.id)
        assertEquals("agent", main[1].call?.id); assertEquals("agent", main[1].result?.id)
        assertTrue(items[1].nested)
    }

    @Test fun consumedInputsAreSkippedAndRejectedOnesShown() {
        val items = transcriptItems(listOf(
            TranscriptEvent.InputChanged(1, 1, "i1", "consumed", null),
            TranscriptEvent.InputChanged(2, 2, "i2", "rejected", "delivery_failed"),
            TranscriptEvent.AgentMessage(3, 3, "  ", null),
        ))
        assertEquals(listOf(2L), items.map { it.key })
    }

    @Test fun onlyWebLinksWithAHostOpen() {
        assertTrue(isWebLink("https://example.org/a?b#c"))
        assertTrue(isWebLink("HTTP://example.org"))
        // A bidirectional override or a zero-width mark would make the confirmed URL read differently.
        assertFalse(isWebLink("https://good.example\u202E/moc.live"))
        assertFalse(isWebLink("https://good\u200B.example"))
        for (link in listOf("javascript:alert(1)", "file:///etc/passwd", "intent://x#Intent;end", "https://", "https:// example.org", "mailto:a@b.c", "https://example.org/\u0000"))
            assertFalse(isWebLink(link), link)
    }
}
