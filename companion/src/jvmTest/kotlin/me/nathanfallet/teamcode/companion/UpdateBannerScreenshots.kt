package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import java.io.File
import kotlinx.coroutines.awaitCancellation
import me.nathanfallet.teamcode.companion.di.updateModule
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.services.AppUpdateException
import me.nathanfallet.teamcode.companion.domain.services.AppUpdater
import org.koin.dsl.module
import kotlin.test.Test

/**
 * Captures of the update banner over the whole app on the demo data of [DesktopScreenshots]: available, updating and
 * failed. Opt-in like it: `TEAMCODE_SCREENSHOTS=/tmp/shots ./gradlew :companion:jvmTest --tests '*UpdateBannerScreenshots*'`
 */
@OptIn(ExperimentalTestApi::class)
class UpdateBannerScreenshots {
    private class DemoUpdater(private val fails: Boolean) : AppUpdater {
        override val appVersion = "1.2.0"
        override suspend fun cliVersion() = "1.2.0"
        override suspend fun latestVersion() = "1.3.0"
        override suspend fun update(output: (String) -> Unit) {
            output("Updating the TeamCode CLI 1.2.0…")
            output("Selected TeamCode 1.3.0. Launcher: ~/Library/Application Support/TeamCode/bin/teamcode")
            output("2 running agent sessions keep running on 1.2.0.")
            if (fails) throw AppUpdateException("Cannot reach GitHub's release servers (release_connection_failure). Check your network, VPN or proxy, then try again.")
            awaitCancellation()
        }
        override fun quit() = Unit
    }

    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        val harness = DesktopScreenshots()
        for ((theme, suffix) in listOf(ThemePreference.DARK to "", ThemePreference.LIGHT to "-light")) {
            for (fails in listOf(false, true)) with(harness) {
                scene(1200, 800, theme, listOf(updateModule, module { single<AppUpdater> { DemoUpdater(fails) } })) {
                    repeat(100) { if (onAllNodesWithTag("update-banner").fetchSemanticsNodes().isEmpty()) { Thread.sleep(50); mainClock.advanceTimeBy(50); waitForIdle() } }
                    if (!fails) shot(output, "update-banner$suffix-1-available")
                    onNodeWithTag("update-banner-update").performClick()
                    repeat(20) { Thread.sleep(50); mainClock.advanceTimeBy(50); waitForIdle() }
                    shot(output, "update-banner$suffix-${if (fails) "3-failed" else "2-updating"}")
                }
            }
        }
    }
}
