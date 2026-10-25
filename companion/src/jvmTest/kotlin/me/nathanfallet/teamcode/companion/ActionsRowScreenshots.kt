package me.nathanfallet.teamcode.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptItem
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptItemView
import kotlin.test.Test

/** Captures of a folded "N actions" header and its unfolded tool rows, demo data. Opt-in: `TEAMCODE_SCREENSHOTS=/tmp/shots`. */
@OptIn(ExperimentalTestApi::class)
class ActionsRowScreenshots {
    private fun tool(seq: Long, name: String, summary: String?) = TranscriptItem.Tool(
        TranscriptEvent.ToolCall(seq, 0, "t$seq", name, "{}", summary, null),
        TranscriptEvent.ToolResult(seq + 1, 0, "t$seq", "ok", false, 0, null),
    )

    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        val group = TranscriptItem.Tools(listOf(tool(1, "Bash", "ls -la"), tool(3, "Bash", null), tool(5, "Read", "README.md")))
        val single = TranscriptItem.Tools(listOf(tool(11, "Bash", "git status"), tool(13, "Bash", "git diff")))
        for ((theme, suffix) in listOf(ThemePreference.DARK to "dark", ThemePreference.LIGHT to "light")) {
            for ((size, width: Dp) in listOf("phone" to 328.dp, "wide" to 760.dp)) {
                runDesktopComposeUiTest(((width.value + 32) * 2).toInt(), 1000) {
                    setContent {
                        CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(2f)) {
                            TeamCodeTheme(theme) {
                                Box(Modifier.background(TeamCode.colors.background).padding(16.dp)) {
                                    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TranscriptItemView(group, memberId = null)
                                        TranscriptItemView(single, memberId = null)
                                    }
                                }
                            }
                        }
                    }
                    waitForIdle()
                    fun shot(name: String) { waitForIdle(); ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output, "$name.png")) }
                    onNodeWithTag("tools-1").performClick()
                    onNodeWithTag("tool-1").performClick()
                    waitForIdle()
                    shot("actions-$size-$suffix")
                }
            }
        }
    }
}
