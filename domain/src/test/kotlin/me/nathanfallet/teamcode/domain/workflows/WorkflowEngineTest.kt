package me.nathanfallet.teamcode.domain.workflows

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState
import me.nathanfallet.teamcode.domain.cloud.DesiredState
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.waits.*
import kotlin.test.*

class WorkflowEngineTest {
    private val ada = Member("ada", "ada", Role.MEMBER, false, false)
    private val bob = Member("bob", "bob", Role.MEMBER, false, false)
    private val admin = Member("admin", "admin", Role.ADMIN, false, false)
    private fun who(m: Member, scopes: Set<ServiceScope>? = null) = Principal(m, "token-${m.id}", "team", scopes)

    private class Store : WorkflowRunStore {
        var clock = 1_000L
        val workflows = mutableMapOf<String, Workflow>()
        val runs = linkedMapOf<String, WorkflowRun>()
        val visits = linkedMapOf<String, NodeVisit>()
        val keys = mutableMapOf<String, String>()
        var starterGone = false
        override suspend fun now() = clock
        override suspend fun revalidate(actor: Principal) {}
        override suspend fun workflow(teamId: String, id: String) = workflows[id]
        override suspend fun start(actor: Principal, run: WorkflowRun, first: NodeVisit, idempotencyKey: String?, limit: Int): RunChange {
            idempotencyKey?.let { key -> keys[actor.member.id + key]?.let { return RunChange(runs.getValue(it), false) } }
            if (runs.values.count { it.starterId == actor.member.id && !it.status.terminal } >= limit) throw TeamFailure("run_limit", 409, "x")
            runs[run.id] = run; visits[first.id] = first; idempotencyKey?.let { keys[actor.member.id + it] = run.id }
            return RunChange(run, true)
        }
        override suspend fun find(teamId: String, id: String) = runs[id]
        val executions = mutableMapOf<String, String>()
        override suspend fun executionSession(tokenId: String) = executions[tokenId]
        override suspend fun runOfSession(teamId: String, sessionId: String): Pair<WorkflowRun, NodeVisit>? {
            val visit = visits.values.filter { it.sessionId == sessionId && it.status == VisitStatus.ACTIVE && it.kind == NodeKind.AGENT }.maxByOrNull { it.seq } ?: return null
            return runs.getValue(visit.runId).takeIf { !it.status.terminal }?.let { it to visit }
        }
        val openWaits = mutableSetOf<String>()
        override suspend fun sessionHasOpenWait(sessionId: String) = sessionId in openWaits
        /** Sessions of the fixture: s1 and s9 are ada's, s8 is bob's. */
        override suspend fun sessionOwner(teamId: String, sessionId: String) = mapOf("s1" to "ada", "s9" to "ada", "s8" to "bob")[sessionId]
        override suspend fun visits(runId: String) = visits.values.filter { it.runId == runId }.sortedBy { it.seq }
        override suspend fun list(teamId: String, starterId: String?, workflowId: String?, status: RunStatus?, before: String?, limit: Int) =
            WorkflowRunPage(runs.values.filter { (starterId == null || it.starterId == starterId) && (status == null || it.status == status) }.take(limit), null)
        override suspend fun requestCancel(teamId: String, id: String): Boolean { val r = runs[id] ?: return false; runs[id] = r.copy(cancelRequested = true); return true }
        override suspend fun starter(run: WorkflowRun) = if (starterGone) null else Principal(Member(run.starterId, run.starterId, Role.MEMBER, false, false), "t", run.teamId)
        override suspend fun claim(limit: Int, leaseMillis: Long) = runs.values.filter { !it.status.terminal }.take(limit).map { RunWork(it, visits(it.id)) }
        val released = mutableListOf<String>()
        override suspend fun release(ids: Collection<String>) { released += ids }
        override suspend fun children(ids: Set<String>) = ids.mapNotNull { id -> runs[id]?.let { id to ChildState(it.status, it.output) } }.toMap()
        private fun update(id: String, f: (WorkflowRun) -> WorkflowRun) { runs[id] = f(runs.getValue(id)) }
        override suspend fun dispatched(visitId: String, sessionId: String, bindRun: Boolean, at: Long): Boolean {
            val v = visits.getValue(visitId); visits[visitId] = v.copy(sessionId = sessionId, dispatchedAt = at)
            if (bindRun) update(v.runId) { it.copy(sessionId = sessionId) }; return true
        }
        override suspend fun waiting(visitId: String, waitId: String): Boolean {
            val v = visits.getValue(visitId); visits[visitId] = v.copy(waitId = waitId); update(v.runId) { it.copy(status = RunStatus.WAITING) }; return true
        }
        override suspend fun startChild(visitId: String, child: WorkflowRun, first: NodeVisit): Boolean {
            val v = visits.getValue(visitId); if (v.childRunId != null) return false
            runs[child.id] = child; visits[first.id] = first; visits[visitId] = v.copy(childRunId = child.id); return true
        }
        override suspend fun complete(visitId: String, status: VisitStatus, outcome: String?, output: String?, at: Long): Boolean {
            val v = visits.getValue(visitId); if (v.status != VisitStatus.ACTIVE) return false
            visits[visitId] = v.copy(status = status, outcome = outcome, output = output, finishedAt = at)
            update(v.runId) { if (it.status == RunStatus.WAITING) it.copy(status = RunStatus.RUNNING) else it }; return true
        }
        override suspend fun next(runId: String, visit: NodeVisit): Boolean { visits[visit.id] = visit; return true }
        override suspend fun finish(runId: String, status: RunStatus, failure: String?, output: String?, at: Long): Boolean {
            val r = runs.getValue(runId); if (r.status.terminal) return false
            runs[runId] = r.copy(status = status, failure = failure, output = output, finishedAt = at); return true
        }
    }

