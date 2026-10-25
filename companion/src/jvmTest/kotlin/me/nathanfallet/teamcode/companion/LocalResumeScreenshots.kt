package me.nathanfallet.teamcode.companion

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import java.io.File
import me.nathanfallet.teamcode.companion.domain.models.ResumableSession
import me.nathanfallet.teamcode.companion.domain.models.ResumableSessions
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.services.LocalAvailability
import me.nathanfallet.teamcode.companion.domain.services.LocalHost
import me.nathanfallet.teamcode.companion.domain.services.LocalHostException
import me.nathanfallet.teamcode.companion.domain.services.LocalResumables
import me.nathanfallet.teamcode.companion.domain.services.LocalResumeBlock
import me.nathanfallet.teamcode.companion.domain.services.LocalSessionRequest
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommand
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommands
import org.koin.dsl.module
import kotlin.test.Test

/**
 * Captures of "Resume a session" in New session on this device (the desktop), on invented folders and conversations:
 * closed, open with its list, one chosen, a folder without conversations, a CLI that did not answer, and TeamCode's
 * service stopped. Opt-in like [DesktopScreenshots]:
 * `TEAMCODE_SCREENSHOTS=/tmp/shots ./gradlew :companion:jvmTest --tests '*LocalResumeScreenshots*'`
 */
@OptIn(ExperimentalTestApi::class)
class LocalResumeScreenshots {
    private val sizes = listOf("desktop-1200" to (1200 to 800), "desktop-560" to (560 to 860))
    private val hour = 3_600_000L
    private val demo = listOf(
        ResumableSession("0000000a-aaaa-4bbb-8ccc-00000000000a", "Fix the flaky login test", DEMO_NOW - 2 * hour, 6),
        ResumableSession("0000000b-aaaa-4bbb-8ccc-00000000000b", "Release checklist for 1.4", DEMO_NOW - 26 * hour, 14),
        ResumableSession("0000000c-aaaa-4bbb-8ccc-00000000000c", "Add dark mode to the settings screen", DEMO_NOW - 4 * 24 * hour, 1),
        ResumableSession("0000000d-aaaa-4bbb-8ccc-00000000000d", "/review 42", DEMO_NOW - 9 * 24 * hour, 3),
    )

    private class Host(var folder: String, var list: (String) -> LocalResumables) : LocalHost {
        override suspend fun availability(accountId: String) = LocalAvailability.Ready
        override suspend fun pickDirectory() = folder
        override suspend fun start(accountId: String, request: LocalSessionRequest) = "s1"
        override suspend fun resumable(accountId: String, directory: String, before: Long?) = list(directory)
    }

    private fun modules(host: Host) = listOf(module {
        single<FeaturesRepository> {
            FakeFeatures(setOf("cloud.sessions.v1", "cloud.transcript.v1", "cloud.messages.v1", "cloud.events.v1", "cloud.permissions.v1",
                "cloud.sessions.remote.v1", "artifacts.v1", Features.REMOTE_SESSIONS, Features.DEVICE_SESSIONS, Features.RESUME_SESSIONS))
        }
        single<LocalHost> { host }
    })

    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        val harness = DesktopScreenshots()
        // One scene per run: the app reads its LocalHost once per process, so a second scene would not offer "On this device".
        // TEAMCODE_SCREENSHOT_SIZE picks the size (the first by default).
        val (name, size) = sizes.firstOrNull { it.first == System.getenv("TEAMCODE_SCREENSHOT_SIZE") } ?: sizes.first()
        val list: (String) -> LocalResumables = {
            if (it.endsWith("/app")) LocalResumables.Page(ResumableSessions(demo, nextBefore = demo.last().lastActiveAt)) else LocalResumables.Page(ResumableSessions(emptyList()))
        }
        val host = Host("/Users/ada/git/app", list)
        with(harness) {
            scene(size.first, size.second, ThemePreference.DARK, modules(host)) {
                openHere()
                tap("local-choose-folder")
                scrollTo("resume")
                shot(output, "$name-1-closed")
                tap("resume-picker")
                repeat(40) { if (!exists("resume-more")) settle() }
                scrollTo("resume-more")
                shot(output, "$name-2-open")
                tap("resume-${demo[1].id}")
                scrollTo("resume")
                shot(output, "$name-3-chosen")
                host.folder = "/Users/ada/git/web"
                tap("local-choose-folder")
                tap("resume-picker")
                scrollTo("resume-empty")
                shot(output, "$name-4-empty")
                // Another folder clears the list: the next opening asks the CLI again, which now fails, then says the service is off.
                host.list = { throw LocalHostException("The TeamCode CLI did not answer in time.") }
                host.folder = "/Users/ada/git/app"
                tap("local-choose-folder")
                tap("resume-picker")
                scrollTo("resume-retry")
                shot(output, "$name-5-error")
                host.list = { LocalResumables.Unavailable(LocalResumeBlock.DAEMON_OFFLINE) }
                tap("resume-retry")
                scrollTo("resume-check-again")
                shot(output, "$name-6-service-stopped")
            }
        }
    }

    private fun DesktopComposeUiTest.openHere() {
        // The account may still be loading when the scene starts: the command opens nothing until it is.
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
        try { node.performScrollTo() } catch (_: AssertionError) {} // Outside a scrolling column.
        node.performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private fun DesktopComposeUiTest.settle() {
        Thread.sleep(50)
        mainClock.advanceTimeBy(500)
        waitForIdle()
    }
}
