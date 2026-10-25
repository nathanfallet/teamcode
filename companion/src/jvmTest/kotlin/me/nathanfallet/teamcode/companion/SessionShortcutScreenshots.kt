package me.nathanfallet.teamcode.companion

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import java.io.File
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommand
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommands
import kotlin.test.Test

/**
 * Captures of the desktop window on demo data: a session open, then the next one after Cmd+Shift+] (what the key sends is
 * [AppCommand.NEXT_SESSION]). Opt-in like [DesktopScreenshots]: `TEAMCODE_SCREENSHOTS=/tmp/shots`.
 */
@OptIn(ExperimentalTestApi::class)
class SessionShortcutScreenshots {
    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        val harness = DesktopScreenshots()
        for ((theme, name) in listOf(ThemePreference.DARK to "dark", ThemePreference.LIGHT to "light")) with(harness) {
            scene(1200, 800, theme) {
                onNodeWithTag("sidebar-session-s1").performSemanticsAction(SemanticsActions.OnClick)
                settle()
                shot(output, "before-$name")
                runOnUiThread { AppCommands.send(AppCommand.NEXT_SESSION) }
                settle()
                shot(output, "after-$name")
            }
        }
    }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.settle() {
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }
}
