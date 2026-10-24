package me.nathanfallet.teamcode.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.FileText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.semantics.getOrNull
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.screens.SIDEBAR_ROWS
import me.nathanfallet.teamcode.companion.presentation.screens.Sidebar
import me.nathanfallet.teamcode.companion.presentation.screens.SidebarActions
import me.nathanfallet.teamcode.companion.presentation.screens.SidebarSelection
import me.nathanfallet.teamcode.companion.presentation.screens.SidebarState
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import kotlin.test.*

/** The desktop's keyboard and sidebar: Enter sends, Shift+Enter and Alt+Enter break the line; the sidebar's lists. */
@OptIn(ExperimentalTestApi::class)
class DesktopInteractionsTest {
    @Test fun enterSubmitsAndShiftOrAltEnterBreakTheLine() = runComposeUiTest {
        var text = ""
        var submitted = 0
        setContent {
            TeamCodeTheme {
                var value by remember { mutableStateOf("") }
                TcTextField(value, { value = it; text = it }, singleLine = false, maxLines = 6, tag = "field", onSubmit = { submitted++ })
            }
        }
        val field = onNodeWithTag("field")
        field.performClick()
        field.performTextInput("one")
        field.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        field.performTextInput("two")
        field.performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
        field.performTextInput("three")
        waitForIdle()
        assertEquals("one\ntwo\nthree", text)
        assertEquals(0, submitted)
        field.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        // Enter sends and types nothing.
        assertEquals(1, submitted)
        assertEquals("one\ntwo\nthree", text)
    }

    @Test fun withoutASubmitActionEnterBreaksTheLineAsBefore() = runComposeUiTest {
        var text = ""
        setContent {
            TeamCodeTheme {
                var value by remember { mutableStateOf("") }
                TcTextField(value, { value = it; text = it }, singleLine = false, tag = "field")
            }
        }
        val field = onNodeWithTag("field")
        field.performClick()
        field.performTextInput("one")
        field.performKeyInput { pressKey(Key.Enter) }
        field.performTextInput("two")
        waitForIdle()
        assertEquals("one\ntwo", text)
    }

