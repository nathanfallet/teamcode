package me.nathanfallet.teamcode.companion

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import java.io.File
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.services.KnownFolder
import me.nathanfallet.teamcode.companion.domain.services.LocalAvailability
import me.nathanfallet.teamcode.companion.domain.services.LocalHost
import me.nathanfallet.teamcode.companion.domain.services.LocalSessionRequest
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommand
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommands
import org.koin.dsl.module
import kotlin.test.Test

/**
 * Captures of "Known folders" in New session on this device (the desktop), on invented folders: closed, open with the
 * list, and one chosen. Opt-in like [LocalResumeScreenshots]:
 * `TEAMCODE_SCREENSHOTS=/tmp/shots ./gradlew :companion:jvmTest --tests '*LocalKnownFoldersScreenshots*'`
 */
@OptIn(ExperimentalTestApi::class)
class LocalKnownFoldersScreenshots {
    private val hour = 3_600_000L
    private val demo = listOf(
        KnownFolder("/Users/ada/git/paywall", DEMO_NOW - 2 * hour),
        KnownFolder("/Users/ada/git/web", DEMO_NOW - 30 * hour),
        KnownFolder("/Users/ada/git/api", DEMO_NOW - 5 * 24 * hour),
        KnownFolder("/Users/ada/git/infra"),
    )

    private class Host(val folders: List<KnownFolder>) : LocalHost {
        override suspend fun availability(accountId: String) = LocalAvailability.Ready
        override suspend fun pickDirectory(): String? = null
        override suspend fun start(accountId: String, request: LocalSessionRequest) = "s1"
        override suspend fun knownFolders(accountId: String) = folders
    }

    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        val harness = DesktopScreenshots()
        val modules = listOf(module {
            single<FeaturesRepository> {
                FakeFeatures(setOf("cloud.sessions.v1", "cloud.transcript.v1", "cloud.messages.v1", "cloud.events.v1", "cloud.permissions.v1",
                    "cloud.sessions.remote.v1", "artifacts.v1", Features.REMOTE_SESSIONS, Features.DEVICE_SESSIONS, Features.RESUME_SESSIONS))
            }
            single<LocalHost> { Host(demo) }
        })
        with(harness) {
            scene(560, 860, ThemePreference.DARK, modules) {
                openHere()
                scrollTo("known-folders")
                shot(output, "known-folders-1-closed")
                tap("known-folders-toggle")
                scrollTo("known-folder-3")
                shot(output, "known-folders-2-open")
                tap("known-folder-1")
                scrollTo("known-folders")
                shot(output, "known-folders-3-chosen")
            }
        }
    }

    private fun DesktopComposeUiTest.openHere() {
        repeat(20) {
            if (!exists("new-session-here")) { runOnUiThread { AppCommands.send(AppCommand.NEW_SESSION) }; settle() }
        }
        tap("new-session-here")
    }

    private fun DesktopComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun DesktopComposeUiTest.scrollTo(tag: String) {
        repeat(40) { if (!exists(tag)) settle() }
        try { onAllNodesWithTag(tag).onFirst().performScrollTo() } catch (_: AssertionError) {}
        settle()
    }

    private fun DesktopComposeUiTest.tap(tag: String) {
        repeat(40) { if (!exists(tag)) settle() }
        val node = onAllNodesWithTag(tag).onFirst()
        try { node.performScrollTo() } catch (_: AssertionError) {}
        node.performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private fun DesktopComposeUiTest.settle() {
        Thread.sleep(50)
        mainClock.advanceTimeBy(500)
        waitForIdle()
    }
}
