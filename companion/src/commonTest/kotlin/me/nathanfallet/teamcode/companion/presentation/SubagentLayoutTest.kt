package me.nathanfallet.teamcode.companion.presentation

import me.nathanfallet.teamcode.companion.presentation.screens.PanelPlacement
import me.nathanfallet.teamcode.companion.presentation.screens.SubagentLayout
import me.nathanfallet.teamcode.companion.presentation.screens.SubagentPanelMode
import me.nathanfallet.teamcode.companion.presentation.screens.subagentLayout
import me.nathanfallet.teamcode.companion.presentation.screens.subagentPanelMode
import kotlin.test.*

/** Where the subagents panel goes, by Material's window size classes (the left sidebar's breakpoints), and what a resize does. */
class SubagentLayoutTest {
    @Test fun theModeFollowsTheWindowSizeClasses() {
        // Compact width, or a phone in landscape (wide but shorter than 480 dp): the phone layout, a screen of its own.
        assertEquals(SubagentPanelMode.SCREEN, subagentPanelMode(widthDp = 412, heightDp = 915))
        assertEquals(SubagentPanelMode.SCREEN, subagentPanelMode(widthDp = 915, heightDp = 412))
        // Medium and expanded (600 to 1199 dp): the sidebar already takes a column, the panel slides over the conversation.
        assertEquals(SubagentPanelMode.OVERLAY, subagentPanelMode(widthDp = 600, heightDp = 900))
        assertEquals(SubagentPanelMode.OVERLAY, subagentPanelMode(widthDp = 1024, heightDp = 768))
        assertEquals(SubagentPanelMode.OVERLAY, subagentPanelMode(widthDp = 1199, heightDp = 900))
        // Large (1200 to 1599 dp): beside the conversation.
        assertEquals(SubagentPanelMode.SIDE, subagentPanelMode(widthDp = 1200, heightDp = 800))
        assertEquals(SubagentPanelMode.SIDE, subagentPanelMode(widthDp = 1366, heightDp = 1024))
        // Extra-large (1600 dp and more): beside it too, and an open subagent gets its own column next to the main thread.
        assertEquals(SubagentPanelMode.SIDE_AND_THREAD, subagentPanelMode(widthDp = 1600, heightDp = 900))
        assertEquals(SubagentPanelMode.SIDE_AND_THREAD, subagentPanelMode(widthDp = 2560, heightDp = 1440))
    }

    @Test fun nothingShowsWithoutSubagentsOrCommands() {
        for (mode in SubagentPanelMode.entries)
            assertEquals(SubagentLayout(PanelPlacement.NONE, threadBeside = false, threadReplacesMain = false),
                subagentLayout(mode, hasContent = false, panelOpen = true, panelHidden = false, threadOpen = false), mode.name)
    }

    @Test fun eachModePlacesThePanelAndTheOpenSubagent() {
        fun layout(mode: SubagentPanelMode, open: Boolean = false, hidden: Boolean = false, thread: Boolean = false) =
            subagentLayout(mode, hasContent = true, panelOpen = open, panelHidden = hidden, threadOpen = thread)
        // A screen or an overlay opens on demand (the header's button) and closes; the side panel shows until hidden.
        assertEquals(PanelPlacement.NONE, layout(SubagentPanelMode.SCREEN).panel)
        assertEquals(PanelPlacement.SCREEN, layout(SubagentPanelMode.SCREEN, open = true).panel)
        assertEquals(PanelPlacement.NONE, layout(SubagentPanelMode.OVERLAY).panel)
        assertEquals(PanelPlacement.OVERLAY, layout(SubagentPanelMode.OVERLAY, open = true).panel)
        assertEquals(PanelPlacement.SIDE, layout(SubagentPanelMode.SIDE).panel)
        assertEquals(PanelPlacement.NONE, layout(SubagentPanelMode.SIDE, hidden = true).panel)
        // An open subagent takes the main thread's place ("switch"), beside it only on an extra-large window.
        for (mode in listOf(SubagentPanelMode.SCREEN, SubagentPanelMode.OVERLAY, SubagentPanelMode.SIDE))
            assertTrue(layout(mode, thread = true).threadReplacesMain && !layout(mode, thread = true).threadBeside, mode.name)
        assertTrue(layout(SubagentPanelMode.SIDE_AND_THREAD, thread = true).threadBeside)
        assertFalse(layout(SubagentPanelMode.SIDE_AND_THREAD, thread = true).threadReplacesMain)
    }

    @Test fun aResizeKeepsTheOpenSubagentAndMovesOnlyThePanel() {
        // The open subagent and the panel's state live in the view model: a resize only changes where they show.
        val wide = subagentLayout(SubagentPanelMode.SIDE_AND_THREAD, hasContent = true, panelOpen = false, panelHidden = false, threadOpen = true)
        val large = subagentLayout(SubagentPanelMode.SIDE, hasContent = true, panelOpen = false, panelHidden = false, threadOpen = true)
        val medium = subagentLayout(SubagentPanelMode.OVERLAY, hasContent = true, panelOpen = false, panelHidden = false, threadOpen = true)
        assertTrue(wide.threadBeside); assertTrue(large.threadReplacesMain); assertTrue(medium.threadReplacesMain)
        assertEquals(PanelPlacement.SIDE, large.panel)
        assertEquals(PanelPlacement.NONE, medium.panel, "Narrowed: the panel folds away instead of covering the conversation unasked")
    }
}
