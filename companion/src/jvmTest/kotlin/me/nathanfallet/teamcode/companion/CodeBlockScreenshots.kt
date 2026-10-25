package me.nathanfallet.teamcode.companion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toAwtImage
import java.io.File
import javax.imageio.ImageIO
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptItem
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptItemView
import androidx.compose.foundation.background
import kotlin.test.Test

/** Captures of an agent message with a code block, before and after the copy. Opt-in: `TEAMCODE_SCREENSHOTS=/tmp/shots`. */
@OptIn(ExperimentalTestApi::class)
class CodeBlockScreenshots {
    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        val text = "Here is the fix:\n\n```kotlin\nfun greet(name: String): String {\n    return \"Hello, \$name\"\n}\n```\n\nRun it with `./gradlew test`, or:\n\n```\n./gradlew test\n```"
        for ((theme, suffix) in listOf(ThemePreference.DARK to "dark", ThemePreference.LIGHT to "light")) {
            runDesktopComposeUiTest(720, 440) {
                setContent {
                    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(2f)) {
                        TeamCodeTheme(theme) {
                            Box(Modifier.background(TeamCode.colors.background).padding(16.dp)) {
                                Box(Modifier.width(328.dp)) { TranscriptItemView(TranscriptItem.Agent(TranscriptEvent.AgentMessage(1, 0, text, null)), memberId = null) }
                            }
                        }
                    }
                }
                waitForIdle()
                fun shot(name: String) { waitForIdle(); ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output, "$name.png")) }
                shot("code-copy-$suffix-1")
                onNodeWithTag("code-copy").performClick()
                waitForIdle()
                shot("code-copy-$suffix-2-copied")
            }
        }
    }
}
