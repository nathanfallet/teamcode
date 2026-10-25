package me.nathanfallet.teamcode.companion

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import java.io.File
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.repositories.ArtifactRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import org.koin.dsl.module
import kotlin.test.Test

/**
 * Captures of an artifact link clicked in a conversation, on demo data, on a phone and a desktop window, dark and light:
 * the conversation, the artifact over it, and an artifact the member cannot read. Opt-in like [DesktopScreenshots]:
 * `TEAMCODE_SCREENSHOTS=/tmp/shots`.
 */
@OptIn(ExperimentalTestApi::class)
class ArtifactLinkOverlayScreenshots {
    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        val links = ArtifactLinkOverlayTest()
        val extra = module {
            single<ArtifactRepository> { links.artifacts() }
            single<TranscriptRepository> { links.linkTranscript() }
        }
        val harness = DesktopScreenshots()
        for ((theme, name) in listOf(ThemePreference.DARK to "dark", ThemePreference.LIGHT to "light")) for ((width, height, size) in listOf(Triple(390, 844, "phone"), Triple(1200, 800, "desktop"))) with(harness) {
            scene(width, height, theme, listOf(extra)) {
                onAllNodesWithText("Fix the flaky login test").onFirst().performSemanticsAction(SemanticsActions.OnClick)
                settle()
                shot(output, "$size-$name-1-conversation")
                clickLink("Read the latency report")
                shot(output, "$size-$name-2-overlay")
                onNodeWithTag("artifact-close").performClick()
                settle()
                clickLink("Hidden plan")
                shot(output, "$size-$name-3-no-access")
                onNodeWithTag("artifact-close").performClick()
                settle()
            }
        }
    }

    private fun DesktopComposeUiTest.clickLink(label: String) {
        onAllNodesWithText(label, substring = true).onFirst().performMouseInput { click(Offset(16f, centerY)) }
        settle()
    }

    private fun DesktopComposeUiTest.settle() {
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }
}
