package me.nathanfallet.teamcode.companion

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onRoot
import java.io.File
import me.nathanfallet.teamcode.companion.demo.DemoContent
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.models.WaitPage
import me.nathanfallet.teamcode.companion.domain.models.WaitStatus
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository
import me.nathanfallet.teamcode.companion.domain.repositories.WorkflowRepository
import org.koin.dsl.module
import kotlin.test.Test

/**
 * The workflow screens reached through the real app on demo data: the runs (one in each state), the saved workflows, a
 * workflow, its start form, and the pages of a waiting and a failed run. Opt-in: runs only when TEAMCODE_SCREENSHOTS names
 * an output folder. `TEAMCODE_SCREENSHOTS=/tmp/shots ./gradlew :companion:jvmTest --tests '*WorkflowsScreenshots*'`
 */
@OptIn(ExperimentalTestApi::class)
class WorkflowsScreenshots {
    private val scenes = DesktopScreenshots()

    // The demo's workflows, runs and waits ([DemoContent]) behind the real screens; the run in "Waiting" is blocked on the member's wait w1.
    private fun workflows() = module {
        single<FeaturesRepository> { FakeFeatures(setOf("cloud.sessions.v1", "cloud.transcript.v1", "cloud.messages.v1", "cloud.events.v1", "cloud.permissions.v1", "artifacts.v1", "waits.v1", "workflows.v1")) }
        single<SessionRepository> { demoSessions().apply { profileList = DemoContent.profiles } }
        single<WorkflowRepository> {
            FakeWorkflowRepository().also { repository ->
                repository.definitions = DemoContent.workflows(DEMO_NOW)
                DemoContent.workflowRuns(DEMO_NOW).forEach { repository.runs[it.id] = it }
            }
        }
        single<WaitRepository> {
            object : WaitRepository {
                override suspend fun list(accountId: String, status: WaitStatus, before: String?) = WaitPage(if (status == WaitStatus.OPEN) DemoContent.waits(DEMO_NOW) else emptyList(), null)
                override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, kotlinx.serialization.json.JsonElement>) = error("not used")
            }
        }
    }

    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        for ((size, window) in listOf("phone" to (390 to 844), "desktop" to (1200 to 800))) for ((theme, name) in listOf(ThemePreference.DARK to "dark", ThemePreference.LIGHT to "light")) {
            val prefix = "$size-$name"
            with(scenes) {
                scene(window.first, window.second, theme, listOf(workflows())) {
                    openWorkflows()
                    shot(output, "$prefix-1-runs")
                    tap("workflows-part-definitions")
                    shot(output, "$prefix-2-workflows")
                    tap("workflow-wf-ticket")
                    shot(output, "$prefix-3-workflow")
                    tap("workflow-start")
                    shot(output, "$prefix-4-start")
                    // Back to the runs (the Workflows tab again, or its sidebar row), then the two run pages.
                    back(); openWorkflows(); tap("workflows-part-runs")
                    tap("workflow-run-run2")
                    shot(output, "$prefix-5-run-waiting")
                    back(); openWorkflows(); tap("workflows-part-runs")
                    tap("workflow-run-run4")
                    shot(output, "$prefix-6-run-failed")
                }
            }
        }
    }

    /** A phone has the Workflows tab; a wide window has it in its sidebar. The server's capabilities are read a moment after the app opens. */
    private fun DesktopComposeUiTest.openWorkflows() {
        var waited = 0
        fun present(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        while (!present("tab-workflows") && !present("sidebar-workflows") && waited++ < 20) { Thread.sleep(100); mainClock.advanceTimeBy(250); waitForIdle() }
        tap(if (present("tab-workflows")) "tab-workflows" else "sidebar-workflows")
    }

    private fun DesktopComposeUiTest.back() {
        var steps = 0
        while (onAllNodesWithTag("back").fetchSemanticsNodes().isNotEmpty() && steps++ < 8) tap("back")
    }

    private fun DesktopComposeUiTest.tap(tag: String) {
        // The lists load a moment after the screen opens.
        var waited = 0
        while (onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() && waited++ < 20) { Thread.sleep(100); mainClock.advanceTimeBy(250); waitForIdle() }
        onAllNodesWithTag(tag).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        mainClock.advanceTimeBy(1_000); waitForIdle()
    }
}
