package me.nathanfallet.teamcode.companion

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import java.io.File
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.DeviceFolder
import me.nathanfallet.teamcode.companion.domain.models.DeviceFolders
import me.nathanfallet.teamcode.companion.domain.models.ResumableSession
import me.nathanfallet.teamcode.companion.domain.models.ResumableSessions
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.repositories.DeviceRepository
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommand
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommands
import org.koin.dsl.module
import kotlin.test.Test

/**
 * Captures of the "Resume a session" picker of New session on an online device, on invented conversations: closed,
 * open with its list, a folder without conversations, a device that did not answer, and an older daemon. Opt-in like
 * [DesktopScreenshots]: `TEAMCODE_SCREENSHOTS=/tmp/shots ./gradlew :companion:jvmTest --tests '*ResumeSessionScreenshots*'`
 */
@OptIn(ExperimentalTestApi::class)
class ResumeSessionScreenshots {
    private val sizes = listOf("phone-390" to (390 to 844), "desktop-1200" to (1200 to 800))
    private val hour = 3_600_000L
    private val demo = listOf(
        ResumableSession("0000000a-aaaa-4bbb-8ccc-00000000000a", "Fix the flaky login test", DEMO_NOW - 2 * hour, 6),
        ResumableSession("0000000b-aaaa-4bbb-8ccc-00000000000b", "Release checklist for 1.4", DEMO_NOW - 26 * hour, 14),
        ResumableSession("0000000c-aaaa-4bbb-8ccc-00000000000c", "Add dark mode to the settings screen", DEMO_NOW - 4 * 24 * hour, 1),
        ResumableSession("0000000d-aaaa-4bbb-8ccc-00000000000d", "/review 42", DEMO_NOW - 9 * 24 * hour, 3),
    )

    private fun modules(folders: DeviceFolders, list: (String) -> ResumableSessions) = listOf(module {
        single<FeaturesRepository> {
            FakeFeatures(setOf("cloud.sessions.v1", "cloud.transcript.v1", "cloud.messages.v1", "cloud.events.v1", "cloud.permissions.v1",
                "cloud.sessions.remote.v1", "artifacts.v1", Features.DEVICE_SESSIONS, Features.RESUME_SESSIONS))
        }
        single<DeviceRepository> {
            FakeDeviceRepository().apply {
                devicesResult = { listOf(hostDevice("d1", "Ada's MacBook Pro", current = true)) }
                foldersResult = { folders }
                resumableResult = { _, folder, _ -> list(folder) }
            }
        }
    })

    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        val app = DeviceFolder("f1", "app", "~/git/app")
        val web = DeviceFolder("f2", "web", "~/git/web")
        val harness = DesktopScreenshots()
        for ((name, size) in sizes) with(harness) {
            scene(size.first, size.second, ThemePreference.DARK, modules(DeviceFolders(true, listOf(app, web), resumes = true)) {
                if (it == app.id) ResumableSessions(demo, nextBefore = demo.last().lastActiveAt) else ResumableSessions(emptyList())
            }) {
                openOnDevice()
                tap("folder-f1")
                shot(output, "$name-1-closed")
                tap("resume-picker")
                // The whole list in view, down to "Show older sessions".
                repeat(40) { if (!exists("resume-more")) settle() }
                try { onAllNodesWithTag("resume-more").onFirst().performScrollTo() } catch (_: AssertionError) {}
                settle()
                shot(output, "$name-2-open")
                tap("resume-${demo[1].id}")
                shot(output, "$name-3-chosen")
                tap("folder-f2")
                tap("resume-picker")
                shot(output, "$name-4-empty")
            }
            scene(size.first, size.second, ThemePreference.DARK, modules(DeviceFolders(true, listOf(app), resumes = true)) {
                throw CompanionException.DeviceRefused("device_no_answer")
            }) {
                openOnDevice()
                tap("resume-picker")
                shot(output, "$name-5-error")
            }
            scene(size.first, size.second, ThemePreference.DARK, modules(DeviceFolders(true, listOf(app), resumes = false)) { ResumableSessions(emptyList()) }) {
                openOnDevice()
                if (exists("resume-old-daemon")) onAllNodesWithTag("resume-old-daemon").onFirst().performScrollTo()
                settle()
                shot(output, "$name-6-old-daemon")
            }
        }
    }

    private fun DesktopComposeUiTest.openOnDevice() {
        runOnUiThread { AppCommands.send(AppCommand.NEW_SESSION) }
        settle()
        tap("target-device")
    }

    private fun DesktopComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

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
