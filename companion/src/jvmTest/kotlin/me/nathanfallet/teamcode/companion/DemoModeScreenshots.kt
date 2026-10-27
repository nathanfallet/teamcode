package me.nathanfallet.teamcode.companion

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import java.io.File
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommand
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommands
import kotlin.test.Test

/**
 * The built-in demo as a member meets it, on the app's own modules: the sign-in screen with "Try the demo", a session
 * with the demo banner, the scripted reply to a message, Settings. Opt-in: runs only when TEAMCODE_SCREENSHOTS names an
 * output folder, so CI skips it.
 * `TEAMCODE_SCREENSHOTS=/tmp/shots ./gradlew :companion:jvmTest --tests '*DemoModeScreenshots*'`
 */
@OptIn(ExperimentalTestApi::class)
class DemoModeScreenshots {
    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        val shots = DesktopScreenshots()
        for ((name, size) in listOf("phone-390" to (390 to 844), "desktop-1200" to (1200 to 800))) {
            demoApp(CountingEngine(), size.first, size.second, density = 2f) {
                fun shot(step: String) = with(shots) { shot(output, "demo-$name-$step") }
                settle()
                shot("1-sign-in")
                onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
                settle()
                shot("2-home")
                onAllNodesWithText("Migrate billing to the new API").onFirst().performSemanticsAction(SemanticsActions.OnClick)
                settle()
                shot("3-session")
                onNodeWithTag("composer").performTextInput("Can you add a dark mode to the settings screen?")
                onNodeWithTag("send").performClick()
                settle()
                shot("4-scripted-reply")
                if (onAllNodesWithTag("back").fetchSemanticsNodes().isNotEmpty()) onAllNodesWithTag("back").onFirst().performSemanticsAction(SemanticsActions.OnClick)
                settle()
                runOnUiThread { AppCommands.send(AppCommand.SHOW_ACCOUNT) }
                settle()
                shot("5-settings")
            }
        }
    }

    private fun DesktopComposeUiTest.settle() {
        repeat(3) { mainClock.advanceTimeBy(1_000); waitForIdle() }
    }
}
