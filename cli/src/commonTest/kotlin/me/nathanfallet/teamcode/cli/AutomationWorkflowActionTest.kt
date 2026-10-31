package me.nathanfallet.teamcode.cli

import com.github.ajalt.clikt.core.UsageError
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.automations.*
import kotlin.test.*

/** `automations create|edit --workflow ID --input K=V`, and how a workflow action and its runs are printed. */
class AutomationWorkflowActionTest {
    private val workflow = "9a1c2d3e-0000-4000-8000-000000000042"
    private fun hook(inputs: Map<String, String>? = mapOf("ticket" to "T-{{ticket}}"), profileId: String? = null) =
        AutomationChanges(name = "Triage", webhook = true, profileId = profileId, workflowId = workflow, inputs = inputs)

    @Test fun `a workflow action carries the workflow its inputs and no prompt`() {
        val input = applyChanges(null, hook(profileId = "p1"))
        assertEquals(AutomationActionView("workflow", "p1", workflowId = workflow, inputs = mapOf("ticket" to "T-{{ticket}}")), input.action)
        assertEquals("", input.prompt); assertEquals(AutomationTriggerView("webhook"), input.trigger)
    }

    @Test fun `--input alone implies the workflow action and needs a workflow`() {
        assertContains(assertFailsWith<UsageError> { applyChanges(null, AutomationChanges(name = "n", webhook = true, inputs = mapOf("a" to "b"))) }.message.orEmpty(), "--workflow")
    }

    @Test fun `a prompt model or effort do not go with a workflow action`() {
        for (changes in listOf(hook().let { AutomationChanges(name = "n", webhook = true, workflowId = workflow, prompt = "p") }, AutomationChanges(name = "n", webhook = true, workflowId = workflow, model = "sonnet"),
            AutomationChanges(name = "n", webhook = true, workflowId = workflow, effort = "high")))
            assertContains(assertFailsWith<UsageError> { applyChanges(null, changes) }.message.orEmpty(), "no prompt")
    }

    @Test fun `--workflow does not go with a cloud action`() {
        assertFailsWith<UsageError> { applyChanges(null, AutomationChanges(name = "n", webhook = true, action = "cloud", profileId = "p1", prompt = "p", workflowId = workflow)) }
    }

    @Test fun `an edit keeps the saved workflow and inputs unless they are given`() {
        val saved = applyChanges(null, hook())
        val renamed = applyChanges(saved, AutomationChanges(name = "Renamed"))
        assertEquals(saved.action, renamed.action); assertEquals("Renamed", renamed.name)
        val changed = applyChanges(saved, AutomationChanges(inputs = mapOf("ticket" to "{{ticket}}", "note" to "n")))
        assertEquals(workflow, changed.action.workflowId); assertEquals(mapOf("ticket" to "{{ticket}}", "note" to "n"), changed.action.inputs)
    }

    @Test fun `an edit can go from a session to a workflow and back`() {
        val session = applyChanges(null, AutomationChanges(name = "n", webhook = true, profileId = "p1", prompt = "Fix it"))
        val toWorkflow = applyChanges(session, AutomationChanges(workflowId = workflow, inputs = mapOf("ticket" to "x")))
        assertEquals("workflow", toWorkflow.action.kind); assertEquals("", toWorkflow.prompt)
        assertContains(assertFailsWith<UsageError> { applyChanges(toWorkflow, AutomationChanges(action = "cloud")) }.message.orEmpty(), "--prompt")
        val back = applyChanges(toWorkflow, AutomationChanges(action = "cloud", prompt = "Fix it again"))
        assertEquals("cloud", back.action.kind); assertNull(back.action.workflowId); assertTrue(back.action.inputs.isEmpty())
    }

    @Test fun `inputs given twice are refused`() {
        assertEquals(mapOf("a" to "1", "b" to "2"), parseInputs(listOf("a" to "1", "b" to "2")))
        assertContains(assertFailsWith<UsageError> { parseInputs(listOf("a" to "1", "a" to "2")) }.message.orEmpty(), "twice")
    }

    @Test fun `details show the workflow and its inputs instead of a prompt`() {
        val view = AutomationView("a1", "m1", "Nathan", "Triage", true, null, null, 2, AutomationTriggerView("webhook"),
            AutomationActionView("workflow", "p1", workflowId = workflow, inputs = mapOf("ticket" to "T-{{ticket}}")), "", emptyList(), null, "skip", 24, createdAt = 0, updatedAt = 0)
        val text = view.details("https://team.example")
        assertContains(text, "Action:       workflow $workflow with profile p1"); assertContains(text, "Input:        ticket = T-{{ticket}}")
        assertFalse("Prompt:" in text)
    }

    @Test fun `a list row and a run row show the workflow`() {
        val row = AutomationSummaryView("a1", "m1", "Nathan", "Triage", true, null, 2, AutomationTriggerView("webhook"), "workflow", updatedAt = 0)
        assertContains(row.line(), "webhook -> workflow")
        val run = AutomationRunView("r1", "a1", 2, "webhook", "running", null, queuedAt = 0, workflowRunId = "wr1")
        assertContains(run.line(), "workflow run wr1"); assertFalse("session" in run.line())
    }

    @Test fun `a dry run shows the rendered inputs`() {
        val text = AutomationPreviewView(renderedInputs = mapOf("ticket" to "T-12")).text()
        assertContains(text, "Rendered inputs:\n  ticket = T-12")
    }
}
