package me.nathanfallet.teamcode.domain.automations

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.teamcode.domain.automations.Fixtures.ada
import me.nathanfallet.teamcode.domain.automations.Fixtures.principal
import me.nathanfallet.teamcode.domain.cloud.CloudSettings
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.workflows.*
import me.nathanfallet.teamcode.domain.workflows.RunStatus as WorkflowRunStatus
import kotlin.test.*

class AutomationWorkflowActionTest {
    private fun triage(profile: String? = "p1", inputs: List<WorkflowInput> = listOf(WorkflowInput("ticket", "Ticket"), WorkflowInput("note", "Note", required = false), WorkflowInput("lang", "Language", default = "en"))) = Workflow(
        "wf1", "team", 2,
        WorkflowDefinition("Triage", null, "a", SessionMode.SAME, inputs, listOf(WorkflowNode("a", NodeKind.AGENT, "Triage", "Look at {{inputs.ticket}}", profileId = profile, transitions = listOf(Transition("done"))))),
        0, 0, null,
    )
    private fun world(workflow: Workflow = triage()) = Fixtures.World().also { it.directory.workflows[workflow.id] = workflow }
    private fun action(inputs: Map<String, String> = mapOf("ticket" to "{{ticket.number}}"), profile: String? = null) =
        AutomationAction(ActionKind.WORKFLOW, profile, workflowId = "wf1", inputs = inputs)
    private val hook = Fixtures.webhook.copy(name = "Triage", action = AutomationAction(ActionKind.WORKFLOW, null, workflowId = "wf1", inputs = mapOf("ticket" to "T-{{ticket.number}}")), prompt = "", overlap = OverlapPolicy.ALLOW)
    private fun refused(code: String = "invalid_automation", block: suspend () -> Unit): TeamFailure = assertFailsWith<TeamFailure> { runBlocking { block() } }.also { assertEquals(code, it.code, it.message) }

    @Test fun aWorkflowActionIsSavedWithItsInputsAndNoPrompt() = runBlocking {
        val w = world()
        val created = w.service.create(principal(ada), hook).automation
        assertEquals("wf1", created.definition.action.workflowId)
        assertEquals(mapOf("ticket" to "T-{{ticket.number}}"), created.definition.action.inputs)
        assertEquals(ActionKind.WORKFLOW, created.definition.action.kind)
    }

    @Test fun anUnknownOrDeletedWorkflowIsRefusedAtSave() {
        val w = world()
        val failure = refused { w.service.create(principal(ada), hook.copy(action = hook.action.copy(workflowId = "gone"))) }
        assertTrue("workflow" in failure.message!!)
        w.directory.workflows.clear()
        refused { w.service.create(principal(ada), hook) }
    }

    @Test fun inputsAreCheckedAgainstTheWorkflowsInputs() {
        val w = world()
        assertTrue("colour" in refused { w.service.create(principal(ada), hook.copy(action = hook.action.copy(inputs = hook.action.inputs + ("colour" to "red")))) }.message!!)
        assertTrue("ticket" in refused { w.service.create(principal(ada), hook.copy(action = hook.action.copy(inputs = emptyMap()))) }.message!!, "a required input without a default must be given")
        // A default, or an optional input, may be left out.
        runBlocking { w.service.create(principal(ada), hook) }
    }

    @Test fun inputTemplatesUseTheDeclaredAndTheRunVariablesOnly() {
        val w = world()
        assertTrue("nope" in refused { w.service.create(principal(ada), hook.copy(action = hook.action.copy(inputs = mapOf("ticket" to "{{nope}}")))) }.message!!)
        runBlocking { w.service.create(principal(ada), hook.copy(action = hook.action.copy(inputs = mapOf("ticket" to "{{run.date}} {{automation.name}} {{ticket.number}}")))) }
        refused { w.service.create(principal(ada), hook.copy(prompt = "Fix it")) }
        refused { w.service.create(principal(ada), hook.copy(action = hook.action.copy(model = "sonnet"))) }
    }

