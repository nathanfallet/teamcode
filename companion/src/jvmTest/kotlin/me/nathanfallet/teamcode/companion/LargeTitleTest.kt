package me.nathanfallet.teamcode.companion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
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
}
