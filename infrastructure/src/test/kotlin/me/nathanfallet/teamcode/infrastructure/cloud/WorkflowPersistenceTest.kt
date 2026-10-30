package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.workflows.*
import me.nathanfallet.teamcode.infrastructure.database.SqlWorkflowStore
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import kotlin.test.*

/** Workflow definitions on real MySQL, two pools standing for two replicas. */
class WorkflowPersistenceTest {
    private fun agent(name: String, call: String? = null) = WorkflowDefinition(
        name, "About $name", "work", SessionMode.HANDOFF, listOf(WorkflowInput("ticket", "Ticket", default = "T-1")),
        listOf(
            if (call == null) WorkflowNode("work", NodeKind.AGENT, "Work", "Fix {{inputs.ticket}}", deliverable = Deliverable(DeliverableType.URL, "PR", "github.com"), transitions = listOf(Transition("delivered")))
            else WorkflowNode("work", NodeKind.WORKFLOW, "Call", workflowId = call, arguments = mapOf("ticket" to "{{inputs.ticket}}"), transitions = listOf(Transition("delivered"))),
        ),
    )

    @Test @Timeout(240)
    fun workflowsHoldAcrossReplicas() = runBlocking {
        AutomationWorld.run(81) { w ->
            w.a.database.query {
                assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(Workflows, WorkflowTeamSettings, withLogs = false), "The reviewed migration matches the Exposed tables")
            }
            val a = WorkflowService(SqlWorkflowStore(w.a.database)); val b = WorkflowService(SqlWorkflowStore(w.b.database))

            // A definition round-trips, with a human node's fields and a loop back.
            val human = WorkflowNode(
                "review", NodeKind.HUMAN, "Review", "Look", fields = listOf(me.nathanfallet.teamcode.domain.waits.WaitField("feedback", me.nathanfallet.teamcode.domain.waits.FieldType.TEXT, "Feedback")),
                actions = listOf(me.nathanfallet.teamcode.domain.waits.WaitAction("approve", "Approve"), me.nathanfallet.teamcode.domain.waits.WaitAction("again", "Again", requires = listOf("feedback"))),
                assignee = NodeAssignee(false, w.member.member.id), transitions = listOf(Transition("approve"), Transition("again", "work")), maxVisits = 5,
            )
            val rich = agent("Fix a ticket").let { it.copy(nodes = listOf(it.nodes[0].copy(transitions = listOf(Transition("delivered", "review"))), human)) }
            val created = a.create(w.admin, rich)
            assertEquals(1, created.version); assertEquals(rich, b.get(w.member, created.id).definition)

            // Saving bumps the version; a stale one is refused; names are unique.
            val second = b.update(w.admin, created.id, rich.copy(description = "v2"), expectedVersion = 1)
            assertEquals(2, second.version); assertEquals("v2", a.get(w.member, created.id).definition.description)
            refusedWith("workflow_conflict") { a.update(w.admin, created.id, rich, expectedVersion = 1) }
            refusedWith("workflow_name_taken") { a.create(w.admin, rich) }
            refusedWith("workflow_editors_only") { a.create(w.member, agent("Mine")) }

            // Two replicas saving A -> B and B -> A at once: one wins, the other sees the loop.
            val x = a.create(w.admin, agent("X")); val y = a.create(w.admin, agent("Y"))
            val outcomes = coroutineScope {
                listOf(a to (x.id to y.id), b to (y.id to x.id)).map { (service, pair) ->
                    async(Dispatchers.IO) { runCatching { service.update(w.admin, pair.first, agent(if (pair.first == x.id) "X" else "Y", call = pair.second), null) } }
                }.awaitAll()
            }
            assertEquals(1, outcomes.count { it.isSuccess }, "exactly one of the two calls is stored")
            val failure = outcomes.single { it.isFailure }.exceptionOrNull()
            assertTrue(failure is TeamFailure, failure?.stackTraceToString()?.take(1500))
            assertEquals("invalid_workflow", failure.code)
            val caller = if (outcomes[0].isSuccess) x else y; val callee = if (outcomes[0].isSuccess) y else x
            refusedWith("workflow_in_use") { b.delete(w.admin, callee.id) }

            // The editing rule is stored, shared, and audited.
            a.saveEditors(w.admin, WorkflowEditors(EditorMode.MEMBERS, setOf(w.member.member.id)))
            assertEquals(WorkflowEditors(EditorMode.MEMBERS, setOf(w.member.member.id)), b.settings(w.member).editors); assertTrue(b.settings(w.member).canEdit)
            val mine = b.create(w.member, agent("Mine")); b.delete(w.member, mine.id)
            assertEquals(setOf("workflow.editors.changed"), w.a.database.query { AuditEvents.selectAll().where { AuditEvents.action like "workflow.editors%" }.map { it[AuditEvents.action] }.toSet() })

            // Paging by keyset, and the list reads the team's index.
            val ids = (1..5).map { a.create(w.admin, agent("P$it")).id }
            val seen = mutableListOf<String>(); var cursor: String? = null
            do { val page = a.list(w.member, cursor, 3); seen += page.workflows.map { it.id }; cursor = page.nextAfter } while (cursor != null)
            assertEquals(seen.sorted(), seen, "ascending"); assertEquals(seen.size, seen.toSet().size); assertTrue(ids.all { it in seen })
            // The plan is read against many teams' rows, not an empty table (the planner scans the primary key of a tiny one).
            w.a.database.query {
                Workflows.batchInsert((1..6_000).toList(), shouldReturnGeneratedValues = false) { n ->
                    this[Workflows.id] = java.util.UUID.randomUUID().toString(); this[Workflows.teamId] = "other-${n % 40}"; this[Workflows.name] = "Seed $n"
                    this[Workflows.version] = 1; this[Workflows.definition] = "{}"; this[Workflows.createdAt] = 1; this[Workflows.updatedAt] = 1
                }
            }
            val plan = w.a.database.query {
                explain(analyze = true) { Workflows.selectAll().where { (Workflows.teamId eq w.teamId) and (Workflows.id greater ids.first()) }.orderBy(Workflows.id to SortOrder.ASC).limit(51) }.joinToString("\n")
            }
            println("Workflow listing execution plan (synthetic metadata only):\n$plan")
            // Either team-prefixed index serves a team's few rows (the planner picks by statistics that vary run to run); a scan of the table is the failure.
            assertTrue("workflow_team" in plan || "workflow_name" in plan, plan)
            assertFalse("Table scan" in plan, plan)

            // Deleting a caller frees its callee; the cap holds.
            b.delete(w.admin, caller.id); b.delete(w.admin, callee.id)
            val over = runCatching { SqlWorkflowStore(w.a.database).save(w.admin, null, agent("Over"), null, limit = 1) {} }
            assertEquals("workflow_limit", (over.exceptionOrNull() as TeamFailure).code)
        }
    }
}