    @Test fun agentNodesWithoutAProfileNeedOneOnTheAction() = runBlocking {
        val w = world(triage(profile = null))
        assertTrue("profile" in refused { w.service.create(principal(ada), hook) }.message!!)
        w.service.create(principal(ada), hook.copy(action = hook.action.copy(profileId = "p1")))
        refused("profile_unassigned") { w.service.create(principal(Fixtures.bob), hook.copy(action = hook.action.copy(profileId = "p1"))) }
    }

    @Test fun aCloudActionCannotCarryWorkflowFields() {
        refused { world().service.create(principal(ada), Fixtures.webhook.copy(action = Fixtures.webhook.action.copy(workflowId = "wf1"))) }
    }

    @Test fun theDryRunRendersTheInputsNotAPrompt() = runBlocking {
        val w = world()
        val id = w.service.create(principal(ada), hook).automation.id
        val preview = (w.service.test(principal(ada), id, buildJsonObject { put("ticket", buildJsonObject { put("number", 42) }) }, dryRun = true, idempotencyKey = null) as TestOutcome.Preview).preview
        assertEquals(mapOf("ticket" to "T-42"), preview.renderedInputs)
        assertNull(preview.renderedPrompt)
        assertNull(preview.profileVersion, "no profile on the action")
    }

    private fun queued(w: Fixtures.World, key: String = "k"): AutomationRun = runBlocking {
        val id = w.store.automations.values.firstOrNull()?.id ?: w.service.create(principal(ada), hook).automation.id
        (w.service.test(principal(ada), id, buildJsonObject { put("ticket", buildJsonObject { put("number", 42) }) }, dryRun = false, idempotencyKey = key) as TestOutcome.Run).outcome.run
    }

    @Test fun aQueuedRunStartsTheWorkflowRunAsTheOwnerWithItsKeyAndRenderedInputs() = runBlocking {
        val w = world()
        val run = queued(w)
        w.dispatcher.run()
        val (id, inputs) = w.workflows.started.getValue("automation-run:${run.id}")
        assertEquals(mapOf("ticket" to "T-42"), inputs)
        val started = w.store.runs.getValue(run.id)
        assertEquals(RunStatus.RUNNING, started.status, "started, then followed in the same pass"); assertEquals(id, started.workflowRunId); assertNull(started.sessionId)
        assertTrue(w.sessions.created.isEmpty(), "no session of its own")
        assertFalse(run.id in w.store.prompts)
    }

    @Test fun anEmptyRenderedInputLeavesTheWorkflowsDefault() = runBlocking {
        val w = world()
        val definition = hook.copy(action = hook.action.copy(inputs = mapOf("ticket" to "T-1", "lang" to "{{ticket.number}}")), variables = listOf(AutomationVariable("ticket.number", "$.ticket.number", VariableType.INT, required = false)))
        val id = w.service.create(principal(ada), definition).automation.id
        val run = (w.service.test(principal(ada), id, buildJsonObject {}, dryRun = false, idempotencyKey = "e") as TestOutcome.Run).outcome.run
        w.dispatcher.run()
        assertEquals(mapOf("ticket" to "T-1"), w.workflows.started.getValue("automation-run:${run.id}").second, "lang renders empty: the default applies")
    }

    @Test fun theRunFollowsTheWorkflowRunToItsEnd() = runBlocking {
        val w = world()
        val run = queued(w)
        w.dispatcher.run()
        val id = w.store.runs.getValue(run.id).workflowRunId!!
        assertEquals(RunStatus.RUNNING, w.store.runs.getValue(run.id).status)
        w.workflows.states[id] = ChildState(WorkflowRunStatus.WAITING, null)
        w.dispatcher.run()
        assertEquals(RunStatus.RUNNING, w.store.runs.getValue(run.id).status, "waiting for a person is still working")
        w.workflows.states[id] = ChildState(WorkflowRunStatus.SUCCEEDED, "done")
        w.dispatcher.run()
        assertEquals(RunStatus.SUCCEEDED, w.store.runs.getValue(run.id).status)
    }