    private class Sessions : WorkflowSessions {
        val created = mutableListOf<List<String?>>()
        val messages = mutableListOf<Pair<String, String>>()
        val progress = mutableMapOf<String, SessionProgress>()
        var failCreate: TeamFailure? = null
        override suspend fun create(starter: Principal, name: String, profileId: String, model: String?, prompt: String, key: String): String {
            failCreate?.let { throw it }
            val id = "s${created.size + 1}"; created += listOf(id, name, profileId, prompt); progress[id] = SessionProgress(CloudSessionState.ACTIVE, DesiredState.RUNNING, null, null); return id
        }
        override suspend fun message(starter: Principal, sessionId: String, text: String, key: String) { messages += sessionId to text }
        override suspend fun progress(sessionIds: Set<String>) = progress.filterKeys { it in sessionIds }
    }

    private class Waits : WorkflowWaits {
        val asked = mutableListOf<List<Any?>>()
        val states = mutableMapOf<String, WaitState>()
        val cancelled = mutableListOf<String>()
        override suspend fun ask(starter: Principal, sessionId: String, assigneeId: String?, title: String, body: String?, fields: List<WaitField>, actions: List<WaitAction>, key: String): String {
            val id = "w${asked.size + 1}"; asked += listOf(id, sessionId, assigneeId, title, body); states[id] = WaitState(WaitStatus.OPEN, null, null); return id
        }
        override suspend fun states(ids: Set<String>) = states.filterKeys { it in ids }
        override suspend fun cancel(starter: Principal, id: String) { cancelled += id; states[id] = WaitState(WaitStatus.CANCELLED, null, null) }
    }

    private companion object {
        const val TELL = "\n\nWhen this step is done, call the `deliver` tool of the teamcode_workflow MCP server once, with "
        const val END = " as its `output`, then end your turn."
        const val ARTIFACT_CLOSING = TELL + "the id of the artifact that holds it, as a string" + END
        const val URL_CLOSING = TELL + "an https address on github.com, as a string" + END
        const val TEXT_CLOSING = TELL + "the text, as a string" + END
    }

