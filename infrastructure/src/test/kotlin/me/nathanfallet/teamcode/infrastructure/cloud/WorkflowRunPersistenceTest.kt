package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState
import me.nathanfallet.teamcode.domain.cloud.DesiredState
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.waits.*
import me.nathanfallet.teamcode.domain.workflows.*
import me.nathanfallet.teamcode.infrastructure.database.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import kotlin.test.*

/** Workflow runs on real MySQL, two pools standing for two replicas, with the real store and fake cloud sessions and waits. */
class WorkflowRunPersistenceTest {
    private class Sessions : WorkflowSessions {
        val created = mutableListOf<String>(); val messages = mutableListOf<String>()
        override suspend fun create(starter: Principal, name: String, profileId: String, model: String?, prompt: String, key: String): String { created += prompt; return "s-${created.size}" }
        override suspend fun message(starter: Principal, sessionId: String, text: String, key: String) { messages += text }
        override suspend fun progress(sessionIds: Set<String>) = sessionIds.associateWith { SessionProgress(CloudSessionState.ACTIVE, DesiredState.RUNNING, null, null) }
    }
    private class Waits : WorkflowWaits {
        val states = mutableMapOf<String, WaitState>()
        override suspend fun ask(starter: Principal, sessionId: String, assigneeId: String?, title: String, body: String?, fields: List<WaitField>, actions: List<WaitAction>, key: String): String =
            "w-${states.size + 1}".also { states[it] = WaitState(WaitStatus.OPEN, null, null) }
        override suspend fun states(ids: Set<String>) = states.filterKeys { it in ids }
        override suspend fun cancel(starter: Principal, id: String) { states[id] = WaitState(WaitStatus.CANCELLED, null, null) }
    }

    private fun definition(name: String, sub: String? = null) = WorkflowDefinition(
        name, null, "work", SessionMode.SAME, listOf(WorkflowInput("ticket", "Ticket", default = "T-1")),
        listOf(
            WorkflowNode("work", NodeKind.AGENT, "Work", "Fix {{inputs.ticket}}", deliverable = Deliverable(DeliverableType.TEXT), transitions = listOf(Transition("delivered", "review"))),
            WorkflowNode("review", NodeKind.HUMAN, "Review", "Look at {{nodes.work.output}}", actions = listOf(WaitAction("approve", "Approve")),
                transitions = listOf(Transition("approve", if (sub != null) "call" else null))),
        ) + listOfNotNull(sub?.let { WorkflowNode("call", NodeKind.WORKFLOW, "Call", workflowId = it, arguments = mapOf("ticket" to "{{inputs.ticket}}"), transitions = listOf(Transition("delivered"))) }),
    )

