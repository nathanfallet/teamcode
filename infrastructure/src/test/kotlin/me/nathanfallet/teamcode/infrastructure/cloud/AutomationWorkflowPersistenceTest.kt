package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.automations.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.workflows.Deliverable
import me.nathanfallet.teamcode.domain.workflows.DeliverableType
import me.nathanfallet.teamcode.domain.workflows.NodeKind
import me.nathanfallet.teamcode.domain.workflows.SessionMode
import me.nathanfallet.teamcode.domain.workflows.Transition
import me.nathanfallet.teamcode.domain.workflows.WorkflowDefinition
import me.nathanfallet.teamcode.domain.workflows.WorkflowInput
import me.nathanfallet.teamcode.domain.workflows.WorkflowNode
import me.nathanfallet.teamcode.infrastructure.database.SqlWorkflowRunStore
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import kotlin.test.*
import me.nathanfallet.teamcode.domain.workflows.RunStatus as WorkflowRunStatus

/** Automations whose action starts a workflow run, on real MySQL with two pools standing for two replicas. */
class AutomationWorkflowPersistenceTest {
    private val triage = WorkflowDefinition(
        "Triage", null, "work", SessionMode.SAME, listOf(WorkflowInput("ticket", "Ticket"), WorkflowInput("lang", "Language", default = "en")),
        listOf(WorkflowNode("work", NodeKind.AGENT, "Work", "Fix {{inputs.ticket}}", deliverable = Deliverable(DeliverableType.TEXT), transitions = listOf(Transition("delivered")))),
    )
    private fun payload(n: Int) = buildJsonObject { put("ticket", JsonPrimitive(n)) }

