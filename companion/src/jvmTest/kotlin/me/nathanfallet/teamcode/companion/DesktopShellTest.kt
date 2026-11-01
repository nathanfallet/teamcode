package me.nathanfallet.teamcode.companion

import me.nathanfallet.teamcode.companion.demo.DemoContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import me.nathanfallet.teamcode.companion.presentation.components.FocusRingShown
import me.nathanfallet.teamcode.companion.presentation.components.focusRing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.repositories.PreferencesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.presentation.components.LocalWideLayout
import me.nathanfallet.teamcode.companion.presentation.components.LocalWindowTitleBar
import me.nathanfallet.teamcode.companion.presentation.components.WindowTitleBar
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommand
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommands
import me.nathanfallet.teamcode.companion.presentation.navigation.TeamCodeNavHost
import me.nathanfallet.teamcode.companion.presentation.screens.SidebarWidths
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import kotlin.test.*

/**
 * The desktop shell end to end, on demo data (App(), its real screens and navigation): the shared lists, the remembered
 * sidebar width, the command palette, the keyboard, the right-click menus, the compact cards, Settings kept across the
 * breakpoint, and a transcript that opens on its last message.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopShellTest {
    @Test fun theSidebarAndThePanelShareOneLoadAndOneLiveFeed() {
        var feeds = 0
        val sessions = demoSessions().apply {
            val opened = changes
            changes = flow { feeds++; opened.collect { emit(it) } }
        }
        app(modules = demoModules(sessions = sessions)) {
            onNodeWithTag("sidebar-all-sessions").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            // The sidebar and the full list in the panel: one list request, one feed.
            assertEquals(1, sessions.calls.count { it.startsWith("list") }, sessions.calls.toString())
            assertEquals(1, feeds)
            // A filter of the panel's list is the sidebar's too, and its section title says so.
            onNodeWithTag("filter").performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithTag("filter-ended").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("sidebar-session-s0").assertDoesNotExist()
            onAllNodesWithText("SESSIONS · ENDED").onFirst().assertExists()
            assertEquals(1, feeds)
        }
    }

    @Test fun theSidebarOpensAtTheWidthTheMemberLeftIt() {
        app(before = { it.get<PreferencesRepository>().setSidebarWidth(2) }) {
            val width = onNodeWithTag("sidebar").getBoundsInRoot().let { it.right - it.left }
            // The divider's 1 dp hairline sits next to it.
            assertTrue(width in (SidebarWidths[2] - 2.dp)..(SidebarWidths[2] + 1.dp), "sidebar $width")
        }
    }

    @Test fun theDefaultSidebarWidthIsNotSavedAsTheMembersChoice() {
        var preferences: PreferencesRepository? = null
        app(before = { preferences = it.get() }) {
            assertNull(preferences!!.sidebarWidth)
        }
    }

    @Test fun theUpdateBannerHeadsThePanelWithNoGapUnderMacsTitleBar() {
        val updater = object : me.nathanfallet.teamcode.companion.domain.services.AppUpdater {
            override val appVersion = "1.2.0"
            override suspend fun cliVersion() = "1.2.0"
            override suspend fun latestVersion() = "1.3.0"
            override suspend fun update(output: (String) -> Unit, progress: (Float) -> Unit) = me.nathanfallet.teamcode.companion.domain.services.UpdateOutcome.CURRENT
            override fun quit() = Unit
        }
        val extra = org.koin.dsl.module {
            single<me.nathanfallet.teamcode.companion.domain.services.AppUpdater> { updater }
            single<me.nathanfallet.teamcode.companion.domain.services.Clock> { me.nathanfallet.teamcode.companion.domain.services.Clock { 1_000_000_000L } }
        }
        app(modules = demoModules() + me.nathanfallet.teamcode.companion.di.updateModule + extra,
            content = { CompositionLocalProvider(LocalWindowTitleBar provides WindowTitleBar.MAC) { App() } }) {
            repeat(10) {
                if (onAllNodesWithTag("update-banner").fetchSemanticsNodes().isNotEmpty()) return@repeat
                Thread.sleep(500); mainClock.advanceTimeBy(1_000); waitForIdle()
            }
            val banner = onNodeWithTag("update-banner").getBoundsInRoot()
            // The window's buttons sit over the sidebar: the banner heads the panel, at the very top, above the panel's content.
            assertEquals(0.dp, banner.top, "banner $banner")
            assertTrue(onNodeWithTag("content-panel").getBoundsInRoot().top >= banner.bottom, "panel")
        }
    }

    @Test fun underMacsTitleBarTheSidebarHeaderSitsBesideTheWindowButtons() {
        app(content = { CompositionLocalProvider(LocalWindowTitleBar provides WindowTitleBar.MAC) { App() } }) {
            val header = onNodeWithTag("sidebar-header").getBoundsInRoot()
            assertEquals(0.dp, header.top)
            // The brand starts after the close, minimize and zoom buttons, on their line; the new session button fits in the bar.
            val brand = onAllNodesWithText("TeamCode").onFirst().getBoundsInRoot()
            assertTrue(brand.left >= WindowTitleBar.MAC.start, "brand ${brand.left}")
            assertTrue(((brand.top + brand.bottom) / 2 - WindowTitleBar.MAC.height / 2).value in -1f..1f, "brand $brand")
            val button = onNodeWithTag("sidebar-new-session").getBoundsInRoot()
            assertTrue(button.top >= 0.dp && button.bottom <= WindowTitleBar.MAC.height, "button $button")
            // The buttons sit over the sidebar: the panel has no gap of its own under the bar.
            assertTrue(onNodeWithTag("content-panel").getBoundsInRoot().top < WindowTitleBar.MAC.height)
        }
        // Without the bar (Windows, Linux, fullscreen), the header keeps its own place.
        app {
            val brand = onAllNodesWithText("TeamCode").onFirst().getBoundsInRoot()
            assertTrue(brand.top >= 12.dp && brand.left < WindowTitleBar.MAC.start, "brand $brand")
        }
    }

    @Test fun cmdKFindsASessionAndEnterOpensIt() {
        app {
            // The key itself, where the keyboard is (without a menu bar, as on a tablet, the app reads it).
            onNodeWithTag("sidebar-session-s0").requestFocus()
            onNodeWithTag("sidebar-session-s0").performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.K) } }
            settle()
            settle()
            onNodeWithTag("palette").assertExists()
            onNodeWithTag("palette-field").performTextInput("speed ci")
            settle()
            onNodeWithTag("palette-session-s3").assertIsSelected()
            onNodeWithTag("palette-action-new-session").assertDoesNotExist()
            // Run as Enter runs it (Enter itself is tested on the palette alone: a key event navigates off the UI thread here).
            onNodeWithTag("palette-session-s3").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("palette").assertDoesNotExist()
            onNodeWithTag("sidebar-session-s3").assertIsSelected()
        }
    }

    @Test fun thePaletteMovesWithTheArrowsAndRunsAnAction() {
        app {
            palette()
            settle()
            // Nothing typed: the actions first, the first one selected.
            onNodeWithTag("palette-action-new-session").assertIsSelected()
            // The field has the keyboard as soon as the palette opens.
            onNodeWithTag("palette-field").assertIsFocused()
            onNodeWithTag("palette-field").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown) }
            settle()
            onNodeWithTag("palette-action-all-artifacts").assertIsSelected()
            onNodeWithTag("palette-action-all-artifacts").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("sidebar-all-artifacts").assertIsSelected()
            // Escape closes it without running anything.
            palette()
            settle()
            onNodeWithTag("palette-field").performKeyInput { pressKey(Key.Escape) }
            settle()
            onNodeWithTag("palette").assertDoesNotExist()
            onNodeWithTag("sidebar-all-artifacts").assertIsSelected()
        }
    }

    @Test fun theBracketShortcutsStepThroughTheSessionsFromTheOneOpen() {
        app {
            // Nothing open: next opens the first one (what the menu bar's item and the keys send).
            runOnUiThread { AppCommands.send(AppCommand.NEXT_SESSION) }
            settle()
            onNodeWithTag("sidebar-session-s0").assertIsSelected()
            runOnUiThread { AppCommands.send(AppCommand.NEXT_SESSION) }
            settle()
            onNodeWithTag("sidebar-session-s1").assertIsSelected()
            runOnUiThread { AppCommands.send(AppCommand.PREVIOUS_SESSION) }
            settle()
            onNodeWithTag("sidebar-session-s0").assertIsSelected()
            // The first one has no previous: it stays open, not wrapped to the last.
            runOnUiThread { AppCommands.send(AppCommand.PREVIOUS_SESSION) }
            settle()
            onNodeWithTag("sidebar-session-s0").assertIsSelected()
            // Another screen open: previous opens the last session of the list, which may lie beyond the sidebar's rows.
            onNodeWithTag("sidebar-all-artifacts").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            runOnUiThread { AppCommands.send(AppCommand.PREVIOUS_SESSION) }
            settle()
            onNodeWithTag("sidebar-all-artifacts").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
            onNodeWithTag("session-menu").assertExists()
        }
    }

    @Test fun cmdOrCtrlShiftBracketsAreTheSessionShortcutsAndShiftKeepsTheOthersApart() {
        fun press(key: Key, command: Boolean = true, shift: Boolean = false) = shortcut(key, command, shift)
        val open = Key.RightBracket
        val close = Key.LeftBracket
        assertEquals(AppCommand.NEXT_SESSION, press(open, shift = true))
        assertEquals(AppCommand.PREVIOUS_SESSION, press(close, shift = true))
        // Without Shift the brackets are not ours, nor without Cmd or Ctrl; Shift does not turn the other shortcuts on.
        assertNull(press(open))
        assertNull(press(open, command = false, shift = true))
        assertNull(press(Key.K, shift = true))
        assertEquals(AppCommand.COMMAND_PALETTE, press(Key.K))
    }

    @Test fun theArrowsMoveThroughTheSidebarAndThePanelsCards() {
        app {
            onNodeWithTag("sidebar-session-s0").requestFocus()
            onNodeWithTag("sidebar-session-s0").performKeyInput { pressKey(Key.DirectionDown) }
            settle()
            onNodeWithTag("sidebar-session-s1").assertIsFocused()
            onNodeWithTag("sidebar-session-s1").performKeyInput { pressKey(Key.DirectionUp) }
            settle()
            onNodeWithTag("sidebar-session-s0").assertIsFocused()

            onNodeWithTag("sidebar-all-artifacts").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("artifact-${DemoContent.artifactId(0)}").requestFocus()
            onNodeWithTag("artifact-${DemoContent.artifactId(0)}").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown) }
            settle()
            onNodeWithTag("artifact-${DemoContent.artifactId(2)}").assertIsFocused()
        }
    }

    @Test fun theFocusRingShowsForTheKeyboardNotForAClick() = runDesktopComposeUiTest(200, 200) {
        setContent { TeamCodeTheme { Box(Modifier.size(100.dp).focusRing(RectangleShape).clickable {}.testTag("row")) } }
        // Compose focuses what a click hits: no ring for that.
        onNodeWithTag("row").performMouseInput { click(center) }
        waitForIdle()
        onNodeWithTag("row").assertIsFocused()
        onNodeWithTag("row").assert(SemanticsMatcher.expectValue(FocusRingShown, false))
        // A key pressed there: the keyboard is in use, the ring shows.
        onNodeWithTag("row").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag("row").assert(SemanticsMatcher.expectValue(FocusRingShown, true))
    }

    @Test fun aRightClickOnThePanelsCardsOpensTheirMenu() {
        app {
            onNodeWithTag("session-s0").performMouseInput { rightClick(center) }
            settle()
            onNodeWithTag("menu-open").assertExists()
            onNodeWithTag("menu-artifacts").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            // The session's artifacts, in the panel over the list.
            onAllNodesWithText("Artifacts of this session").onFirst().assertExists()

            onNodeWithTag("sidebar-all-artifacts").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("artifact-${DemoContent.artifactId(1)}").performMouseInput { rightClick(center) }
            settle()
            onNodeWithTag("menu-copy-link").assertExists()
            onNodeWithTag("menu-browser").assertExists()
        }
    }

    @Test fun theOfficeIsASidebarButtonAwayFromTheSessionListAndBack() {
        app {
            onNodeWithTag("sidebar-office").assertExists()
            onNodeWithTag("sidebar-office").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("office").assertExists()
            System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let { with(DesktopScreenshots()) { shot(java.io.File(it).also { dir -> dir.mkdirs() }, "office-shell") } }
            // The same button leaves it: the sessions list is back.
            onNodeWithTag("sidebar-office").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("office").assertDoesNotExist()
            onNodeWithTag("session-s0").assertExists()
        }
    }

    @Test fun theWholeOfficeFitsItsPanelAndClickingAnAgentReplacesIt() {
        app {
            onNodeWithTag("sidebar-office").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            val panel = onNodeWithTag("office-canvas").getBoundsInRoot()
            assertTrue(panel.bottom - panel.top > 100.dp)
            // Sweep the right-hand room (the cloud) until a click lands on an agent: the office is replaced by the session.
            val canvas = onNodeWithTag("office-canvas")
            var y = 60f
            while (y < 900f && onAllNodesWithTag("office").fetchSemanticsNodes().isNotEmpty()) {
                var x = 300f
                while (x < 900f && onAllNodesWithTag("office").fetchSemanticsNodes().isNotEmpty()) {
                    runOnUiThread { canvas.performMouseInput { moveTo(Offset(x, y)); click(Offset(x, y)) } }
                    settle()
                    x += 40f
                }
                y += 40f
            }
            settle()
            onNodeWithTag("office").assertDoesNotExist()
        }
    }

    @Test fun artifactCardsAreOneLineRowsOnTheDesktop() {
        app {
            onNodeWithTag("sidebar-all-artifacts").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            val card = onNodeWithTag("artifact-${DemoContent.artifactId(0)}").getBoundsInRoot()
            assertTrue(card.bottom - card.top < 56.dp, "card ${card.bottom - card.top}")
        }
    }

    @Test fun settingsStayOpenWhenTheWindowNarrowsAndComeBackWhenItWidens() {
        val wide = mutableStateOf(true)
        app(content = { CompositionLocalProvider(LocalWideLayout provides wide.value) { TeamCodeNavHost() } }) {
            onNodeWithTag("sidebar-settings").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            wide.value = false
            settle()
            onNodeWithTag("tab-account").assertIsSelected()
            onNodeWithTag("account-ada").assertExists()
            wide.value = true
            settle()
            onNodeWithTag("sidebar-settings").assertIsSelected()
        }
    }

    @Test fun theArtifactsTabOpensTheFullListInThePanelWhenTheWindowWidens() {
        val wide = mutableStateOf(false)
        app(content = { CompositionLocalProvider(LocalWideLayout provides wide.value) { TeamCodeNavHost() } }) {
            onNodeWithTag("tab-artifacts").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            wide.value = true
            settle()
            onNodeWithTag("sidebar-all-artifacts").assertIsSelected()
        }
    }

    private fun automationsOffered() = org.koin.dsl.module {
        single<me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository> {
            FakeFeatures(setOf("cloud.sessions.v1", "cloud.transcript.v1", "cloud.messages.v1", "cloud.events.v1", "cloud.permissions.v1", "artifacts.v1", "automations.v1", "workflows.v1", "waits.v1"))
        }
        single<me.nathanfallet.teamcode.companion.domain.repositories.AutomationRepository> { FakeAutomationRepository() }
    }

    @Test fun settingsHasNoAutomationsRowWhenTheServerOffersNone() {
        app(390, 844) {
            onNodeWithTag("tab-account").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("account-ada").assertExists()
            onNodeWithTag("automations-row").assertDoesNotExist()
        }
    }

    @Test fun onAPhoneWithWaitsWorkflowsAndAutomationsOfferedTheTabBarHoldsAtMostFiveTabs() {
        app(390, 844, modules = demoModules() + automationsOffered()) {
            awaitTag("tab-workflows")
            val tabs = listOf("sessions", "waits", "artifacts", "workflows", "automations", "account").count { onAllNodesWithTag("tab-$it").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(tabs <= 5, "tabs $tabs")
            onNodeWithTag("tab-automations").assertDoesNotExist()
        }
    }

    @Test fun theAutomationsListOpenedFromSettingsFollowsTheWindowAcrossTheBreakpoint() {
        val wide = mutableStateOf(false)
        app(modules = demoModules() + automationsOffered(), content = { CompositionLocalProvider(LocalWideLayout provides wide.value) { TeamCodeNavHost() } }) {
            onNodeWithTag("tab-account").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            awaitTag("automations-row")
            onNodeWithTag("automations-row").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("automations-new").assertExists()
            // Widened: the sidebar's Automations list, in the panel.
            wide.value = true
            settle()
            onNodeWithTag("automations-new").assertExists()
            onNodeWithTag("sidebar-all-automations").assertIsSelected()
            // Narrowed again: Settings is the tab under the list, and back goes to it.
            wide.value = false
            settle()
            onNodeWithTag("automations-new").assertExists()
            onNodeWithTag("back").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("tab-account").assertIsSelected()
        }
    }

    @Test fun theAutomationsListOpenInTheWidePanelBecomesSettingsThenTheListWhenTheWindowNarrows() {
        val wide = mutableStateOf(true)
        app(modules = demoModules() + automationsOffered(), content = { CompositionLocalProvider(LocalWideLayout provides wide.value) { TeamCodeNavHost() } }) {
            onNodeWithTag("sidebar-all-automations").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("automations-new").assertExists()
            wide.value = false
            settle()
            onNodeWithTag("automations-new").assertExists()
            onNodeWithTag("back").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("tab-account").assertIsSelected()
            awaitTag("automations-row")
        }
    }

    @Test fun aSessionOpensOnItsLastMessageOnTheDesktopAndOnAPhone() {
        for ((width, height) in listOf(1200 to 800, 390 to 844)) {
            app(width, height, modules = demoModules(transcript = longTranscript())) {
                onAllNodesWithText("Fix the flaky login test").onFirst().performSemanticsAction(SemanticsActions.OnClick)
                settle()
                onAllNodesWithText("Message 80").onFirst().assertIsDisplayed()
                // The first message is not even composed: the list opened at its end.
                assertTrue(onAllNodesWithText("Message 1", substring = false).fetchSemanticsNodes().isEmpty())
            }
        }
    }

    private fun longTranscript() = object : TranscriptRepository {
        override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage {
            val events = (1..80).map { index ->
                if (index % 2 == 1) TranscriptEvent.UserMessage(index.toLong(), DEMO_NOW - 100_000 + index, "Message $index", "in-$index", "m1")
                else TranscriptEvent.AgentMessage(index.toLong(), DEMO_NOW - 100_000 + index, "Message $index\n\nA longer answer that takes **a few lines** once rendered.", null)
            }
            return TranscriptPage(events, null, 80)
        }
        override fun follow(accountId: String, sessionId: String, after: Long) = flow<LiveUpdate<TranscriptEvent>> { emit(LiveUpdate.Opened(false)); awaitCancellation() }
    }

    /** What Cmd+K and Ctrl+K send, from the menu bar or the app's key handler (`shortcut`, tested on its own). */
    private fun DesktopComposeUiTest.palette() {
        runOnUiThread { AppCommands.send(AppCommand.COMMAND_PALETTE) }
        settle()
    }

    /** Waits for [tag] to appear: the offered-capability checks run on the clock, so it advances while real time passes. */
    private fun DesktopComposeUiTest.awaitTag(tag: String) {
        var waited = 0
        while (onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() && waited++ < 60) { Thread.sleep(100); mainClock.advanceTimeBy(250); waitForIdle() }
        onNodeWithTag(tag).assertExists()
    }

    private fun DesktopComposeUiTest.settle() {
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    /** The app on demo data in a [width] × [height] window, after [before] ran on its Koin. */
    private fun app(
        width: Int = 1200,
        height: Int = 800,
        modules: List<Module> = demoModules(),
        before: (Koin) -> Unit = {},
        content: @androidx.compose.runtime.Composable () -> Unit = { App() },
        block: DesktopComposeUiTest.() -> Unit,
    ) {
        val koin = startKoin { modules(modules) }.koin
        before(koin)
        try {
            runDesktopComposeUiTest(width, height) {
                val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
                // This test's Koin is the one the screens see: the composition local's default is read once per JVM.
                setContent { CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { org.koin.compose.KoinContext(koin) { TeamCodeTheme { content() } } } }
                settle()
                block()
                mainClock.advanceTimeBy(2_000)
                waitForIdle()
            }
        } finally {
            stopKoin()
        }
    }
}
