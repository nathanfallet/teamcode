package me.nathanfallet.teamcode.companion

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import java.io.File
import me.nathanfallet.teamcode.companion.demo.DemoContent
import me.nathanfallet.teamcode.companion.domain.models.*
import me.nathanfallet.teamcode.companion.domain.repositories.AutomationRepository
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.repositories.WorkflowRepository
import org.koin.dsl.module
import kotlin.test.Test

/**
 * The automation screens with a workflow action, through the real app: the page (inputs, runs with "Open the run"), and the
 * editor with its workflow choice and the inputs form. Opt-in: runs only when TEAMCODE_SCREENSHOTS names an output folder.
 * `TEAMCODE_SCREENSHOTS=/tmp/shots ./gradlew :companion:jvmTest --tests '*AutomationWorkflowScreenshots*'`
 */
@OptIn(ExperimentalTestApi::class)
class AutomationWorkflowScreenshots {
    private val scenes = DesktopScreenshots()

    private fun automations() = module {
        single<FeaturesRepository> { FakeFeatures(setOf("cloud.sessions.v1", "cloud.transcript.v1", "cloud.messages.v1", "cloud.events.v1", "artifacts.v1", "workflows.v1", "automations.v1")) }
        single<SessionRepository> { demoSessions().apply { profileList = DemoContent.profiles } }
        single<WorkflowRepository> { FakeWorkflowRepository().also { it.definitions = DemoContent.workflows(DEMO_NOW) } }
        single<AutomationRepository> {
            val definition = AutomationDefinition(
                "Triage new tickets", AutomationTrigger(TriggerKind.WEBHOOK),
                AutomationAction(ActionKind.WORKFLOW, null, workflowId = "wf-ticket", inputs = mapOf("ticket" to "{{ticket.number}}", "notes" to "Priority {{ticket.priority}}.")),
                "", variables = listOf(AutomationVariable("ticket.number", "$.ticket.number", "string"), AutomationVariable("ticket.priority", "$.ticket.priority", "string")),
            )
            FakeAutomationRepository().apply {
                current = automation(definition = definition, enabled = true)
                summaries = listOf(summary("au1", "Triage new tickets", trigger = definition.trigger).copy(actionKind = ActionKind.WORKFLOW))
                runs = listOf(run("r1", RunSource.WEBHOOK, "running", sessionId = null, workflowRunId = "run1"), run("r2", RunSource.WEBHOOK, "failed", sessionId = null, reason = "run_limit"))
            }
        }
    }

    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        for ((size, window) in listOf("phone" to (390 to 844), "desktop" to (1200 to 800))) for ((theme, name) in listOf(ThemePreference.DARK to "dark", ThemePreference.LIGHT to "light")) {
            val prefix = "$size-$name"
            with(scenes) {
                scene(window.first, window.second, theme, listOf(automations())) {
                    var waited = 0
                    fun present(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
                    while (!present("tab-account") && !present("sidebar-automation-au1") && waited++ < 20) { Thread.sleep(100); mainClock.advanceTimeBy(250); waitForIdle() }
                    if (present("tab-account")) { tap("tab-account"); tap("automations-row"); shot(output, "$prefix-1-list"); tap("automation-au1") } else tap("sidebar-automation-au1")
                    shot(output, "$prefix-2-page")
                    scrollTo("run-workflow-r1")
                    shot(output, "$prefix-3-runs")
                    tap("automation-edit")
                    scrollTo("automation-action-workflow")
                    shot(output, "$prefix-4-editor-action")
                    scrollTo("automation-input-ticket")
                    shot(output, "$prefix-5-editor-inputs")
                }
            }
        }
    }

    private fun DesktopComposeUiTest.scrollTo(tag: String) {
        var waited = 0
        while (onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() && waited++ < 20) { Thread.sleep(100); mainClock.advanceTimeBy(250); waitForIdle() }
        onAllNodesWithTag(tag).onFirst().performScrollTo()
        mainClock.advanceTimeBy(500); waitForIdle()
    }

    private fun DesktopComposeUiTest.tap(tag: String) {
        var waited = 0
        while (onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() && waited++ < 20) { Thread.sleep(100); mainClock.advanceTimeBy(250); waitForIdle() }
        onAllNodesWithTag(tag).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        mainClock.advanceTimeBy(1_000); waitForIdle()
    }
}