    private val store = Store(); private val sessions = Sessions(); private val waits = Waits()
    private val engine = WorkflowEngine(store, sessions, waits)
    private val service = WorkflowRunService(store)

    private val deploy = WorkflowDefinition(
        "Deploy", null, "ship", SessionMode.SAME, listOf(WorkflowInput("pr", "PR")),
        listOf(WorkflowNode("ship", NodeKind.AGENT, "Ship", "Deploy {{inputs.pr}}", deliverable = Deliverable(DeliverableType.TEXT), transitions = listOf(Transition("delivered")))),
    )
    private val ticket = WorkflowDefinition(
        "Fix a ticket", null, "analyze", SessionMode.SAME, listOf(WorkflowInput("ticket", "Ticket")),
        listOf(
            WorkflowNode("analyze", NodeKind.AGENT, "Analyze", "Plan {{inputs.ticket}}", deliverable = Deliverable(DeliverableType.ARTIFACT), transitions = listOf(Transition("delivered", "review")), maxVisits = 2),
            WorkflowNode("review", NodeKind.HUMAN, "Review the plan", "See {{nodes.analyze.output}}", fields = listOf(WaitField("feedback", FieldType.TEXT, "Feedback")),
                actions = listOf(WaitAction("approve", "Approve"), WaitAction("request_changes", "Request changes", requires = listOf("feedback"))),
                transitions = listOf(Transition("approve", "implement"), Transition("request_changes", "analyze"))),
            WorkflowNode("implement", NodeKind.AGENT, "Implement", "Implement {{nodes.analyze.output}}", deliverable = Deliverable(DeliverableType.URL, domain = "github.com"), transitions = listOf(Transition("delivered", "deploy"))),
            WorkflowNode("deploy", NodeKind.WORKFLOW, "Deploy", workflowId = "wf-deploy", arguments = mapOf("pr" to "{{nodes.implement.output}}"), transitions = listOf(Transition("delivered"))),
        ),
    )
    init {
        store.workflows["wf-ticket"] = Workflow("wf-ticket", "team", 3, ticket, 1, 1, null)
        store.workflows["wf-deploy"] = Workflow("wf-deploy", "team", 1, deploy, 1, 1, null)
    }

    private fun code(block: suspend () -> Unit) = assertFailsWith<TeamFailure> { runBlocking { block() } }.code
    private suspend fun start(vararg inputs: Pair<String, String>, profile: String? = "p1", id: String = "wf-ticket") = service.start(who(ada), id, inputs.toMap(), profile, null).run
    private fun visits(run: WorkflowRun) = store.visits.values.filter { it.runId == run.id }.sortedBy { it.seq }
    private fun run(id: String) = store.runs.getValue(id)
    private suspend fun pass(times: Int = 1) = repeat(times) { engine.run() }

