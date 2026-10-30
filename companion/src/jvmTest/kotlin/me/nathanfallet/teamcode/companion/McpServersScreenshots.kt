package me.nathanfallet.teamcode.companion

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import java.io.File
import java.util.Locale
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.McpServerRepository
import org.koin.dsl.module
import kotlin.test.Test

/**
 * The MCP servers screen reached through the real app (Account, then MCP servers), on demo data. Opt-in: runs only when
 * TEAMCODE_SCREENSHOTS names an output folder. `TEAMCODE_SCREENSHOTS=/tmp/shots ./gradlew :companion:jvmTest --tests '*McpServersScreenshots*'`
 */
@OptIn(ExperimentalTestApi::class)
class McpServersScreenshots {
    private val scenes = DesktopScreenshots()
    private val mcp = module {
        single<FeaturesRepository> { FakeFeatures(setOf("cloud.sessions.v1", "cloud.transcript.v1", "cloud.messages.v1", "cloud.events.v1", "cloud.permissions.v1", "artifacts.v1", "mcp.gateway.v1")) }
        single<McpServerRepository> { FakeMcpServerRepository() }
    }

    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        capture(output, "phone-390", 390 to 844, ThemePreference.DARK, "dark", withAccount = true)
        capture(output, "phone-390", 390 to 844, ThemePreference.LIGHT, "light")
        capture(output, "desktop-1200", 1200 to 800, ThemePreference.DARK, "dark")
        capture(output, "desktop-1200", 1200 to 800, ThemePreference.LIGHT, "light")
        capture(output, "phone-390", 390 to 844, ThemePreference.DARK, "fr-dark")
    }

    private fun capture(output: File, size: String, window: Pair<Int, Int>, theme: ThemePreference, suffix: String, withAccount: Boolean = false) {
        val previous = Locale.getDefault()
        if (suffix.startsWith("fr")) Locale.setDefault(Locale.FRENCH) else Locale.setDefault(Locale.ENGLISH)
        try {
            with(scenes) {
                scene(window.first, window.second, theme, listOf(mcp)) {
                    // A phone has the Account tab; a wide window has Settings in its sidebar.
                    val entry = if (onAllNodesWithTag("tab-account").fetchSemanticsNodes().isNotEmpty()) "tab-account" else "sidebar-settings"
                    onAllNodesWithTag(entry).onFirst().performSemanticsAction(SemanticsActions.OnClick)
                    mainClock.advanceTimeBy(1_000); waitForIdle()
                    if (withAccount) shot(output, "mcp-servers-$size-$suffix-1-account")
                    onAllNodesWithTag("mcp-servers").onFirst().performSemanticsAction(SemanticsActions.OnClick)
                    mainClock.advanceTimeBy(1_000); waitForIdle()
                    shot(output, "mcp-servers-$size-$suffix")
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
