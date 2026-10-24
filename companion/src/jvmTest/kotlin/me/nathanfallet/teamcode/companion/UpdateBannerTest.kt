package me.nathanfallet.teamcode.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import me.nathanfallet.teamcode.companion.di.updateModule
import me.nathanfallet.teamcode.companion.domain.services.AppUpdater
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.presentation.components.UpdateBanner
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import kotlin.test.assertEquals

/** The update banner on the desktop target: shown only where an [AppUpdater] is bound, whatever the window's size. */
@OptIn(ExperimentalTestApi::class)
class UpdateBannerTest {
    private class Updater : AppUpdater {
        override val appVersion = "1.2.0"
        var updates = 0
        override suspend fun cliVersion() = "1.2.0"
        override suspend fun latestVersion() = "1.3.0"
        override suspend fun update(output: (String) -> Unit) { updates++; output("Updating the TeamCode CLI 1.2.0…"); kotlinx.coroutines.awaitCancellation() }
        override fun quit() = Unit
    }

    private fun scene(updater: AppUpdater?, width: Int, block: DesktopComposeUiTest.() -> Unit) {
        startKoin {
            modules(updateModule, module {
                single<Settings> { MapSettings() }
                single<Clock> { Clock { 1_000_000_000L } }
                updater?.let { value -> single<AppUpdater> { value } }
            })
        }
        try {
            runDesktopComposeUiTest(width, 800) {
                val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
                setContent { CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { TeamCodeTheme { UpdateBanner() } } }
                waitForIdle()
                try { block() } finally { runOnUiThread { owner.viewModelStore.clear() } }
            }
        } finally {
            stopKoin()
        }
    }

    /** The view model works on the app's main dispatcher, which waitUntil would block: let it run between frames. */
    private fun DesktopComposeUiTest.awaitShown(condition: () -> Boolean) {
        repeat(10) {
            if (condition()) return
            Thread.sleep(500); mainClock.advanceTimeBy(1_000); waitForIdle()
        }
        error("Condition still not satisfied: " + onRoot(useUnmergedTree = true).printToString())
    }

    @Test fun withoutADesktopUpdaterNothingShowsAtAnyWidth() {
        // The mobile apps bind no AppUpdater: no banner on a phone, nor on an iPad's wide or split layout.
        for (width in listOf(390, 820, 1366)) scene(null, width) {
            Thread.sleep(300); mainClock.advanceTimeBy(300); waitForIdle()
            assertEquals(0, onAllNodesWithTag("update-banner").fetchSemanticsNodes().size)
        }
    }

    @Test fun theDesktopShowsTheBannerAndUpdateStartsTheUpdate() {
        val updater = Updater()
        scene(updater, 1200) {
            awaitShown { onAllNodesWithTag("update-banner").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Update available: TeamCode 1.3.0").assertExists()
            onNodeWithTag("update-banner-update").performClick()
            awaitShown { onAllNodesWithText("Updating TeamCode to 1.3.0…").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, updater.updates)
        }
    }

    @Test fun notNowHidesTheBanner() {
        scene(Updater(), 1200) {
            awaitShown { onAllNodesWithTag("update-banner").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("update-banner-dismiss").performClick()
            waitForIdle()
            assertEquals(0, onAllNodesWithTag("update-banner").fetchSemanticsNodes().size)
        }
    }
}