    @Test @Timeout(300)
    fun runsHoldAcrossReplicas() = runBlocking {
        AutomationWorld.run(91) { w ->
            w.a.database.query {
                assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(WorkflowRuns, WorkflowVisits, withLogs = false), "The reviewed migration matches the Exposed tables")
            }
            val definitions = WorkflowService(SqlWorkflowStore(w.a.database))
            val inner = definitions.create(w.admin, definition("Inner")); val outer = definitions.create(w.admin, definition("Outer", sub = inner.id))
            val storeA = SqlWorkflowRunStore(w.a.database); val storeB = SqlWorkflowRunStore(w.b.database)
            val serviceA = WorkflowRunService(storeA); val serviceB = WorkflowRunService(storeB)
            val sessions = Sessions(); val waits = Waits()
            val engineA = WorkflowEngine(storeA, sessions, waits); val engineB = WorkflowEngine(storeB, sessions, waits)

            // Starting is idempotent across replicas, the first visit is stored with the run, and the run holds a server-held credential.
            val first = serviceA.start(w.member, outer.id, mapOf("ticket" to "T-42"), "p1", "k1"); val again = serviceB.start(w.member, outer.id, mapOf("ticket" to "T-42"), "p1", "k1")
            assertTrue(first.created); assertFalse(again.created); assertEquals(first.run.id, again.run.id)
            val run = first.run
            val (stored, visits) = serviceB.get(w.member, run.id)
            assertEquals(listOf("work"), visits.map { it.nodeId }); assertEquals(outer.version, stored.workflowVersion); assertEquals(definition("Outer", sub = inner.id), stored.definition)
            assertEquals(1L, w.a.database.query { Tokens.selectAll().where { Tokens.device like "workflow %" }.count() })
            assertEquals(emptyList(), serviceB.list(w.admin, false, null, null, null, 10).runs, "an administrator's own list holds only their runs")
            assertEquals(listOf(run.id), serviceB.list(w.admin, true, null, "running", null, 10).runs.map { it.id })

            // Two replicas claiming together: one gets the run, the other nothing, until it is released.
            val claims = coroutineScope { listOf(storeA, storeB).map { async(Dispatchers.IO) { it.claim(10, 60_000).map { work -> work.run.id } } }.awaitAll() }
            assertEquals(listOf(run.id), claims.flatten(), "exactly one replica holds the run")
            assertEquals(emptyList(), storeB.claim(10, 60_000)); storeA.release(listOf(run.id)); assertEquals(listOf(run.id), storeB.claim(10, 60_000).map { it.run.id }); storeB.release(listOf(run.id))

            // The engine, alternating replicas, takes the run through its nodes; a delivery of the wrong type is refused.
            engineA.run(); assertEquals(listOf("Fix T-42"), sessions.created)
            assertEquals("invalid_deliverable", assertFailsWith<TeamFailure> { serviceB.deliver(w.member, run.id, kotlinx.serialization.json.JsonObject(emptyMap())) }.code)
            serviceB.deliver(w.member, run.id, JsonPrimitive("fixed"))
            assertEquals("nothing_to_deliver", assertFailsWith<TeamFailure> { serviceA.deliver(w.member, run.id, JsonPrimitive("again")) }.code, "only the first delivery counts")
            engineB.run(); engineA.run()
            assertEquals(RunStatus.WAITING, serviceA.get(w.member, run.id).first.status)
            waits.states["w-1"] = WaitState(WaitStatus.RESOLVED, "approve", ""); engineB.run(); engineA.run(); engineB.run()
            val child = w.a.database.query { WorkflowRuns.selectAll().where { WorkflowRuns.parentRunId eq run.id }.single() }
            assertEquals(1, child[WorkflowRuns.depth]); assertEquals("s-1", child[WorkflowRuns.sessionId]); assertNotNull(child[WorkflowRuns.credentialId])
            // Both replicas starting the same child is impossible: the parent visit is unique.
            val parentVisit = serviceA.get(w.member, run.id).second.last()
            assertFalse(storeA.startChild(parentVisit.id, WorkflowEngine.newRun(inner, run.starterId, emptyMap(), "p1", null, 1, run.id, parentVisit.id, 5), WorkflowEngine.visit("x", 1, definition("Inner").nodes[0], 5)))

            // The child works in the parent's session; delivering and answering end it, then the parent.
            engineA.run(); engineB.run()
            val childId = child[WorkflowRuns.id]
            serviceA.deliver(w.member, childId, JsonPrimitive("child fixed")); engineA.run(); engineB.run()
            waits.states["w-2"] = WaitState(WaitStatus.RESOLVED, "approve", "x: y"); engineA.run(); engineB.run(); engineA.run(); engineB.run(); engineA.run()
            assertEquals(RunStatus.SUCCEEDED, serviceA.get(w.member, childId).first.status)
            assertEquals(RunStatus.SUCCEEDED, serviceA.get(w.member, run.id).first.status); assertEquals("x: y", serviceA.get(w.member, run.id).first.output)
            assertEquals(0L, w.a.database.query { Tokens.selectAll().where { Tokens.device like "workflow %" }.count() }, "the credential goes with the root run")

            // Conditional updates: of two replicas completing the same visit, one wins.
            val second = serviceA.start(w.member, inner.id, emptyMap(), "p1", null).run; engineA.run()
            val visit = serviceA.get(w.member, second.id).second.single()
            val results = coroutineScope { listOf(storeA, storeB).map { async(Dispatchers.IO) { it.complete(visit.id, VisitStatus.DONE, "delivered", "x", 9) } }.awaitAll() }
            assertEquals(1, results.count { it })

            // Cancelling ends the run on the next pass and withdraws its wait.
            val third = serviceA.start(w.member, inner.id, emptyMap(), "p1", null).run; engineA.run(); serviceA.deliver(w.member, third.id, JsonPrimitive("x")); engineB.run(); engineA.run()
            serviceB.cancel(w.member, third.id); engineA.run()
            assertEquals(RunStatus.CANCELLED, serviceA.get(w.member, third.id).first.status); assertEquals(WaitStatus.CANCELLED, waits.states.getValue(serviceA.get(w.member, third.id).second.last().waitId!!).status)
            assertEquals("run_finished", assertFailsWith<TeamFailure> { serviceA.cancel(w.member, third.id) }.code)

            // The cap counts unfinished runs of a member across replicas.
            val capped = coroutineScope {
                (1..6).map { n ->
                    async(Dispatchers.IO) {
                        val r = WorkflowEngine.newRun(inner, w.member.member.id, emptyMap(), "p1", null, 0, null, null, 100L + n)
                        runCatching { (if (n % 2 == 0) storeA else storeB).start(w.member, r, WorkflowEngine.visit(r.id, 1, definition("Inner").nodes[0], 100L + n), null, limit = 4) }
                    }
                }.awaitAll()
            }
            assertEquals(3, capped.count { it.isSuccess }, "the member already holds one unfinished run (the second above)")
            assertTrue(capped.filter { it.isFailure }.all { (it.exceptionOrNull() as TeamFailure).code == "run_limit" })

            // Listing: keyset paging over many teams' rows, and the plans read the intended indexes.
            w.a.database.query {
                WorkflowRuns.batchInsert((1..6_000).toList(), shouldReturnGeneratedValues = false) { n ->
                    this[WorkflowRuns.id] = java.util.UUID.randomUUID().toString(); this[WorkflowRuns.teamId] = "other-${n % 40}"; this[WorkflowRuns.workflowId] = "wf-${n % 7}"; this[WorkflowRuns.workflowName] = "Seed"
                    this[WorkflowRuns.workflowVersion] = 1; this[WorkflowRuns.definition] = "{}"; this[WorkflowRuns.starterId] = w.admin.member.id; this[WorkflowRuns.status] = if (n % 3 == 0) "succeeded" else "running"
                    this[WorkflowRuns.inputs] = "{}"; this[WorkflowRuns.depth] = 0; this[WorkflowRuns.cancelRequested] = false; this[WorkflowRuns.leaseUntil] = 1L shl 50
                    this[WorkflowRuns.createdAt] = n.toLong(); this[WorkflowRuns.updatedAt] = n.toLong()
                }
            }
            val seen = mutableListOf<String>(); var cursor: String? = null
            do { val page = serviceA.list(w.member, false, null, null, cursor, 2); seen += page.runs.map { it.id }; cursor = page.nextBefore } while (cursor != null)
            assertEquals(seen.size, seen.toSet().size); assertTrue(run.id in seen && second.id in seen && third.id in seen)
            assertEquals(listOf(third.id), serviceA.list(w.member, false, null, "cancelled", null, 10).runs.map { it.id })
            assertEquals(listOf(run.id), serviceA.list(w.admin, true, outer.id, "succeeded", null, 10).runs.map { it.id }, "the workflow filter")
            val team = w.teamId; val me = w.member.member.id
            // The columns a list reads: everything but the definition snapshot.
            val columns = WorkflowRuns.columns - WorkflowRuns.definition
            val plans = w.a.database.query {
                mapOf(
                    "own, newest first" to explain(analyze = true) { WorkflowRuns.select(columns).where { (WorkflowRuns.teamId eq team) and (WorkflowRuns.starterId eq me) and (WorkflowRuns.createdAt less 10_000_000_000_000L) }.orderBy(WorkflowRuns.createdAt to SortOrder.DESC, WorkflowRuns.id to SortOrder.DESC).limit(51) },
                    "own, one status" to explain(analyze = true) { WorkflowRuns.select(columns).where { (WorkflowRuns.teamId eq team) and (WorkflowRuns.starterId eq me) and (WorkflowRuns.status eq "running") }.orderBy(WorkflowRuns.createdAt to SortOrder.DESC, WorkflowRuns.id to SortOrder.DESC).limit(51) },
                    "team, one workflow" to explain(analyze = true) { WorkflowRuns.select(columns).where { (WorkflowRuns.teamId eq team) and (WorkflowRuns.workflowId eq outer.id) }.orderBy(WorkflowRuns.createdAt to SortOrder.DESC, WorkflowRuns.id to SortOrder.DESC).limit(51) },
                    "engine claim" to explain(analyze = true) { WorkflowRuns.select(WorkflowRuns.id).where { (WorkflowRuns.status inList listOf("running", "waiting")) and (WorkflowRuns.leaseUntil lessEq 1_000L) }.orderBy(WorkflowRuns.leaseUntil to SortOrder.ASC).limit(50) },
                ).mapValues { it.value.joinToString("\n") }
            }
            plans.forEach { (name, plan) -> println("Workflow runs plan ($name, synthetic metadata only):\n$plan") }
            assertTrue("wfrun_starter" in plans.getValue("own, newest first"), plans.getValue("own, newest first"))
            assertTrue("wfrun_starter_status" in plans.getValue("own, one status"), plans.getValue("own, one status"))
            plans.filterKeys { it != "engine claim" }.forEach { (name, plan) -> assertFalse("Sort" in plan, "$name sorts: $plan") }
            assertTrue("wfrun_claim" in plans.getValue("engine claim"), plans.getValue("engine claim"))
        }
    }
}
