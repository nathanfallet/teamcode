package me.nathanfallet.teamcode.companion

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import java.io.File
import me.nathanfallet.teamcode.companion.demo.DemoContent
import me.nathanfallet.teamcode.companion.domain.models.EditorMode
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.models.WorkflowEditors
import me.nathanfallet.teamcode.companion.domain.models.WorkflowSettings
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.repositories.WorkflowRepository
import org.koin.dsl.module
import kotlin.test.Test

/**
 * The workflow definition editor reached through the real app on demo data: an empty new workflow, a filled agent step, a human
 * step with fields, a workflow step, the map of the flow with its loop, a validation error and the delete confirmation. Opt-in:
 * runs only when TEAMCODE_SCREENSHOTS names an output folder.
 * `TEAMCODE_SCREENSHOTS=/tmp/shots ./gradlew :companion:jvmTest --tests '*WorkflowEditorScreenshots*'`
 */
@OptIn(ExperimentalTestApi::class)
class WorkflowEditorScreenshots {
    private val scenes = DesktopScreenshots()

    private fun workflows() = module {
        single<FeaturesRepository> { FakeFeatures(setOf("cloud.sessions.v1", "cloud.transcript.v1", "cloud.messages.v1", "cloud.events.v1", "cloud.permissions.v1", "artifacts.v1", "waits.v1", "workflows.v1")) }
        single<SessionRepository> { demoSessions().apply { profileList = DemoContent.profiles } }
        single<WorkflowRepository> {
            FakeWorkflowRepository().also { repository ->
                repository.definitions = DemoContent.workflows(DEMO_NOW)
                DemoContent.definitions(DEMO_NOW).forEach { repository.saved[it.id] = it }
                repository.settings = WorkflowSettings(WorkflowEditors(EditorMode.ALL), canEdit = true)
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
                    tap("workflows-part-definitions")
                    // 1. A new workflow: one empty agent step, the refusals already shown against it.
                    tap("workflows-new")
                    shot(output, "$prefix-1-new")
                    back()
                    // 2. A filled agent step, 3. a human step with fields, 5. the map with its loop, 6. a validation error.
                    tap("workflow-wf-ticket"); tap("workflow-edit")
                    tap("wfe-n0-header"); scrollNearTop("wfe-n0-header", window.second)
                    shot(output, "$prefix-2-agent")
                    tap("wfe-n1-header"); scrollNearTop("wfe-n1-header", window.second)
                    shot(output, "$prefix-3-human")
                    scrollNearTop("wfe-map", window.second)
                    shot(output, "$prefix-5-map")
                    scrollTo("wfe-n0-instruction")
                    onAllNodesWithTag("wfe-n0-instruction").onFirst().performTextInput(" Then tell {{inputs.nobody}}.")
                    mainClock.advanceTimeBy(500); waitForIdle()
                    scrollNearTop("wfe-n0-header", window.second)
                    shot(output, "$prefix-6-validation")
                    back()
                    // 4. A step that runs another workflow.
                    back(); tap("workflow-wf-release"); tap("workflow-edit")
                    tap("wfe-n1-header"); scrollNearTop("wfe-n1-header", window.second)
                    shot(output, "$prefix-4-workflow")
                    back(); back()
                    // 7. The delete confirmation.
                    tap("workflow-wf-ticket"); scrollTo("workflow-delete"); tap("workflow-delete")
                    shot(output, "$prefix-7-delete")
                }
            }
        }
    }

    /** Brings [tag] near the top of the form (a plain scroll only makes it visible, at the bottom edge). */
    private fun DesktopComposeUiTest.scrollNearTop(tag: String, windowHeight: Int) {
        scrollTo(tag)
        onAllNodesWithTag("wfe-form").onFirst().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, windowHeight * 2 * 0.7f) }
        mainClock.advanceTimeBy(500); waitForIdle()
    }

    private fun DesktopComposeUiTest.scrollTo(tag: String) {
        var waited = 0
        while (onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() && waited++ < 20) { Thread.sleep(100); mainClock.advanceTimeBy(250); waitForIdle() }
        onAllNodesWithTag(tag).onFirst().performScrollTo()
        mainClock.advanceTimeBy(500); waitForIdle()
    }

    private fun DesktopComposeUiTest.openWorkflows() {
        var waited = 0
        fun present(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        while (!present("tab-workflows") && !present("sidebar-workflows") && waited++ < 20) { Thread.sleep(100); mainClock.advanceTimeBy(250); waitForIdle() }
        tap(if (present("tab-workflows")) "tab-workflows" else "sidebar-workflows")
    }

    private fun DesktopComposeUiTest.back() {
        var steps = 0
        if (onAllNodesWithTag("back").fetchSemanticsNodes().isNotEmpty() && steps++ < 1) tap("back")
    }

    private fun DesktopComposeUiTest.tap(tag: String) {
        var waited = 0
        while (onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() && waited++ < 20) { Thread.sleep(100); mainClock.advanceTimeBy(250); waitForIdle() }
        onAllNodesWithTag(tag).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        mainClock.advanceTimeBy(1_000); waitForIdle()
    }
}
