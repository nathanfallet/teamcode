package me.nathanfallet.teamcode.companion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import me.nathanfallet.teamcode.companion.presentation.components.LargeTitle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LargeTitleTest {
    @Test fun theTitleStaysOnOneLineWhenTheActionsDoNotFitBesideIt() = runComposeUiTest {
        setContent {
            TeamCodeTheme {
                Box(Modifier.width(340.dp)) {
                    LargeTitle("Sessions", Modifier.testTag("bar")) {
                        repeat(4) { Box(Modifier.size(width = 70.dp, height = 44.dp)) }
                    }
                }
            }
        }
        // Squeezed into a column of letters the title would be several lines tall; on its own line the bar is title + actions.
        val bar = onNodeWithTag("bar").getBoundsInRoot()
        assertTrue(bar.height < 120.dp, "the bar is ${bar.height} tall: the title wrapped letter by letter")
    }

    // The Sessions bar of a wide phone: two round buttons, the filter pill, then the plus. A Row gave the plus what was left, which
    // was nothing; every action must keep its size and stay inside the screen at any width, wrapping to another line when needed.
    @Test fun noActionIsSqueezedOrPushedOffScreenAtAnyWidth() = runComposeUiTest {
        var width by mutableStateOf(320.dp)
        setContent {
            TeamCodeTheme {
                Box(Modifier.width(width)) {
                    LargeTitle("Sessions") {
                        Box(Modifier.size(44.dp).testTag("a0"))
                        Box(Modifier.size(44.dp).testTag("a1"))
                        Box(Modifier.size(width = 190.dp, height = 44.dp).testTag("a2"))
                        Box(Modifier.size(44.dp).testTag("a3"))
                    }
                }
            }
        }
        for (w in 320..520 step 10) {
            width = w.dp
            waitForIdle()
            for ((tag, expected) in listOf("a0" to 44.dp, "a1" to 44.dp, "a2" to 190.dp, "a3" to 44.dp)) {
                val b = onNodeWithTag(tag).getBoundsInRoot()
                assertTrue(b.width >= expected, "at $w dp the action $tag is ${b.width} wide, not $expected: it was squeezed")
                assertTrue(b.left >= 0.dp && b.right <= w.dp, "at $w dp the action $tag is outside the screen ($b)")
            }
        }
    }
}
