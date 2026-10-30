package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.team.TeamEventAudience
import me.nathanfallet.teamcode.domain.team.TeamEventType
import me.nathanfallet.teamcode.domain.cloud.CloudCreateOutcome
import me.nathanfallet.teamcode.domain.cloud.CloudSessionDraft
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState
import me.nathanfallet.teamcode.domain.cloud.DesiredState
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.ServiceScope
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.waits.*
import me.nathanfallet.teamcode.domain.workflows.*
import me.nathanfallet.teamcode.infrastructure.database.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.database.tables.Waits as WaitsTable
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
            // A listed run names the step in progress, read for the whole page at once.
            assertEquals("work", serviceB.list(w.admin, true, null, "running", null, 10).current[run.id]?.nodeId)

            // Two replicas claiming together: one gets the run, the other nothing, until it is released.
            val claims = coroutineScope { listOf(storeA, storeB).map { async(Dispatchers.IO) { it.claim(10, 60_000).map { work -> work.run.id } } }.awaitAll() }
            assertEquals(listOf(run.id), claims.flatten(), "exactly one replica holds the run")
            assertEquals(emptyList(), storeB.claim(10, 60_000)); storeA.release(listOf(run.id)); assertEquals(listOf(run.id), storeB.claim(10, 60_000).map { it.run.id }); storeB.release(listOf(run.id))

            // The engine, alternating replicas, takes the run through its nodes; a delivery of the wrong type is refused.
            engineA.run(); assertEquals(listOf("Fix T-42\n\nWhen this step is done, call the `deliver` tool of the teamcode_workflow MCP server once, with the text, as a string as its `output`, then end your turn."), sessions.created)
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

    @Test @Timeout(300)
    fun runChangesArePublishedToTheFeedInTheirTransaction() = runBlocking {
        AutomationWorld.run(93) { w ->
            val inner = WorkflowService(SqlWorkflowStore(w.a.database)).create(w.admin, definition("Events"))
            val store = SqlWorkflowRunStore(w.a.database); val service = WorkflowRunService(store)
            val sessions = Sessions(); val waits = Waits(); val engine = WorkflowEngine(store, sessions, waits)
            val feed = SqlTeamEventStore(w.a.database); val head = feed.head(w.teamId)
            val types = setOf(TeamEventType.WORKFLOW_RUN_UPDATED)
            fun audience(who: Principal, team: Boolean = false) = TeamEventAudience(who.member.id, sessions = true, runs = false, teamSessions = team, teamRuns = false)

            val run = service.start(w.member, inner.id, emptyMap(), "p1", null).run
            engine.run()
            service.deliver(w.member, run.id, JsonPrimitive("done")); engine.run(); engine.run()
            assertEquals(RunStatus.WAITING, service.get(w.member, run.id).first.status)
            waits.states["w-1"] = WaitState(WaitStatus.RESOLVED, "approve", ""); engine.run(); engine.run()
            assertEquals(RunStatus.SUCCEEDED, service.get(w.member, run.id).first.status)

            val mine = feed.read(w.teamId, audience(w.member), head, types, 100).events
            val statuses = mine.map { Json.parseToJsonElement(it.data).jsonObject.getValue("status").jsonPrimitive.content }
            assertEquals("running", statuses.first(), "the start is published"); assertTrue("waiting" in statuses, statuses.toString()); assertEquals("succeeded", statuses.last(), "the end is published")
            assertTrue(mine.all { it.subjectId == run.id && it.ownerId == w.member.member.id && it.type == TeamEventType.WORKFLOW_RUN_UPDATED })
            assertEquals(mine.map { it.seq }, mine.map { it.seq }.sorted())
            val data = Json.parseToJsonElement(mine.first().data).jsonObject
            assertEquals(run.id, data.getValue("runId").jsonPrimitive.content); assertEquals(inner.id, data.getValue("workflowId").jsonPrimitive.content)
            assertEquals(emptyList(), feed.read(w.teamId, audience(w.admin), head, types, 100).events, "another member does not see the run")
            assertEquals(mine.map { it.seq }, feed.read(w.teamId, audience(w.admin, team = true), head, types, 100).events.map { it.seq }, "an administrator who reads the team's sessions does")
            // A change that does nothing publishes nothing: the finished run cannot be finished again.
            val before = feed.head(w.teamId)
            assertFalse(store.finish(run.id, RunStatus.FAILED, "x", null, 99))
            assertEquals(before, feed.head(w.teamId))
        }
    }

    @Test @Timeout(300)
    fun theAgentDeliversBySessionAcrossReplicas() = runBlocking {
        AutomationWorld.run(92) { w ->
            val definitions = WorkflowService(SqlWorkflowStore(w.a.database))
            val solo = definitions.create(w.admin, definition("Solo"))
            val storeA = SqlWorkflowRunStore(w.a.database); val storeB = SqlWorkflowRunStore(w.b.database)
            val serviceA = WorkflowRunService(storeA); val serviceB = WorkflowRunService(storeB)
            suspend fun session(name: String) = (w.a.sessions.create(w.member, null, CloudSessionDraft(name, w.profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
            val own = session("Own"); val other = session("Other"); val idle = session("Idle")
            val ids = ArrayDeque(listOf(own.id, other.id))
            val sessions = object : WorkflowSessions {
                override suspend fun create(starter: Principal, name: String, profileId: String, model: String?, prompt: String, key: String) = ids.removeFirst()
                override suspend fun message(starter: Principal, sessionId: String, text: String, key: String) = Unit
                override suspend fun progress(sessionIds: Set<String>) = sessionIds.associateWith { SessionProgress(CloudSessionState.ACTIVE, DesiredState.RUNNING, null, null) }
            }
            val engine = WorkflowEngine(storeA, sessions, Waits())
            val first = serviceA.start(w.member, solo.id, mapOf("ticket" to "T-1"), "p1", null).run
            val second = serviceA.start(w.member, solo.id, mapOf("ticket" to "T-2"), "p1", null).run
            engine.run()
            // Whichever run the engine served first got the first session.
            val (run, elsewhere) = if (serviceA.get(w.member, first.id).first.sessionId == own.id) first to second else second to first
            assertEquals(own.id, serviceA.get(w.member, run.id).first.sessionId); assertEquals(other.id, serviceA.get(w.member, elsewhere.id).first.sessionId)

            // The credential of the session's execution, and a key carrying the same scope without a session.
            suspend fun token(id: String, scopes: String) = w.a.database.query {
                val now = System.currentTimeMillis()
                Tokens.insert {
                    it[Tokens.id] = id; it[memberId] = w.member.member.id; it[tokenHash] = "hash-$id"; it[device] = "cloud session"; it[deviceId] = null
                    it[createdAt] = now; it[expiresAt] = now + 3_600_000; it[serviceScopes] = scopes
                }
            }
            val tokenId = java.util.UUID.randomUUID().toString(); val keyId = java.util.UUID.randomUUID().toString()
            token(tokenId, "[\"inference\",\"workflow:session\"]"); token(keyId, "[\"inference\",\"workflow:session\"]")
            w.a.database.query {
                val now = System.currentTimeMillis()
                CloudInferenceCredentials.insert { it[CloudInferenceCredentials.tokenId] = tokenId; it[sessionId] = own.id; it[epoch] = 1; it[teamId] = w.teamId; it[createdAt] = now; it[expiresAt] = now + 3_600_000 }
            }
            val agent = w.member.copy(tokenId = tokenId, serviceScopes = setOf(ServiceScope.INFERENCE, ServiceScope.WORKFLOW_SESSION))
            assertEquals(own.id, storeB.executionSession(tokenId)); assertNull(storeB.executionSession(keyId))
            refusedWith("credential_scope") { serviceB.deliverBySession(agent.copy(tokenId = keyId), own.id, JsonPrimitive("x")) }
            refusedWith("credential_scope") { serviceB.deliverBySession(agent.copy(serviceScopes = setOf(ServiceScope.INFERENCE)), own.id, JsonPrimitive("x")) }

            // Unknown, another member's and not-running sessions; a bad output is refused and stored nowhere.
            refusedWith("not_found") { serviceB.deliverBySession(w.member, "no-such-session", JsonPrimitive("x")) }
            refusedWith("not_found") { serviceB.deliverBySession(w.admin, own.id, JsonPrimitive("x")) }
            refusedWith("nothing_to_deliver") { serviceB.deliverBySession(w.member, idle.id, JsonPrimitive("x")) }
            refusedWith("invalid_deliverable") { serviceB.deliverBySession(agent, null, kotlinx.serialization.json.JsonObject(emptyMap())) }
            assertEquals(VisitStatus.ACTIVE, serviceA.get(w.member, run.id).second.single().status)

            // The same delivery from both replicas at once: exactly one wins, the other finds nothing to deliver. The credential is
            // confined to its session, whatever the body names, so the other run is untouched.
            val results = coroutineScope {
                listOf(serviceA to w.member, serviceB to agent).map { (service, who) ->
                    async(Dispatchers.IO) { runCatching { service.deliverBySession(who, if (who === agent) other.id else own.id, JsonPrimitive("fixed")) } }
                }.awaitAll()
            }
            assertEquals(1, results.count { it.isSuccess }, results.toString())
            assertEquals("nothing_to_deliver", results.single { it.isFailure }.exceptionOrNull().let { (it as TeamFailure).code })
            val (delivered, visits) = serviceB.get(w.member, run.id)
            assertEquals("fixed", visits.single().output); assertEquals(RunStatus.RUNNING, delivered.status)
            assertEquals(VisitStatus.ACTIVE, serviceB.get(w.member, elsewhere.id).second.single().status, "an execution credential never reaches another session's run")
            refusedWith("nothing_to_deliver") { serviceA.deliverBySession(agent, null, JsonPrimitive("again")) }

            // The plan of the lookup, on a table seeded at a larger scale.
            w.a.database.query {
                val runIds = (1..2_000).map { java.util.UUID.randomUUID().toString() }
                WorkflowRuns.batchInsert(runIds.withIndex().toList(), shouldReturnGeneratedValues = false) { (n, id) ->
                    this[WorkflowRuns.id] = id; this[WorkflowRuns.teamId] = "other-${n % 40}"; this[WorkflowRuns.workflowId] = "wf-seed"; this[WorkflowRuns.workflowName] = "Seed"
                    this[WorkflowRuns.workflowVersion] = 1; this[WorkflowRuns.definition] = "{}"; this[WorkflowRuns.starterId] = w.admin.member.id; this[WorkflowRuns.status] = if (n % 3 == 0) "succeeded" else "running"
                    this[WorkflowRuns.inputs] = "{}"; this[WorkflowRuns.depth] = 0; this[WorkflowRuns.cancelRequested] = false; this[WorkflowRuns.leaseUntil] = 1L shl 50
                    this[WorkflowRuns.createdAt] = n.toLong(); this[WorkflowRuns.updatedAt] = n.toLong()
                }
                (0 until 2_000 * 100).chunked(5_000).forEach { chunk -> WorkflowVisits.batchInsert(chunk, shouldReturnGeneratedValues = false) { n ->
                    this[WorkflowVisits.id] = java.util.UUID.randomUUID().toString(); this[WorkflowVisits.runId] = runIds[n / 100]; this[WorkflowVisits.seq] = n % 100 + 1
                    this[WorkflowVisits.nodeId] = "work"; this[WorkflowVisits.kind] = "agent"; this[WorkflowVisits.status] = if (n % 100 == 99 && n % 3 != 0) "active" else "done"
                    this[WorkflowVisits.sessionId] = "seed-session-${n / 100}"; this[WorkflowVisits.startedAt] = n.toLong()
                } }
            }
            val team = w.teamId
            val plan = w.a.database.query {
                explain(analyze = true) {
                    (WorkflowVisits innerJoin WorkflowRuns).selectAll().where {
                        (WorkflowVisits.sessionId eq "seed-session-1999") and (WorkflowVisits.status eq "active") and (WorkflowVisits.kind eq "agent") and
                            (WorkflowRuns.teamId eq team) and (WorkflowRuns.status inList listOf("running", "waiting"))
                    }.orderBy(WorkflowVisits.seq to SortOrder.DESC).limit(1)
                }.joinToString("\n")
            }
            println("Workflow delivery by session plan (seeded: 2000 runs, 200000 visits, synthetic metadata only):\n$plan")
            assertTrue("wfvisit_session" in plan, plan)
        }
    }

    @Test @Timeout(300)
    fun theStopHookQuestionSeesStepsAndOpenWaitsAcrossReplicas() = runBlocking {
        AutomationWorld.run(93) { w ->
            val definitions = WorkflowService(SqlWorkflowStore(w.a.database))
            val solo = definitions.create(w.admin, definition("Solo"))
            val storeA = SqlWorkflowRunStore(w.a.database); val storeB = SqlWorkflowRunStore(w.b.database)
            val serviceA = WorkflowRunService(storeA); val serviceB = WorkflowRunService(storeB)
            suspend fun session(name: String) = (w.a.sessions.create(w.member, null, CloudSessionDraft(name, w.profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
            val own = session("Own"); val idle = session("Idle")
            val sessions = object : WorkflowSessions {
                override suspend fun create(starter: Principal, name: String, profileId: String, model: String?, prompt: String, key: String) = own.id
                override suspend fun message(starter: Principal, sessionId: String, text: String, key: String) = Unit
                override suspend fun progress(sessionIds: Set<String>) = sessionIds.associateWith { SessionProgress(CloudSessionState.ACTIVE, DesiredState.RUNNING, null, null) }
            }
            val run = serviceA.start(w.member, solo.id, mapOf("ticket" to "T-1"), "p1", null).run
            WorkflowEngine(storeA, sessions, Waits()).run()

            // The step runs and nothing was asked: pending, on either replica, naming the step and what it expects.
            for (service in listOf(serviceA, serviceB)) {
                val pending = service.pendingBySession(w.member, own.id)
                assertTrue(pending.needsAction); assertEquals(run.id, pending.runId); assertEquals("Work", pending.nodeTitle); assertEquals(DeliverableType.TEXT, pending.deliverable?.type)
            }
            // Another member's session, an unknown one and a session without a step.
            refusedWith("not_found") { serviceB.pendingBySession(w.admin, own.id) }
            refusedWith("not_found") { serviceB.pendingBySession(w.member, "no-such-session") }
            assertFalse(serviceB.pendingBySession(w.member, idle.id).needsAction)

            // An open wait of the session, whatever its kind, is the agent having asked; a resolved one is not.
            suspend fun wait(status: String, kind: String) = w.a.database.query {
                WaitsTable.insert {
                    it[id] = java.util.UUID.randomUUID().toString(); it[teamId] = w.teamId; it[sessionId] = own.id; it[WaitsTable.kind] = kind; it[resolver] = "human"; it[WaitsTable.status] = status
                    it[assigneeId] = w.member.member.id; it[title] = "Question"; it[refs] = "[]"; it[fieldDefs] = "[]"; it[actions] = "[]"; it[createdAt] = 1L
                }
            }
            wait("resolved", "human_request"); wait("cancelled", "question")
            assertTrue(serviceB.pendingBySession(w.member, own.id).needsAction, "closed waits do not count")
            wait("open", "human_request")
            for (service in listOf(serviceA, serviceB)) assertFalse(service.pendingBySession(w.member, own.id).needsAction, "the agent asked a person")
            // Delivered: nothing pending either.
            w.a.database.query { WaitsTable.deleteWhere { WaitsTable.sessionId eq own.id } }
            serviceA.deliverBySession(w.member, own.id, JsonPrimitive("done"))
            assertFalse(serviceB.pendingBySession(w.member, own.id).needsAction)

            // The plan of the wait lookup, on a waits table seeded at a larger scale (200,000 rows over the two sessions a member may keep here, a few open in one of them).
            val seeded = listOf(own.id, idle.id)
            w.a.database.query {
                (0 until 200_000).chunked(5_000).forEach { chunk -> WaitsTable.batchInsert(chunk, shouldReturnGeneratedValues = false) { n ->
                    this[WaitsTable.id] = java.util.UUID.randomUUID().toString(); this[WaitsTable.teamId] = w.teamId; this[WaitsTable.sessionId] = seeded[n % 2]; this[WaitsTable.kind] = "human_request"
                    this[WaitsTable.resolver] = "human"; this[WaitsTable.status] = if (n % 50_000 == 2) "open" else "resolved"; this[WaitsTable.assigneeId] = w.member.member.id
                    this[WaitsTable.title] = "Seed"; this[WaitsTable.refs] = "[]"; this[WaitsTable.fieldDefs] = "[]"; this[WaitsTable.actions] = "[]"; this[WaitsTable.createdAt] = n.toLong()
                } }
            }
            for ((label, session) in listOf("a session with an open wait" to seeded[0], "a session without one" to seeded[1])) {
                assertEquals(label.endsWith("open wait"), storeB.sessionHasOpenWait(session))
                val plan = w.a.database.query {
                    explain(analyze = true) { WaitsTable.select(WaitsTable.id).where { (WaitsTable.sessionId eq session) and (WaitsTable.status eq WaitStatus.OPEN.wire) }.limit(1) }.joinToString("\n")
                }
                println("Open wait of a session ($label; seeded: 200000 waits over 2 sessions, synthetic metadata only):\n$plan")
                assertTrue("wait_session" in plan, plan)
            }
        }
    }
}
