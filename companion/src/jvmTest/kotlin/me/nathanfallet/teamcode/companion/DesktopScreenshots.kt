package me.nathanfallet.teamcode.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommand
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommands
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.companion.di.appModules
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.ArtifactPage
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.ArtifactRepository
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository
import me.nathanfallet.teamcode.companion.domain.repositories.DeviceRepository
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.PreferencesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionFileRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SubscriptionAccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TransferRepository
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import me.nathanfallet.teamcode.companion.domain.services.InstallationIdentity
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import me.nathanfallet.teamcode.companion.domain.services.SecureRandom
import me.nathanfallet.teamcode.companion.infrastructure.system.JavaSecureRandom
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test

/**
 * Captures of the whole app (App(), real screens and navigation) on demo data at several window sizes, for the pull
 * request's before/after. Opt-in: runs only when TEAMCODE_SCREENSHOTS names an output folder, so CI skips it.
 * `TEAMCODE_SCREENSHOTS=/tmp/shots ./gradlew :companion:jvmTest --tests '*DesktopScreenshots*'`
 */
@OptIn(ExperimentalTestApi::class)
class DesktopScreenshots {
    private val sizes = listOf(
        "phone-390" to (390 to 844),
        "tablet-split-600" to (600 to 900),
        "tablet-portrait-820" to (820 to 1180),
        "desktop-800" to (800 to 700),
        "desktop-1200" to (1200 to 800),
        "desktop-1600" to (1600 to 1000),
    )

    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        val only = System.getenv("TEAMCODE_SCREENSHOTS_ONLY")?.split(',')?.toSet()
        for ((name, size) in sizes) {
            if (only != null && name !in only) continue
            for (theme in listOf(ThemePreference.DARK, ThemePreference.LIGHT)) {
                if (theme == ThemePreference.LIGHT && name != "desktop-1200") continue
                val suffix = if (theme == ThemePreference.LIGHT) "-light" else ""
                scene(size.first, size.second, theme) {
                    shot(output, "$name$suffix-1-home")
                    clickText("Fix the flaky login test")
                    mainClock.advanceTimeBy(1_000); waitForIdle()
                    shot(output, "$name$suffix-2-session")
                    // The full artifact list: "See more" in the sidebar, the Artifacts tab on a phone (before: the tab).
                    val more = onAllNodesWithTag("sidebar-all-artifacts").fetchSemanticsNodes().isNotEmpty()
                    if (more) onAllNodesWithTag("sidebar-all-artifacts").onFirst().performSemanticsAction(SemanticsActions.OnClick)
                    else if (onAllNodesWithTag("back").fetchSemanticsNodes().isNotEmpty()) {
                        onAllNodesWithTag("back").onFirst().performSemanticsAction(SemanticsActions.OnClick)
                        mainClock.advanceTimeBy(1_000); waitForIdle()
                        onAllNodesWithTag("tab-artifacts").onFirst().performSemanticsAction(SemanticsActions.OnClick)
                    }
                    mainClock.advanceTimeBy(1_000); waitForIdle()
                    shot(output, "$name$suffix-3-artifacts")
                    runOnUiThread { AppCommands.send(AppCommand.NEW_SESSION) }
                    mainClock.advanceTimeBy(1_000); waitForIdle()
                    shot(output, "$name$suffix-4-new-session")
                }
                // The desktop's own: a right-click menu on a card, and the command palette.
                if (name == "desktop-1200" && theme == ThemePreference.DARK) {
                    scene(size.first, size.second, theme) {
                        onAllNodesWithTag("sidebar-all-artifacts").onFirst().performSemanticsAction(SemanticsActions.OnClick)
                        mainClock.advanceTimeBy(1_000); waitForIdle()
                        onAllNodesWithTag("artifact-r1").onFirst().performMouseInput { rightClick(Offset(260f, 40f)) }
                        mainClock.advanceTimeBy(1_000); waitForIdle()
                        shot(output, "$name-5-card-menu")
                    }
                    scene(size.first, size.second, theme) {
                        runOnUiThread { AppCommands.send(AppCommand.COMMAND_PALETTE) }
                        mainClock.advanceTimeBy(1_000); waitForIdle()
                        shot(output, "$name-6-palette")
                        onAllNodesWithTag("palette-field").onFirst().performTextInput("ci")
                        mainClock.advanceTimeBy(1_000); waitForIdle()
                        shot(output, "$name-7-palette-search")
                    }
                }
            }
        }
    }

    internal fun scene(width: Int, height: Int, theme: ThemePreference, extra: List<org.koin.core.module.Module> = emptyList(), block: DesktopComposeUiTest.() -> Unit) {
        val koin = startKoin { modules(demoModules() + extra) }.koin
        koin.get<PreferencesRepository>().setTheme(theme)
        try {
            // Rendered at 2x: the window is twice the size in pixels and the content sees a density of 2.
            runDesktopComposeUiTest(width * 2, height * 2) {
                val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
                setContent { CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalDensity provides Density(2f)) { App() } }
                mainClock.advanceTimeBy(1_000); waitForIdle()
                try { block() } catch (error: Throwable) { error.printStackTrace(); throw error }
                // Back to the first screen before the scene closes: a pushed entry still entering fails the teardown.
                mainClock.advanceTimeBy(2_000)
                waitForIdle()
            }
        } finally {
            stopKoin()
        }
    }

    private fun DesktopComposeUiTest.clickText(text: String): SemanticsNodeInteraction =
        onAllNodesWithText(text).onFirst().performSemanticsAction(SemanticsActions.OnClick)

    internal fun DesktopComposeUiTest.shot(output: File, name: String) {
        waitForIdle()
        // Every root, the window's and those of dialogs and menus over it, drawn where it sits.
        val roots = onAllNodes(isRoot())
        val count = roots.fetchSemanticsNodes().size
        val base = roots[0].captureToImage().toAwtImage()
        val image = java.awt.image.BufferedImage(base.width, base.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val canvas = image.createGraphics()
        for (index in 0 until count) {
            val node = roots[index]
            val bounds = node.fetchSemanticsNode().boundsInWindow
            canvas.drawImage(node.captureToImage().toAwtImage(), bounds.left.toInt(), bounds.top.toInt(), null)
        }
        canvas.dispose()
        ImageIO.write(image, "png", File(output, "$name.png"))
    }

}

