package me.nathanfallet.teamcode.domain.workflows

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState
import me.nathanfallet.teamcode.domain.cloud.DesiredState
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.waits.WaitStatus

/**
 * Moves unfinished runs on, one visit at a time (docs/workflows.md). Nothing is kept in memory: a pass claims the runs
 * nobody holds, reads what they wait for in a few batched reads, and records each step in the store, so a restarted or
 * second replica continues from the stored visits. A step that fails for a reason that may pass (the cloud being busy)
 * leaves the run as it is for the next pass; one that cannot succeed ends the run with a short failure code.
 */
class WorkflowEngine(
    private val store: WorkflowRunStore,
    private val sessions: WorkflowSessions,
    private val waits: WorkflowWaits,
) {
    suspend fun run(): Int {
        val claimed = store.claim(BATCH, LEASE_MILLIS)
        if (claimed.isEmpty()) return 0
        val active = claimed.mapNotNull { it.active }
        val progress = sessions.progress(active.filter { it.kind == NodeKind.AGENT }.mapNotNull { it.sessionId }.toSet())
        val waitStates = waits.states(active.mapNotNull { it.waitId }.toSet())
        val children = store.children(active.mapNotNull { it.childRunId }.toSet())
        val context = Context(progress, waitStates, children)
        try {
            for (work in claimed) {
                try { step(work, context) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* released below: the next pass tries again */ }
            }
        } finally {
            // Also when cancelled: the lease would otherwise hold the runs back for its whole length.
            withContext(NonCancellable) { try { store.release(claimed.map { it.run.id }) } catch (_: Exception) {} }
        }
        return claimed.size
    }

    private class Context(val progress: Map<String, SessionProgress>, val waits: Map<String, WaitState>, val children: Map<String, ChildState>)

    private suspend fun step(work: RunWork, context: Context) {
        val run = work.run
        val now = store.now()
        if (run.cancelRequested) return cancel(work, now)
        if (now - run.createdAt > MAX_AGE_MILLIS) return fail(work, "expired", now)
        val visit = work.active ?: return transition(work, now)
        val node = run.definition.nodes.firstOrNull { it.id == visit.nodeId } ?: return fail(work, "node_missing", now)
        when (visit.kind) {
            NodeKind.AGENT -> agent(work, visit, node, context, now)
            NodeKind.HUMAN -> human(work, visit, node, context, now)
            NodeKind.WORKFLOW -> sub(work, visit, node, context, now)
        }
    }

    /** The last visit is done: follow its outcome to the next node, or end the run. */
    private suspend fun transition(work: RunWork, now: Long) {
        val run = work.run; val last = work.visits.lastOrNull() ?: return fail(work, "no_visit", now)
        if (last.status != VisitStatus.DONE) return fail(work, "visit_${last.status.wire}", now)
        val node = run.definition.nodes.first { it.id == last.nodeId }
        val target = node.transitions.firstOrNull { it.on == last.outcome } ?: return fail(work, "no_transition", now)
        if (target.to == null) { store.finish(run.id, RunStatus.SUCCEEDED, null, last.output, now); return }
        val next = run.definition.nodes.firstOrNull { it.id == target.to } ?: return fail(work, "node_missing", now)
        if (work.visits.count { it.nodeId == next.id } >= next.maxVisits) return fail(work, "max_visits:${next.id}", now)
        if (work.visits.size >= run.definition.limits.maxVisits) return fail(work, "max_total_visits", now)
        store.next(run.id, visit(run.id, work.visits.size + 1, next, now))
    }

    private suspend fun agent(work: RunWork, visit: NodeVisit, node: WorkflowNode, context: Context, now: Long) {
        val sessionId = visit.sessionId
        if (sessionId == null) return dispatch(work, visit, node, now)
        val progress = context.progress[sessionId] ?: return fail(work, "session_missing", now)
        when {
            progress.state == CloudSessionState.FAILED -> fail(work, "session_failed", now)
            progress.state == CloudSessionState.TERMINATED || progress.desired == DesiredState.TERMINATED -> fail(work, "session_deleted", now)
            // The turn that took the instruction ended and nothing was delivered; a turn that started before it (the agent
            // finishing the previous node) does not count.
            progress.turnEnd != null && (progress.turnStartedAt ?: 0) >= (visit.dispatchedAt ?: Long.MAX_VALUE) -> fail(work, "ended_without_delivery", now)
        }
    }

    /** Sends the node's instruction to the run's session, or starts a session for it. */
    private suspend fun dispatch(work: RunWork, visit: NodeVisit, node: WorkflowNode, now: Long) {
        val run = work.run
        val starter = store.starter(run) ?: return fail(work, "starter_unavailable", now)
        var prompt = WorkflowTemplate.render(node.instruction.orEmpty(), run.inputs, WorkflowTemplate.outputs(work.visits))
        val key = "workflow-visit-${visit.id}"
        try {
            val reuse = run.definition.sessionMode == SessionMode.SAME && run.sessionId != null
            if (reuse) {
                sessions.message(starter, run.sessionId!!, prompt, key)
                store.dispatched(visit.id, run.sessionId, bindRun = false, at = now)
            } else {
                val profile = node.profileId ?: run.profileId ?: return fail(work, "profile_missing", now)
                if (run.definition.sessionMode == SessionMode.HANDOFF) prompt = handoff(prompt, work.visits)
                val created = sessions.create(starter, "${run.workflowName}: ${node.title}".take(120), profile, node.model, prompt, key)
                store.dispatched(visit.id, created, bindRun = true, at = now)
            }
        } catch (error: TeamFailure) {
            if (!error.retryable) fail(work, error.code, now)
        }
    }

    /** A new session starts without the conversation: what the earlier nodes delivered goes in its first message. */
    private fun handoff(prompt: String, visits: List<NodeVisit>): String {
        val done = visits.filter { it.status == VisitStatus.DONE && it.output != null }
        if (done.isEmpty()) return prompt
        return buildString {
            append(prompt).append("\n\nWhat the earlier steps delivered:")
            done.forEach { append("\n- ").append(it.nodeId).append(": ").append(it.output) }
        }.take(me.nathanfallet.teamcode.domain.cloud.CloudConversationRules.MAX_MESSAGE_CHARS)
    }

    private suspend fun human(work: RunWork, visit: NodeVisit, node: WorkflowNode, context: Context, now: Long) {
        val run = work.run
        val waitId = visit.waitId
        if (waitId == null) {
            val starter = store.starter(run) ?: return fail(work, "starter_unavailable", now)
            val session = run.sessionId ?: return fail(work, "no_session", now)
            val assignee = node.assignee?.takeUnless { it.ownerOfRun }?.memberId
            val body = node.instruction?.let { WorkflowTemplate.render(it, run.inputs, WorkflowTemplate.outputs(work.visits)) }
            try {
                val id = waits.ask(starter, session, assignee, node.title, body, node.fields, node.actions, "workflow-visit-${visit.id}")
                store.waiting(visit.id, id)
            } catch (error: TeamFailure) {
                if (!error.retryable) fail(work, error.code, now)
            }
            return
        }
        val state = context.waits[waitId] ?: return fail(work, "wait_missing", now)
        when (state.status) {
            WaitStatus.OPEN -> Unit
            WaitStatus.RESOLVED -> store.complete(visit.id, VisitStatus.DONE, state.action, state.text.orEmpty(), now)
            WaitStatus.CANCELLED -> fail(work, "wait_cancelled", now)
        }
    }

    private suspend fun sub(work: RunWork, visit: NodeVisit, node: WorkflowNode, context: Context, now: Long) {
        val run = work.run
        val childId = visit.childRunId
        if (childId == null) {
            if (run.depth + 1 > run.definition.limits.maxDepth) return fail(work, "max_depth", now)
            val workflow = store.workflow(run.teamId, node.workflowId!!) ?: return fail(work, "workflow_missing", now)
            val outputs = WorkflowTemplate.outputs(work.visits)
            val arguments = node.arguments.mapValues { WorkflowTemplate.render(it.value, run.inputs, outputs) }
            val inputs = try { WorkflowRunRules.inputs(workflow.definition, arguments) } catch (_: TeamFailure) { return fail(work, "invalid_arguments", now) }
            val child = newRun(workflow, run.starterId, inputs, run.profileId, if (workflow.definition.sessionMode == SessionMode.SAME) run.sessionId else null, run.depth + 1, run.id, visit.id, now)
            store.startChild(visit.id, child, visit(child.id, 1, workflow.definition.nodes.first { it.id == workflow.definition.start }, now))
            return
        }
        val child = context.children[childId] ?: return fail(work, "sub_workflow_missing", now)
        when (child.status) {
            RunStatus.SUCCEEDED -> store.complete(visit.id, VisitStatus.DONE, WorkflowRules.DELIVERED, child.output.orEmpty(), now)
            RunStatus.FAILED, RunStatus.CANCELLED -> fail(work, "sub_workflow_${child.status.wire}", now)
            else -> Unit
        }
    }

    private suspend fun cancel(work: RunWork, now: Long) {
        val visit = work.active
        if (visit != null) {
            val starter = store.starter(work.run)
            if (starter != null && visit.waitId != null) try { waits.cancel(starter, visit.waitId) } catch (error: CancellationException) { throw error } catch (_: Exception) {}
            visit.childRunId?.let { store.requestCancel(work.run.teamId, it) }
            store.complete(visit.id, VisitStatus.CANCELLED, null, null, now)
        }
        store.finish(work.run.id, RunStatus.CANCELLED, "cancelled", null, now)
    }

    private suspend fun fail(work: RunWork, code: String, now: Long) {
        work.active?.let { store.complete(it.id, VisitStatus.FAILED, null, null, now) }
        store.finish(work.run.id, RunStatus.FAILED, code, null, now)
    }

    companion object {
        const val BATCH = 50
        const val LEASE_MILLIS = 30_000L
        /** A run that has not finished in two weeks is over. */
        const val MAX_AGE_MILLIS = 14L * 24 * 3_600_000

        fun visit(runId: String, seq: Int, node: WorkflowNode, now: Long) =
            NodeVisit(UUID.randomUUID().toString(), runId, seq, node.id, node.kind, VisitStatus.ACTIVE, startedAt = now)

        fun newRun(workflow: Workflow, starterId: String, inputs: Map<String, String>, profileId: String?, sessionId: String?, depth: Int, parentRunId: String?, parentVisitId: String?, now: Long) = WorkflowRun(
            UUID.randomUUID().toString(), workflow.teamId, workflow.id, workflow.definition.name, workflow.version, workflow.definition, starterId, RunStatus.RUNNING,
            inputs, profileId, sessionId, depth, parentRunId, parentVisitId, false, null, null, now, now, null,
        )
    }
}