    @Test fun theTicketWorkflowRunsFromAnalysisToItsSubWorkflowWithABackwardLoop() = runBlocking {
        val run = start("ticket" to "T-42")
        assertEquals(RunStatus.RUNNING, run.status); assertEquals(1, visits(run).size)

        // The first agent node starts a session with its rendered instruction; the run keeps that session.
        pass()
        assertEquals(listOf("s1", "Fix a ticket: Analyze", "p1", "Plan T-42$ARTIFACT_CLOSING"), sessions.created.single())
        assertEquals("s1", run(run.id).sessionId); assertEquals("s1", visits(run).single().sessionId)
        assertEquals("not_found", code { service.deliver(who(bob), run.id, JsonPrimitive("a1")) }, "only the starter delivers")

        // A delivery of the wrong type is refused and stored nowhere; the right one moves the run to the human node.
        assertEquals("invalid_deliverable", code { service.deliver(who(ada), run.id, JsonPrimitive("not an id!")) })
        service.deliver(who(ada), run.id, JsonPrimitive("plan-1"))
        pass(); assertEquals(listOf("analyze", "review"), visits(run).map { it.nodeId })
        pass()
        assertEquals(listOf("w1", "s1", null, "Review the plan", "See plan-1"), waits.asked.single())
        assertEquals(RunStatus.WAITING, run(run.id).status)

        // Changes requested: the run goes back to analyze (second visit), then forward again.
        waits.states["w1"] = WaitState(WaitStatus.RESOLVED, "request_changes", "Feedback: split step 2")
        pass(2)
        assertEquals(listOf("analyze", "review", "analyze"), visits(run).map { it.nodeId }); assertEquals("Feedback: split step 2", visits(run)[1].output)
        pass()
        assertEquals("Plan T-42$ARTIFACT_CLOSING", sessions.messages.single().second, "same mode: the next instruction is a message of the same session")
        service.deliver(who(ada), run.id, JsonPrimitive("plan-2")); pass(2)
        waits.states["w2"] = WaitState(WaitStatus.RESOLVED, "approve", ""); pass(4)
        assertEquals(listOf("analyze", "review", "analyze", "review", "implement"), visits(run).map { it.nodeId })
        assertEquals("Implement plan-2$URL_CLOSING", sessions.messages.last().second, "the latest output of a node is the one templates read")

        // The URL must be on the node's domain.
        assertEquals("invalid_deliverable", code { service.deliver(who(ada), run.id, JsonPrimitive("https://evil.example/pr/7")) })
        service.deliver(who(ada), run.id, JsonPrimitive("https://github.com/acme/app/pull/7")); pass(2)

        // The sub-workflow starts as a child run of the same session and its output comes back.
        val child = store.runs.values.single { it.parentRunId == run.id }
        assertEquals(1, child.depth); assertEquals("s1", child.sessionId); assertEquals(mapOf("pr" to "https://github.com/acme/app/pull/7"), child.inputs)
        pass()
        assertEquals("Deploy https://github.com/acme/app/pull/7$TEXT_CLOSING", sessions.messages.last().second)
        service.deliver(who(ada), child.id, JsonPrimitive("deployed")); pass(2)
        assertEquals(RunStatus.SUCCEEDED, run(child.id).status); pass(2)
        assertEquals(RunStatus.SUCCEEDED, run(run.id).status); assertEquals("deployed", run(run.id).output)
        assertNotNull(run(run.id).finishedAt)
    }

    @Test fun everyClaimedRunIsReleasedAfterThePass() = runBlocking {
        val run = start("ticket" to "T"); pass()
        assertEquals(listOf(run.id), store.released)
    }

    @Test fun startingChecksInputsProfilesAndTheCap() = runBlocking {
        assertEquals("invalid_inputs", code { start() })
        assertEquals("invalid_inputs", code { start("ticket" to "T", "nope" to "x") })
        assertEquals("profile_required", code { start("ticket" to "T", profile = null) })
        assertEquals("not_found", code { start("ticket" to "T", id = "ghost") })
        assertEquals("credential_scope", code { service.start(who(ada, setOf(ServiceScope.CLOUD_WRITE)), "wf-ticket", mapOf("ticket" to "T"), "p1", null) })
        val first = service.start(who(ada), "wf-ticket", mapOf("ticket" to "T"), "p1", "k1"); val again = service.start(who(ada), "wf-ticket", mapOf("ticket" to "T"), "p1", "k1")
        assertTrue(first.created); assertFalse(again.created); assertEquals(first.run.id, again.run.id)
        repeat(WorkflowRunService.MAX_ACTIVE_RUNS - 1) { start("ticket" to "T") }
        assertEquals("run_limit", code { start("ticket" to "T") })
    }