    @Test fun aFailedOrCancelledWorkflowRunEndsTheAutomationRunTheSameWay() = runBlocking {
        val w = world()
        val failed = queued(w, "f"); w.dispatcher.run()
        w.workflows.states[w.store.runs.getValue(failed.id).workflowRunId!!] = ChildState(WorkflowRunStatus.FAILED, null)
        val cancelled = queued(w, "c"); w.dispatcher.run()
        w.workflows.states[w.store.runs.getValue(cancelled.id).workflowRunId!!] = ChildState(WorkflowRunStatus.CANCELLED, null)
        w.dispatcher.run()
        assertEquals(RunStatus.FAILED to "workflow_failed", w.store.runs.getValue(failed.id).let { it.status to it.reason })
        assertEquals(RunStatus.CANCELLED to "workflow_cancelled", w.store.runs.getValue(cancelled.id).let { it.status to it.reason })
    }

    @Test fun aWorkflowRunThatDisappearedFailsTheRunClearly() = runBlocking {
        val w = world()
        val run = queued(w); w.dispatcher.run()
        w.workflows.states.clear()
        w.dispatcher.run()
        assertEquals("workflow_missing", w.store.runs.getValue(run.id).reason)
    }

    @Test fun cancellingAWorkingRunCancelsTheWorkflowRun() = runBlocking {
        val w = world()
        val run = queued(w); w.dispatcher.run()
        val id = w.store.runs.getValue(run.id).workflowRunId!!
        w.service.cancel(principal(ada), run.automationId, run.id)
        w.dispatcher.run()
        assertEquals(listOf(id), w.workflows.cancelled)
        assertEquals(RunStatus.CANCELLED, w.store.runs.getValue(run.id).status)
    }

    @Test fun aRefusalOfTheRunServiceEndsTheRunFailedWithItsCode() = runBlocking {
        val w = world()
        w.workflows.failure = TeamFailure("run_limit", 409, "At most 20 workflow runs at a time.")
        val limited = queued(w, "l"); w.dispatcher.run()
        assertEquals(RunStatus.FAILED to "run_limit", w.store.runs.getValue(limited.id).let { it.status to it.reason })
        w.workflows.failure = TeamFailure("not_found", 404, "Workflow not found.")
        val missing = queued(w, "m"); w.dispatcher.run()
        assertEquals(RunStatus.FAILED to "workflow_missing", w.store.runs.getValue(missing.id).let { it.status to it.reason })
        w.workflows.failure = TeamFailure("invalid_inputs", 400, "The input ticket is required.")
        val invalid = queued(w, "i"); w.dispatcher.run()
        assertEquals("invalid_inputs", w.store.runs.getValue(invalid.id).reason)
    }

    @Test fun aRevokedOwnerFailsAWorkflowRunToo() = runBlocking {
        val w = world()
        val run = queued(w)
        w.store.revoked += run.automationId
        w.dispatcher.run()
        assertEquals("owner_unavailable", w.store.runs.getValue(run.id).reason)
        assertTrue(w.workflows.started.isEmpty())
    }

    @Test fun workflowRunsTakeNoPlaceInTheSessionCap() = runBlocking {
        val w = world()
        w.directory.settings = CloudSettings(maxRunningAutomationsPerMember = 1)
        val id = w.service.create(principal(ada), hook).automation.id
        val payload = buildJsonObject { put("ticket", buildJsonObject { put("number", 1) }) }
        val first = (w.service.test(principal(ada), id, payload, false, "a") as TestOutcome.Run).outcome.run
        val second = (w.service.test(principal(ada), id, payload, false, "b") as TestOutcome.Run).outcome.run
        w.dispatcher.run()
        assertEquals(RunStatus.RUNNING, w.store.runs.getValue(first.id).status)
        assertEquals(RunStatus.RUNNING, w.store.runs.getValue(second.id).status, "a second one starts although the cap is 1")
    }

    @Test fun aDispatchRetriedAfterACrashStartsOneWorkflowRun() = runBlocking {
        val w = world()
        val run = queued(w)
        // The first replica started the workflow run then died before recording it; the retry uses the same key.
        val first = w.workflows.start(principal(ada), w.store.automations.values.single(), mapOf("ticket" to "T-42"), "automation-run:${run.id}")
        w.dispatcher.run()
        assertEquals(1, w.workflows.started.size)
        assertEquals(first, w.store.runs.getValue(run.id).workflowRunId)
    }
}
