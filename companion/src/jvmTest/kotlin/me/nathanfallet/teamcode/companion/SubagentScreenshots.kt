package me.nathanfallet.teamcode.companion

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import me.nathanfallet.teamcode.companion.domain.models.BackgroundTask
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import org.koin.dsl.module
import kotlin.test.Test

/**
 * Captures of the subagents and background tasks panel at every size of its layout, on demo data: a session running
 * three subagents (done, failed, running), a background subagent, a background command, a monitor and a scheduled
 * wake-up. Opt-in like [DesktopScreenshots]: `TEAMCODE_SCREENSHOTS=/tmp/shots ./gradlew :companion:jvmTest --tests '*SubagentScreenshots*'`
 */
@OptIn(ExperimentalTestApi::class)
class SubagentScreenshots {
    private val sizes = listOf(
        "phone-390" to (390 to 844),
        "tablet-split-600" to (600 to 900),
        "tablet-portrait-820" to (820 to 1180),
        "desktop-1200" to (1200 to 800),
        "desktop-1600" to (1600 to 1000),
    )

    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        val only = System.getenv("TEAMCODE_SCREENSHOTS_ONLY")?.split(',')?.toSet()
        val shots = DesktopScreenshots()
        val busy = listOf(module { single<TranscriptRepository> { subagentTranscript() } })
        for ((name, size) in sizes) {
            if (only != null && name !in only) continue
            with(shots) {
                // Nothing runs in the demo's usual session: no badge, no panel.
                if (name == "desktop-1200" || name == "phone-390") scene(size.first, size.second, ThemePreference.DARK) {
                    openSession()
                    shot(output, "$name-0-no-subagents")
                }
                scene(size.first, size.second, ThemePreference.DARK, busy) {
                    openSession()
                    shot(output, "$name-1-session")
                    if (exists("subagent-panel").not()) tap("running-badge")
                    shot(output, "$name-2-panel")
                    tap("subagent-a3")
                    shot(output, "$name-3-thread")
                    if (exists("thread-back")) tap("thread-back") else if (exists("thread-close")) tap("thread-close")
                    if (exists("subagent-panel").not() && exists("running-badge")) tap("running-badge")
                    if (exists("stop-t-bash")) {
                        tap("stop-t-bash")
                        shot(output, "$name-4-stop")
                    }
                }
            }
        }
    }

    private fun DesktopComposeUiTest.openSession() {
        onAllNodesWithText("Fix the flaky login test").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        mainClock.advanceTimeBy(1_000); waitForIdle()
    }

    private fun DesktopComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun DesktopComposeUiTest.tap(tag: String) {
        onAllNodesWithTag(tag).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        mainClock.advanceTimeBy(1_000); waitForIdle()
    }
}

/** A session busy with subagents and background work, all invented: no real data. */
internal fun subagentTranscript(withTasks: Boolean = true) = object : TranscriptRepository {
    override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage {
        val at = DEMO_NOW - 600_000
        fun agent(seq: Long, id: String, type: String, description: String, background: Boolean = false, parent: String? = null) =
            TranscriptEvent.ToolCall(seq, at + seq * 4_000, id, "Agent",
                "{\n  \"description\": \"$description\",\n  \"prompt\": \"$description in acme/web.\",\n  \"subagent_type\": \"$type\"${if (background) ",\n  \"run_in_background\": true" else ""}\n}",
                description, parent)
        fun bash(seq: Long, id: String, command: String, parent: String? = null, background: Boolean = false) =
            TranscriptEvent.ToolCall(seq, at + seq * 4_000, id, "Bash", "{\n  \"command\": \"$command\"${if (background) ",\n  \"run_in_background\": true" else ""}\n}", command, parent)
        fun result(seq: Long, id: String, text: String, parent: String? = null, error: Boolean = false) =
            TranscriptEvent.ToolResult(seq, at + seq * 4_000, id, text, error, null, parent)
        fun says(seq: Long, text: String, parent: String? = null) = TranscriptEvent.AgentMessage(seq, at + seq * 4_000, text, parent)
        val events = mutableListOf(
            TranscriptEvent.Turn(1, at, TurnPhase.entries.first { it.wire == "started" }, null, null),
            TranscriptEvent.UserMessage(2, at + 1_000, "The login test fails about once in ten runs on CI. Find out why, check the other auth tests, and keep the build green.", "in-1", "m1"),
            says(3, "I'll split this: one subagent maps the auth code, one reviews the other tests, one reproduces the failure."),
            agent(4, "a1", "Explore", "Map the auth module"),
            bash(5, "c1", "rg -l session web/src", parent = "a1"), result(6, "c1", "web/src/auth/Session.kt\nweb/src/auth/Login.kt", parent = "a1"),
            says(7, "The session cookie is set in Login.kt after the redirect.", parent = "a1"),
            result(8, "a1", "Auth lives in web/src/auth: Session.kt writes the cookie after the redirect."),
            agent(9, "a2", "reviewer", "Review the other auth tests"),
            bash(10, "c2", "./gradlew :web:test --tests '*Auth*'", parent = "a2"), result(11, "c2", "2 tests failed", parent = "a2", error = true),
            result(12, "a2", "The review stopped: the test run failed.", error = true),
            agent(13, "a3", "general-purpose", "Reproduce the flaky failure"),
            says(14, "Running the test 50 times to catch the failure.", parent = "a3"),
            bash(15, "c3", "for i in $(seq 50); do ./gradlew :web:test --tests LoginTest -q; done", parent = "a3"),
            agent(16, "b1", "Explore", "Watch the CI history", background = true),
            result(17, "b1", "Launched in the background."),
            bash(18, "c4", "./gradlew :web:jvmTest --continuous", background = true),
            result(19, "c4", "Running in the background."),
            TranscriptEvent.ToolCall(20, at + 80_000, "c5", "Monitor", "{\n  \"description\": \"CI run 4521\"\n}", "until gh run view 4521 --json status | grep -q completed; do sleep 30; done", null),
            says(21, "Two subagents and a build are still running; I'll report when they finish."),
        )
        if (withTasks) events += TranscriptEvent.Tasks(22, at + 90_000, listOf(
            BackgroundTask("t-agent", "local_agent", "Watch the CI history", "Explore", "b1", at + 64_000),
            BackgroundTask("t-bash", "local_bash", "Continuous web tests", null, "c4", at + 72_000),
            BackgroundTask("t-mon", "local_bash", "CI run 4521", null, "c5", at + 80_000),
        ), stoppable = true, scheduled = 1)
        return TranscriptPage(events, null, events.size.toLong())
    }
    override fun follow(accountId: String, sessionId: String, after: Long) = flow<LiveUpdate<TranscriptEvent>> { emit(LiveUpdate.Opened(false)); awaitCancellation() }
}