    @Test fun aRunFailsWithAShortCodeWhenItsSessionOrItsWorkCannotGoOn() = runBlocking {
        val gone = start("ticket" to "T"); pass()
        sessions.progress["s1"] = SessionProgress(CloudSessionState.FAILED, DesiredState.RUNNING, null, null); pass()
        assertEquals("session_failed", run(gone.id).failure); assertEquals(VisitStatus.FAILED, visits(gone).single().status)

        // A turn that ended after the instruction without a delivery: the run says so. A turn that began before does not count.
        val silent = start("ticket" to "T"); pass()
        val s = visits(silent).single().sessionId!!; val at = visits(silent).single().dispatchedAt!!
        sessions.progress[s] = SessionProgress(CloudSessionState.ACTIVE, DesiredState.RUNNING, at - 1, "completed"); pass()
        assertEquals(RunStatus.RUNNING, run(silent.id).status)
        sessions.progress[s] = SessionProgress(CloudSessionState.ACTIVE, DesiredState.RUNNING, at + 5, "completed"); pass()
        assertEquals("ended_without_delivery", run(silent.id).failure)

        sessions.failCreate = TeamFailure("profile_unavailable", 400, "x")
        val refused = start("ticket" to "T"); pass(); assertEquals("profile_unavailable", run(refused.id).failure)
        sessions.failCreate = TeamFailure("busy", 503, "x")
        val busy = start("ticket" to "T"); pass(); assertEquals(RunStatus.RUNNING, run(busy.id).status, "a busy cloud is tried again")
        sessions.failCreate = null; store.starterGone = true
        val orphan = start("ticket" to "T").also { store.starterGone = false }; store.starterGone = true; pass(); assertEquals("starter_unavailable", run(orphan.id).failure)
    }

    @Test fun loopsAreBoundedByTheNodesVisitsAndTheRunsTotal() = runBlocking {
        val bounded = ticket.copy(nodes = ticket.nodes.map { if (it.id == "analyze") it.copy(maxVisits = 2) else it })
        store.workflows["wf-ticket"] = Workflow("wf-ticket", "team", 4, bounded, 1, 1, null)
        val run = start("ticket" to "T"); pass()
        repeat(2) {
            service.deliver(who(ada), run.id, JsonPrimitive("p$it")); pass(2)
            waits.states["w${it + 1}"] = WaitState(WaitStatus.RESOLVED, "request_changes", "again"); pass(2)
            if (it == 0) pass()
        }
        assertEquals("max_visits:analyze", run(run.id).failure)
        val total = ticket.copy(limits = WorkflowLimits(maxVisits = 2))
        store.workflows["wf-ticket"] = Workflow("wf-ticket", "team", 5, total, 1, 1, null)
        val short = start("ticket" to "T"); pass(); service.deliver(who(ada), short.id, JsonPrimitive("p")); pass(2)
        assertEquals(2, visits(short).size); waits.states["w3"] = WaitState(WaitStatus.RESOLVED, "approve", ""); pass(2)
        assertEquals("max_total_visits", run(short.id).failure)
    }

    @Test fun subWorkflowsAreBoundedInDepthAndFailTheParentWhenTheyFail() = runBlocking {
        store.workflows["wf-ticket"] = Workflow("wf-ticket", "team", 6, ticket.copy(limits = WorkflowLimits(maxDepth = 0)), 1, 1, null)
        val shallow = start("ticket" to "T"); pass(); service.deliver(who(ada), shallow.id, JsonPrimitive("p")); pass(2)
        waits.states["w1"] = WaitState(WaitStatus.RESOLVED, "approve", ""); pass(3); service.deliver(who(ada), shallow.id, JsonPrimitive("https://github.com/a/b/pull/1")); pass(2)
        assertEquals("max_depth", run(shallow.id).failure)

        store.workflows["wf-ticket"] = Workflow("wf-ticket", "team", 7, ticket, 1, 1, null)
        val parent = start("ticket" to "T2"); pass(); service.deliver(who(ada), parent.id, JsonPrimitive("p")); pass(2)
        waits.states["w2"] = WaitState(WaitStatus.RESOLVED, "approve", ""); pass(3); service.deliver(who(ada), parent.id, JsonPrimitive("https://github.com/a/b/pull/2")); pass(2)
        val child = store.runs.values.single { it.parentRunId == parent.id }
        service.cancel(who(ada), child.id); pass(2)
        assertEquals(RunStatus.CANCELLED, run(child.id).status); pass(2)
        assertEquals("sub_workflow_cancelled", run(parent.id).failure)
    }