    @Test fun theSidebarListsTheMostRecentRowsThenSeeMoreAndPinsTheAccounts() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val accounts = listOf(account("a1"), account("a2", origin = "https://other.example", username = "bob"))
        val state = SidebarState(
            account = accounts[0], accounts = accounts,
            sessions = (0 until 8).map { cloudSession("s$it", SessionState.READY).copy(name = "Session $it") }, sessionsLoading = false, finishedTurns = emptySet(),
            artifacts = (0 until 8).map { artifact("r$it") }, artifactsLoading = false,
            selection = SidebarSelection.None, now = 10_000,
        )
        val actions = SidebarActions(
            onNewSession = { opened += "new" }, onOpenSession = { opened += "session ${it.id}" }, onOpenSessionArtifacts = { opened += "session artifacts ${it.id}" },
            onAllSessions = { opened += "all sessions" }, onOpenArtifact = { opened += "artifact ${it.id}" }, onAllArtifacts = { opened += "all artifacts" },
            onSwitchAccount = { opened += "switch ${it.id}" }, onSignInAgain = { opened += "sign in ${it.id}" }, onAddAccount = { opened += "add" }, onSettings = { opened += "settings" },
        )
        setContent { TeamCodeTheme { Sidebar(state, actions) } }
        // At most five of each, whatever the list holds.
        onAllNodes(hasTestTagPrefix("sidebar-session-")).assertCountEquals(SIDEBAR_ROWS)
        onAllNodes(hasTestTagPrefix("sidebar-artifact-")).assertCountEquals(SIDEBAR_ROWS)
        onNodeWithTag("sidebar-all-sessions").performClick()
        onNodeWithTag("sidebar-all-artifacts").performClick()
        onNodeWithTag("sidebar-session-s0").performClick()
        // The accounts, pinned below the scrolling lists: the other one switches to it.
        onAllNodes(hasAnyAncestor(hasTestTag("sidebar-accounts")) and hasTestTagPrefix("sidebar-account-")).assertCountEquals(2)
        onNodeWithTag("sidebar-account-bob-other.example").performClick()
        assertEquals(listOf("all sessions", "all artifacts", "session s0", "switch a2"), opened)
        onAllNodesWithTag("sidebar-new-session").assertCountEquals(1)
    }

    @Test fun theArrowsMoveThroughTheSidebarAndEnterOpensTheFocusedRow() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val state = SidebarState(
            account = account("a1"), accounts = listOf(account("a1")),
            sessions = (0 until 3).map { cloudSession("s$it", SessionState.READY).copy(name = "Session $it") }, sessionsLoading = false, finishedTurns = setOf("s2"),
            artifacts = emptyList(), artifactsLoading = false, selection = SidebarSelection.None, now = 10_000, sessionsFilter = "Paused",
        )
        val actions = SidebarActions(
            onNewSession = {}, onOpenSession = { opened += "session ${it.id}" }, onOpenSessionArtifacts = {}, onAllSessions = {}, onOpenArtifact = {}, onAllArtifacts = {},
            onSwitchAccount = {}, onSignInAgain = {}, onAddAccount = {}, onSettings = {}, onSearch = { opened += "search" }, searchShortcut = "⌘K",
        )
        setContent { TeamCodeTheme { Sidebar(state, actions) } }
        onNodeWithTag("sidebar-session-s0").requestFocus()
        onNodeWithTag("sidebar-session-s0").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown) }
        onNodeWithTag("sidebar-session-s2").assertIsFocused()
        onNodeWithTag("sidebar-session-s2").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("session s2"), opened)
        // The filter of the shared list, and the search row with its shortcut.
        onNodeWithText("SESSIONS · PAUSED").assertExists()
        onNodeWithText("⌘K").assertExists()
        onNodeWithTag("sidebar-search").performClick()
        assertEquals(listOf("session s2", "search"), opened)
    }

    @Test fun cmdOrCtrlKIsThePalettesShortcut() = runComposeUiTest {
        val commands = mutableListOf<me.nathanfallet.teamcode.companion.presentation.navigation.AppCommand>()
        setContent {
            TeamCodeTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.onPreviewKeyEvent { event -> me.nathanfallet.teamcode.companion.shortcut(event)?.let { commands += it; true } ?: false }) {
                    TcTextField("", {}, tag = "field")
                }
            }
        }
        onNodeWithTag("field").performClick()
        onNodeWithTag("field").performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.K) } }
        onNodeWithTag("field").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } }
        waitForIdle()
        assertEquals(List(2) { me.nathanfallet.teamcode.companion.presentation.navigation.AppCommand.COMMAND_PALETTE }, commands)
    }
}

private fun hasTestTagPrefix(prefix: String) = androidx.compose.ui.test.SemanticsMatcher("tag starts with $prefix") { node ->
    node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
}

/** The command palette alone: typing filters, the arrows move the selection, Enter runs it and closes. */
@OptIn(ExperimentalTestApi::class)
class CommandPaletteViewTest {
    @Test fun typingFiltersTheArrowsMoveAndEnterRuns() = runComposeUiTest {
        val ran = mutableListOf<String>()
        var open by mutableStateOf(true)
        val items = listOf("Alpha report", "Beta report", "Gamma notes").mapIndexed { index, title ->
            me.nathanfallet.teamcode.companion.presentation.screens.PaletteItem("i$index", me.nathanfallet.teamcode.companion.presentation.screens.PaletteSection.ARTIFACTS, title,
                com.adamglin.PhosphorIcons.Regular.FileText) { ran += title }
        }
        setContent { TeamCodeTheme { if (open) me.nathanfallet.teamcode.companion.presentation.screens.CommandPalette(items, onDismiss = { open = false }) } }
        waitForIdle()
        onNodeWithTag("palette-field").performTextInput("report")
        waitForIdle()
        onNodeWithTag("palette-i2").assertDoesNotExist()
        onNodeWithTag("palette-i0").assertIsSelected()
        onNodeWithTag("palette-field").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag("palette-i1").assertIsSelected()
        // Wraps around.
        onNodeWithTag("palette-field").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag("palette-i0").assertIsSelected()
        onNodeWithTag("palette-field").performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("Beta report"), ran)
        assertFalse(open)
    }
}