    @Test @Timeout(600)
    fun aWorkflowActionStartsExactlyOneRunWhateverTheReplicas() = runBlocking {
        AutomationWorld.run(95) { w ->
            w.a.database.query {
                assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(Automations, AutomationRuns, withLogs = false), "The reviewed migrations match the Exposed tables")
            }
            val workflow = w.a.workflowDefinitions.create(w.admin, triage)
            val definition = AutomationDefinition("Triage", AutomationTrigger(TriggerKind.WEBHOOK),
                AutomationAction(ActionKind.WORKFLOW, w.profile.id, workflowId = workflow.id, inputs = mapOf("ticket" to "T-{{ticket}}")), "",
                listOf(AutomationVariable("ticket", "$.ticket", VariableType.INT)), overlap = OverlapPolicy.ALLOW)
            // Saved and read back by the other replica, validated against the workflow.
            refusedWith("invalid_automation") { w.a.automations.create(w.member, definition.copy(action = definition.action.copy(workflowId = java.util.UUID.randomUUID().toString()))) }
            refusedWith("invalid_automation") { w.a.automations.create(w.member, definition.copy(action = definition.action.copy(inputs = mapOf("colour" to "red")))) }
            refusedWith("invalid_automation") { w.a.automations.create(w.member, definition.copy(action = definition.action.copy(inputs = emptyMap()))) }
            val created = w.a.automations.create(w.member, definition).automation
            assertEquals(definition.action, w.b.store.find(w.teamId, created.id)!!.definition.action)
            assertEquals(ActionKind.WORKFLOW.wire, w.b.store.list(w.teamId, w.member.member.id, null, 10).automations.single().definition.action.kind.wire)

            // A workflow an automation starts is not deleted.
            val inUse = assertFailsWith<TeamFailure> { w.b.workflowDefinitions.delete(w.admin, workflow.id) }
            assertEquals("workflow_in_use", inUse.code); assertTrue("Triage" in inUse.message!!)

            // One trigger, two replicas dispatching at once, then a replay of the same dispatch after a crash before the link was recorded.
            w.a.automations.enable(w.member, created.id)
            val run = (w.a.automations.test(w.member, created.id, payload(7), false, "k1") as TestOutcome.Run).outcome.run
            w.a.database.query { assertEquals("""{"ticket":"T-7"}""", AutomationRuns.select(AutomationRuns.prompt).where { AutomationRuns.id eq run.id }.single()[AutomationRuns.prompt]) }
            coroutineScope { listOf(w.a, w.b).map { r -> async(Dispatchers.IO) { r.dispatcher.run() } }.awaitAll() }
            suspend fun workflowRuns() = w.a.database.query { WorkflowRuns.selectAll().where { WorkflowRuns.starterId eq w.member.member.id }.toList() }
            val started = w.a.store.run(w.teamId, created.id, run.id)!!
            assertEquals(1, workflowRuns().size)
            assertEquals(w.member.member.id, workflowRuns().single()[WorkflowRuns.starterId], "started as the automation's owner")
            assertEquals(started.workflowRunId, workflowRuns().single()[WorkflowRuns.id])
            assertEquals("""{"ticket":"T-7","lang":"en"}""", workflowRuns().single()[WorkflowRuns.inputs], "the workflow's default fills the input left out")
            assertEquals(w.profile.id, workflowRuns().single()[WorkflowRuns.profileId])
            assertTrue(started.status in setOf(RunStatus.STARTING, RunStatus.RUNNING)); assertNull(started.sessionId)
            w.a.database.query {
                AutomationRuns.update({ AutomationRuns.id eq run.id }) {
                    it[status] = "queued"; it[workflowRunId] = null; it[nextAttemptAt] = 0; it[prompt] = """{"ticket":"T-7"}"""
                }
            }
            coroutineScope { listOf(w.b, w.a).map { r -> async(Dispatchers.IO) { r.dispatcher.run() } }.awaitAll() }
            assertEquals(1, workflowRuns().size, "the same key returns the first workflow run")
            assertEquals(workflowRuns().single()[WorkflowRuns.id], w.b.store.run(w.teamId, created.id, run.id)!!.workflowRunId)

            // The automation run ends as the workflow run does.
            val followed = w.b.store.run(w.teamId, created.id, run.id)!!
            SqlWorkflowRunStore(w.a.database).finish(followed.workflowRunId!!, WorkflowRunStatus.SUCCEEDED, null, "done", w.a.store.now())
            w.a.dispatcher.run()
            assertEquals(RunStatus.SUCCEEDED, w.b.store.run(w.teamId, created.id, run.id)!!.status)

            // The cap of 20 unfinished runs per member: a refusal fails the run with its code.
            repeat(20) { w.a.workflowRuns.start(w.member, workflow.id, mapOf("ticket" to "x$it"), w.profile.id, null) }
            val capped = (w.a.automations.test(w.member, created.id, payload(8), false, "k2") as TestOutcome.Run).outcome.run
            w.b.dispatcher.run()
            assertEquals(RunStatus.FAILED to "run_limit", w.a.store.run(w.teamId, created.id, capped.id)!!.let { it.status to it.reason })
            assertEquals(21, workflowRuns().size)

            // A workflow that vanished (a race with the delete check) fails the run clearly.
            w.a.database.query { Workflows.deleteWhere { Workflows.id eq workflow.id } }
            val missing = (w.a.automations.test(w.member, created.id, payload(9), false, "k3") as TestOutcome.Run).outcome.run
            w.a.dispatcher.run()
            assertEquals(RunStatus.FAILED to "workflow_missing", w.a.store.run(w.teamId, created.id, missing.id)!!.let { it.status to it.reason })
            refusedWith("invalid_automation") { w.a.automations.update(w.member, created.id, created.version, definition) }
        }
    }

    @Test @Timeout(600)
    fun aWorkflowIsDeletedOnceNoAutomationStartsIt() = runBlocking {
        AutomationWorld.run(96) { w ->
            val workflow = w.a.workflowDefinitions.create(w.admin, triage)
            val created = w.a.automations.create(w.member, AutomationDefinition("Daily", AutomationTrigger(TriggerKind.CRON, "0 21 * * *", "Europe/Paris"),
                AutomationAction(ActionKind.WORKFLOW, w.profile.id, workflowId = workflow.id, inputs = mapOf("ticket" to "Review {{run.date}}")), ""))
            refusedWith("workflow_in_use") { w.a.workflowDefinitions.delete(w.admin, workflow.id) }
            w.b.automations.delete(w.member, created.automation.id)
            w.a.workflowDefinitions.delete(w.admin, workflow.id)
        }
    }

    /** The plans of the queries this change added or changed, on automation_runs and automations seeded at scale. */
    @Test @Timeout(900)
    fun theCapAndTheInUseLookupUseTheirIndexesAtScale() = runBlocking {
        AutomationWorld.run(97) { w ->
            val owners = listOf(w.admin.member.id, w.member.member.id)
            w.a.database.query {
                val automationIds = (1..3_000).map { java.util.UUID.randomUUID().toString() }
                Automations.batchInsert(automationIds.withIndex().toList(), shouldReturnGeneratedValues = false) { (n, id) ->
                    this[Automations.id] = id; this[Automations.teamId] = if (n % 10 == 0) w.teamId else "other-${n % 40}"; this[Automations.ownerId] = owners[n % 2]; this[Automations.name] = "Seed $n"
                    this[Automations.triggerKind] = "webhook"; this[Automations.catchup] = "once"; this[Automations.catchupGraceMinutes] = 360; this[Automations.actionKind] = if (n % 2 == 0) "workflow" else "cloud"
                    this[Automations.workflowId] = if (n % 2 == 0) "seed-workflow-${n % 500}" else null; this[Automations.maxRuntimeMinutes] = 60; this[Automations.prompt] = ""; this[Automations.variables] = "[]"
                    this[Automations.overlap] = "allow"; this[Automations.maxRunsPerDay] = 24; this[Automations.enabled] = false; this[Automations.version] = 1; this[Automations.consecutiveFailures] = 0
                    this[Automations.secretRotatedAt] = 0; this[Automations.createdAt] = n.toLong(); this[Automations.updatedAt] = n.toLong()
                }
                (0 until 300_000).chunked(10_000).forEach { chunk -> AutomationRuns.batchInsert(chunk, shouldReturnGeneratedValues = false) { n ->
                    this[AutomationRuns.id] = java.util.UUID.randomUUID().toString(); this[AutomationRuns.automationId] = automationIds[n % 3_000]; this[AutomationRuns.teamId] = w.teamId
                    this[AutomationRuns.ownerId] = owners[n % 2]; this[AutomationRuns.automationVersion] = 1; this[AutomationRuns.runSource] = "webhook"; this[AutomationRuns.deliveryKey] = "seed-$n"
                    this[AutomationRuns.status] = if (n % 1_000 < 2) "running" else "succeeded"; this[AutomationRuns.variables] = "[]"; this[AutomationRuns.workflowRunId] = if (n % 2_000 == 0) "seed-wf-run" else null
                    this[AutomationRuns.queuedAt] = n.toLong(); this[AutomationRuns.nextAttemptAt] = n.toLong(); this[AutomationRuns.cancelRequested] = false; this[AutomationRuns.sessionReleased] = true
                } }
            }
            val owner = w.member.member.id; val team = w.teamId
            val cap = w.a.database.query {
                explain(analyze = true) {
                    AutomationRuns.select(AutomationRuns.id).where {
                        (AutomationRuns.ownerId eq owner) and (AutomationRuns.status inList listOf("starting", "running")) and (AutomationRuns.teamId eq team) and AutomationRuns.workflowRunId.isNull()
                    }
                }.joinToString("\n")
            }
            println("Owner cap (working) plan, 300000 automation_runs:\n$cap")
            assertTrue("automation_run_owner" in cap, cap)
            val inUse = w.a.database.query {
                explain(analyze = true) {
                    Automations.select(Automations.name).where { (Automations.workflowId eq "seed-workflow-100") and (Automations.teamId eq team) }.limit(1)
                }.joinToString("\n")
            }
            println("Workflow in-use plan, 3000 automations:\n$inUse")
            assertTrue("automation_workflow" in inUse, inUse)
        }
    }
}