    @Test fun cancellingEndsTheRunWithdrawsItsWaitAndCascadesToItsChild() = runBlocking {
        val run = start("ticket" to "T"); pass(); service.deliver(who(ada), run.id, JsonPrimitive("p")); pass(3)
        assertEquals("not_found", code { service.cancel(who(bob), run.id) })
        service.cancel(who(admin), run.id); pass()
        assertEquals(RunStatus.CANCELLED, run(run.id).status); assertEquals(listOf("w1"), waits.cancelled)
        assertEquals(VisitStatus.CANCELLED, visits(run).last().status)
        assertEquals("run_finished", code { service.cancel(who(ada), run.id) })
        assertEquals("nothing_to_deliver", code { service.deliver(who(ada), run.id, JsonPrimitive("x")) })
    }

    @Test fun runsAreVisibleToTheirStarterAndToAdministrators() = runBlocking {
        val run = start("ticket" to "T")
        assertEquals(run.id, service.get(who(ada), run.id).first.id); assertEquals(run.id, service.get(who(admin), run.id).first.id)
        assertEquals("not_found", code { service.get(who(bob), run.id) })
        assertEquals(1, service.list(who(ada), false, null, "running", null, 10).runs.size)
        assertEquals(0, service.list(who(bob), false, null, null, null, 10).runs.size)
        assertEquals("forbidden", code { service.list(who(bob), true, null, null, null, 10) })
        assertEquals(1, service.list(who(admin), true, null, null, null, 10).runs.size)
        assertEquals("invalid_request", code { service.list(who(ada), false, null, "weird", null, 10) })
    }

