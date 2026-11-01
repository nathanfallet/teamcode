package me.nathanfallet.teamcode.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import me.nathanfallet.teamcode.companion.domain.services.ShakeDetector
import me.nathanfallet.teamcode.companion.presentation.components.LocalWideLayout
import me.nathanfallet.teamcode.companion.presentation.navigation.TeamCodeNavHost
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The office on the phone layout, on demo data with a fake [ShakeDetector] in Koin: a shake pushes it over the tabs,
 * an agent opens its session in its place, back from the session lands on the tabs, and nothing stacks a second office.
 */
@OptIn(ExperimentalTestApi::class)
class PhoneOfficeTest {
    /** Shakes on demand; like a real sensor, a shake while nobody listens is lost. */
    private class FakeShakes : ShakeDetector {
        private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override val shakes: Flow<Unit> = events
        /** How many collectors listen: the sensor is on when this is above zero. */
        val listening get() = events.subscriptionCount.value
        fun shake() { events.tryEmit(Unit) }
    }

    @Test fun aShakeOnTheTabsOpensTheOfficeAndASecondOneDoesNotStackAnother() {
        phone { shakes, _ ->
            onNodeWithTag("tab-sessions").assertExists()
            assertEquals(1, shakes.listening)
            runOnUiThread { shakes.shake() }
            settle()
            onNodeWithTag("office").assertExists()
            onNodeWithTag("tab-sessions").assertDoesNotExist()
            // Over the tabs, the sensor is off: a second shake does nothing.
            assertEquals(0, shakes.listening)
            runOnUiThread { shakes.shake() }
            settle()
            // One back, and the tabs are there: there was one office.
            onNodeWithTag("back").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("office").assertDoesNotExist()
            onNodeWithTag("tab-sessions").assertExists()
            assertEquals(1, shakes.listening)
        }
    }

    @Test fun anAgentTappedInTheOfficeOpensItsSessionAndBackLandsOnTheTabs() {
        phone { shakes, _ ->
            runOnUiThread { shakes.shake() }
            settle()
            clickAnAgent()
            onNodeWithTag("session-menu").assertExists()
            onNodeWithTag("tab-sessions").assertDoesNotExist()
            // The session replaced the office: its back leads to the tabs, not to the office.
            onNodeWithTag("back").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("office").assertDoesNotExist()
            onNodeWithTag("tab-sessions").assertExists()
        }
    }

    @Test fun theSessionsTitleHasAButtonIntoTheOfficeForWhoeverCannotShake() {
        phone { _, _ ->
            onNodeWithTag("open-office").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("office").assertExists()
            onNodeWithTag("back").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("tab-sessions").assertExists()
            onNodeWithTag("office").assertDoesNotExist()
        }
    }

    @Test fun noShakeIsListenedToOverASheet() {
        phone { shakes, _ ->
            onNodeWithTag("new-session").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            assertEquals(0, shakes.listening)
            runOnUiThread { shakes.shake() }
            settle()
            onNodeWithTag("office").assertDoesNotExist()
        }
    }

    @Test fun theWideLayoutDoesNotListenToShakes() {
        phone(wide = true) { shakes, _ ->
            onNodeWithTag("wide-layout").assertExists()
            assertEquals(0, shakes.listening)
            runOnUiThread { shakes.shake() }
            settle()
            onNodeWithTag("office").assertDoesNotExist()
        }
    }

    @Test fun theOfficeStaysOpenAcrossTheBreakpointBothWays() {
        // A window that crosses the breakpoint (the layout follows its width): wide enough for the sidebar.
        phone(width = 1200) { shakes, wide ->
            runOnUiThread { shakes.shake() }
            settle()
            // Wider: the office is the panel's screen, as the sidebar button opens it.
            wide.value = true
            settle()
            onNodeWithTag("wide-layout").assertExists()
            onNodeWithTag("office").assertExists()
            // Narrower again: pushed over the tabs, and back leads to them.
            wide.value = false
            settle()
            onNodeWithTag("office").assertExists()
            onNodeWithTag("tab-sessions").assertDoesNotExist()
            onNodeWithTag("back").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("tab-sessions").assertExists()
            onNodeWithTag("office").assertDoesNotExist()
        }
    }

    @Test fun anAgentOpenedInTheWideOfficeKeepsTheSessionWhenTheWindowNarrows() {
        phone(wide = true) { _, wide ->
            onNodeWithTag("sidebar-office").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            // The right-hand room (the cloud) of the panel, as the desktop shell's test sweeps it.
            clickAnAgent(fromX = 300f, fromY = 60f, step = 40f)
            wide.value = false
            settle()
            // The session is pushed over the tabs, without the office under it.
            onNodeWithTag("session-menu").assertExists()
            onNodeWithTag("office").assertDoesNotExist()
            onNodeWithTag("back").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("tab-sessions").assertExists()
            onNodeWithTag("office").assertDoesNotExist()
        }
    }

    /**
     * Sweeps the office's canvas with mouse clicks (a click opens an agent's session) until the office gives way: every
     * [step] px from ([fromX], [fromY]), a short frame advance between clicks.
     */
    private fun DesktopComposeUiTest.clickAnAgent(fromX: Float = 8f, fromY: Float = 8f, step: Float = 14f) {
        val canvas = onNodeWithTag("office-canvas")
        val size = canvas.fetchSemanticsNode().size
        fun open() = onAllNodesWithTag("office").fetchSemanticsNodes().isNotEmpty()
        var y = fromY
        while (y < size.height && open()) {
            var x = fromX
            while (x < size.width && open()) {
                runOnUiThread { canvas.performMouseInput { moveTo(Offset(x, y)); click(Offset(x, y)) } }
                mainClock.advanceTimeBy(150)
                waitForIdle()
                x += step
            }
            y += step
        }
        settle()
        onNodeWithTag("office").assertDoesNotExist()
    }

    private fun DesktopComposeUiTest.settle() {
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    /** The app on demo data in a [width] wide window, laid out [wide] or as a phone, with a fake shake detector. */
    private fun phone(wide: Boolean = false, width: Int = if (wide) 1200 else 390, block: DesktopComposeUiTest.(FakeShakes, MutableState<Boolean>) -> Unit) {
        val shakes = FakeShakes()
        startKoin { modules(demoModules() + module { single<ShakeDetector> { shakes } }) }
        try {
            val layout = mutableStateOf(wide)
            runDesktopComposeUiTest(width, if (width > 600) 800 else 844) {
                val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
                setContent {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        TeamCodeTheme { CompositionLocalProvider(LocalWideLayout provides layout.value) { TeamCodeNavHost() } }
                    }
                }
                settle()
                block(shakes, layout)
                mainClock.advanceTimeBy(2_000)
                waitForIdle()
            }
        } finally {
            stopKoin()
        }
    }
}