    @Test fun deliverablesAreCheckedByTypeBeforeTheyAreAccepted() {
        fun ok(d: Deliverable, v: kotlinx.serialization.json.JsonElement) = Deliverables.validate(d, v)
        assertEquals("plan-1", ok(Deliverable(DeliverableType.ARTIFACT), JsonPrimitive("plan-1")))
        assertFailsWith<TeamFailure> { ok(Deliverable(DeliverableType.TEXT), JsonPrimitive(" ")) }
        assertFailsWith<TeamFailure> { ok(Deliverable(DeliverableType.URL), JsonPrimitive("http://example.com")) }
        ok(Deliverable(DeliverableType.URL, domain = "github.com"), JsonPrimitive("https://api.github.com/x"))
        assertFailsWith<TeamFailure> { ok(Deliverable(DeliverableType.URL, domain = "github.com"), JsonPrimitive("https://notgithub.com/x")) }
        val schema = kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"object","required":["verdict"],"properties":{"verdict":{"type":"string","enum":["ok","ko"]},"score":{"type":"integer"}}}""")
        val json = Deliverable(DeliverableType.JSON, schema = schema)
        ok(json, kotlinx.serialization.json.Json.parseToJsonElement("""{"verdict":"ok","score":3}"""))
        assertFailsWith<TeamFailure> { ok(json, kotlinx.serialization.json.Json.parseToJsonElement("""{"score":3}""")) }
        assertFailsWith<TeamFailure> { ok(json, kotlinx.serialization.json.Json.parseToJsonElement("""{"verdict":"maybe"}""")) }
        assertFailsWith<TeamFailure> { ok(json, kotlinx.serialization.json.Json.parseToJsonElement("""{"verdict":"ok","score":"3"}""")) }
        assertFailsWith<TeamFailure> { ok(json, JsonPrimitive("""{"verdict":"ok"}""")) }
        assertFailsWith<TeamFailure> { ok(Deliverable(DeliverableType.TEXT), JsonObject(emptyMap())) }
    }

    @Test fun templatesReadInputsAndLatestOutputsAndNothingElse() {
        val visits = listOf(
            NodeVisit("v1", "r", 1, "a", NodeKind.AGENT, VisitStatus.DONE, output = "old", startedAt = 1),
            NodeVisit("v2", "r", 2, "a", NodeKind.AGENT, VisitStatus.DONE, output = "new", startedAt = 2),
            NodeVisit("v3", "r", 3, "b", NodeKind.AGENT, VisitStatus.ACTIVE, startedAt = 3),
        )
        assertEquals("T-1|new||\$1|", WorkflowTemplate.render("{{inputs.t}}|{{ nodes.a.output }}|{{nodes.b.output}}|\$1|{{x}}", mapOf("t" to "T-1"), WorkflowTemplate.outputs(visits)))
    }

    @Test fun theWorkflowWaitNeverWakesTheSession() {
        val wait = Wait("w", "team", WaitSource.WAIT, WaitKind.WORKFLOW, WaitResolver.HUMAN, WaitStatus.RESOLVED, "s1", null, null, "ada", null, null, "t", null, emptyList(), emptyList(), emptyList(), null, 1, 2)
        assertFalse(TurnFailedWait.wakes(wait, "approve")); assertTrue(TurnFailedWait.wakes(wait.copy(kind = WaitKind.HUMAN_REQUEST), "approve"))
    }

    @Test fun theAgentDeliversBySessionAndOnlyTheStarterOrTheSessionsCredentialMay() = runBlocking {
        val execution = who(ada, setOf(ServiceScope.INFERENCE, ServiceScope.WORKFLOW_SESSION)).copy(tokenId = "tcex-1")
        store.executions["tcex-1"] = "s1"
        val run = start("ticket" to "T-42"); pass()
        assertEquals("s1", run(run.id).sessionId)
        // Another member, an unknown session and a session of another member all look the same.
        assertEquals("not_found", code { service.deliverBySession(who(bob), "s1", JsonPrimitive("plan-1")) })
        assertEquals("not_found", code { service.deliverBySession(who(ada), "s8", JsonPrimitive("plan-1")) })
        assertEquals("not_found", code { service.deliverBySession(who(ada), "nope", JsonPrimitive("plan-1")) })
        // The starter must name the session; a session of hers that runs no workflow step has nothing to deliver.
        assertEquals("invalid_request", code { service.deliverBySession(who(ada), null, JsonPrimitive("plan-1")) })
        assertEquals("nothing_to_deliver", code { service.deliverBySession(who(ada), "s9", JsonPrimitive("plan-1")) })
        // Same validation as the run route, then the same conditional completion: a second delivery finds nothing.
        assertEquals("invalid_deliverable", code { service.deliverBySession(execution, null, JsonPrimitive("not an id!")) })
        service.deliverBySession(execution, "s8", JsonPrimitive("plan-1"))
        assertEquals(VisitStatus.DONE, visits(run).single().status); assertEquals("plan-1", visits(run).single().output)
        assertEquals("nothing_to_deliver", code { service.deliverBySession(execution, null, JsonPrimitive("plan-1")) })
    }

    @Test fun theStopHookAsksWhetherTheAgentStillHasToDeliverOrAsk() = runBlocking {
        val execution = who(ada, setOf(ServiceScope.INFERENCE, ServiceScope.WORKFLOW_SESSION)).copy(tokenId = "tcex-1")
        store.executions["tcex-1"] = "s1"
        val run = start("ticket" to "T-42"); pass()
        val pending = service.pendingBySession(execution, "s8")
        assertTrue(pending.needsAction); assertEquals(run.id, pending.runId); assertEquals("Analyze", pending.nodeTitle); assertEquals(DeliverableType.ARTIFACT, pending.deliverable?.type)
        assertTrue(service.pendingBySession(who(ada), "s1").needsAction, "the starter names the session")
        // An open wait of any kind (the agent asked a person) is not pending, whatever the credential.
        store.openWaits += "s1"
        assertFalse(service.pendingBySession(execution, null).needsAction)
        assertEquals(PendingDelivery(false), service.pendingBySession(who(ada), "s1"))
        store.openWaits -= "s1"
        // Another member's session, an unknown one and another member's key look the same; her own session without a step has nothing pending.
        assertEquals("not_found", code { service.pendingBySession(who(bob), "s1") })
        assertEquals("not_found", code { service.pendingBySession(who(ada), "s8") })
        assertEquals("not_found", code { service.pendingBySession(who(ada), "nope") })
        assertEquals("invalid_request", code { service.pendingBySession(who(ada), null) })
        assertEquals(PendingDelivery(false), service.pendingBySession(who(ada), "s9"))
        // Same credentials as the delivery: a reader and a scope without a session are refused.
        assertEquals("credential_scope", code { service.pendingBySession(who(ada, setOf(ServiceScope.CLOUD_READ)), "s1") })
        assertEquals("credential_scope", code { service.pendingBySession(who(ada, setOf(ServiceScope.WORKFLOW_SESSION)).copy(tokenId = "ci-key"), "s1") })
        // Delivered: nothing is pending any more.
        service.deliverBySession(execution, null, JsonPrimitive("plan-1"))
        assertFalse(service.pendingBySession(execution, null).needsAction)
    }

    @Test fun anExecutionCredentialDeliversOnlyForItsSessionAndDoesNothingElse() = runBlocking {
        val run = start("ticket" to "T-42"); pass()
        val unbound = who(ada, setOf(ServiceScope.WORKFLOW_SESSION)).copy(tokenId = "ci-key")
        assertEquals("credential_scope", code { service.deliverBySession(unbound, "s1", JsonPrimitive("plan-1")) }, "the scope on a key bound to no session")
        val old = who(ada, setOf(ServiceScope.INFERENCE, ServiceScope.ARTIFACTS_WRITE)).copy(tokenId = "tcex-old")
        store.executions["tcex-old"] = "s1"
        assertEquals("credential_scope", code { service.deliverBySession(old, "s1", JsonPrimitive("plan-1")) }, "an execution credential from before the scope")
        // Bound to s9, which runs no step: whatever it names, it cannot deliver into the run of s1.
        val other = who(ada, setOf(ServiceScope.INFERENCE, ServiceScope.WORKFLOW_SESSION)).copy(tokenId = "tcex-9")
        store.executions["tcex-9"] = "s9"
        assertEquals("nothing_to_deliver", code { service.deliverBySession(other, "s1", JsonPrimitive("plan-1")) })
        // The scope grants nothing else: not the run route, not the other cloud actions.
        assertEquals("credential_scope", code { service.deliver(other, run.id, JsonPrimitive("plan-1")) })
        assertEquals("credential_scope", code { service.get(other, run.id) })
        val check = AuthorizeCredentialUseCase()
        check.check(other, CredentialAction.WORKFLOW_DELIVER)
        for (action in listOf(CredentialAction.CLOUD_READ, CredentialAction.CLOUD_WRITE, CredentialAction.WAITS_READ, CredentialAction.WAITS_WRITE, CredentialAction.INTERACTIVE))
            assertEquals("credential_scope", code { check.check(other, action) })
        // A cloud key keeps its usual reach: it names the session.
        service.deliverBySession(who(ada, setOf(ServiceScope.CLOUD_WRITE)), "s1", JsonPrimitive("plan-1"))
        assertEquals(VisitStatus.DONE, visits(run).single().status)
    }
}
