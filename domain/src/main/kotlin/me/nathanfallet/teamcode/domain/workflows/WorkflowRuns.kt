package me.nathanfallet.teamcode.domain.workflows

import me.nathanfallet.teamcode.domain.cloud.CloudSessionState
import me.nathanfallet.teamcode.domain.cloud.DesiredState
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.waits.WaitAction
import me.nathanfallet.teamcode.domain.waits.WaitField
import me.nathanfallet.teamcode.domain.waits.WaitStatus

enum class RunStatus(val wire: String) {
    RUNNING("running"), WAITING("waiting"), SUCCEEDED("succeeded"), FAILED("failed"), CANCELLED("cancelled");
    val terminal get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}
enum class VisitStatus(val wire: String) {
    ACTIVE("active"), DONE("done"), FAILED("failed"), CANCELLED("cancelled");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}

/** One pass through a node. [dispatchedAt] is when an agent node's instruction reached its session; [remindedAt] is when the engine sent its one reminder (null: none yet). */
data class NodeVisit(
    val id: String, val runId: String, val seq: Int, val nodeId: String, val kind: NodeKind, val status: VisitStatus,
    val outcome: String? = null, val output: String? = null, val sessionId: String? = null, val waitId: String? = null, val childRunId: String? = null,
    val dispatchedAt: Long? = null, val startedAt: Long, val finishedAt: Long? = null, val remindedAt: Long? = null,
)

/** A run on the [definition] snapshot it started with, so editing or deleting the workflow never changes a run in flight. */
data class WorkflowRun(
    val id: String, val teamId: String, val workflowId: String, val workflowName: String, val workflowVersion: Int, val definition: WorkflowDefinition,
    val starterId: String, val status: RunStatus, val inputs: Map<String, String>, val profileId: String?, val sessionId: String?, val depth: Int,
    val parentRunId: String?, val parentVisitId: String?, val cancelRequested: Boolean, val failure: String?, val output: String?,
    val createdAt: Long, val updatedAt: Long, val finishedAt: Long?,
    /** The server-held token the run acts with; set by the store, never shown. */
    val credentialId: String? = null,
)

/** A run with its visits in order: what one step of the engine reads. */
data class RunWork(val run: WorkflowRun, val visits: List<NodeVisit>) {
    val active get() = visits.lastOrNull { it.status == VisitStatus.ACTIVE }
}

/** [current] is the active visit of each unfinished run of the page (read in one query), so a list can name the step in progress. */
data class WorkflowRunPage(val runs: List<WorkflowRun>, val nextBefore: String?, val current: Map<String, NodeVisit> = emptyMap())
data class RunChange(val run: WorkflowRun, val created: Boolean)

/** What the engine needs of a child run. */
data class ChildState(val status: RunStatus, val output: String?)

/** What [WorkflowRunService.pendingBySession] answers: [needsAction] is true when the session's agent step still has to be delivered or asked about. */
data class PendingDelivery(val needsAction: Boolean, val runId: String? = null, val nodeTitle: String? = null, val deliverable: Deliverable? = null)

interface WorkflowRunStore {
    suspend fun now(): Long
    suspend fun revalidate(actor: Principal)
    /** The last saved version of a workflow, for a sub-workflow node. */
    suspend fun workflow(teamId: String, id: String): Workflow?
    /**
     * Stores [run] and its first visit in one transaction. The same [idempotencyKey] from the same member returns the first
     * run (created = false). Fails `409 run_limit` past [limit] unfinished runs of the starter.
     */
    suspend fun start(actor: Principal, run: WorkflowRun, first: NodeVisit, idempotencyKey: String?, limit: Int): RunChange
    suspend fun find(teamId: String, id: String): WorkflowRun?
    /** The cloud session an execution credential (`tcex_`) was issued for, or null for any other credential. */
    suspend fun executionSession(tokenId: String): String?
    /**
     * The unfinished run of [teamId] whose latest active visit is an agent visit bound to [sessionId], with that visit; null
     * when none. One indexed read on the visit's session.
     */
    suspend fun runOfSession(teamId: String, sessionId: String): Pair<WorkflowRun, NodeVisit>?
    /** Whether the session holds an open stored wait, of any kind: one `exists` on `waits (session_id, status)`. A session's own permission request is not a stored wait. */
    suspend fun sessionHasOpenWait(sessionId: String): Boolean
    /** The member who owns the cloud session, or null when it does not exist in the team. */
    suspend fun sessionOwner(teamId: String, sessionId: String): String?
    suspend fun visits(runId: String): List<NodeVisit>
    /** By keyset, newest first. [starterId] null: the whole team. */
    suspend fun list(teamId: String, starterId: String?, workflowId: String?, status: RunStatus?, before: String?, limit: Int): WorkflowRunPage
    suspend fun requestCancel(teamId: String, id: String): Boolean

    /** The principal a run acts as (its starter), or null when they can no longer act. */
    suspend fun starter(run: WorkflowRun): Principal?
    /** Unfinished runs nobody holds, with their visits; each is held for [leaseMillis]. */
    suspend fun claim(limit: Int, leaseMillis: Long): List<RunWork>
    /** Lets go of claimed runs so the next pass sees them at once (otherwise their lease would have to run out). */
    suspend fun release(ids: Collection<String>)
    suspend fun children(ids: Set<String>): Map<String, ChildState>

    /** Records that the agent node's instruction reached [sessionId]; [bindRun] also makes it the run's session. */
    suspend fun dispatched(visitId: String, sessionId: String, bindRun: Boolean, at: Long): Boolean
    /**
     * Records the one reminder of an active agent visit that has none: true for exactly one caller, false when the visit was
     * already reminded or is no longer active (a delivery that arrived first wins).
     */
    suspend fun reminded(visitId: String, at: Long): Boolean
    /** Takes the mark of [reminded] back when the reminder could not be queued, so a later pass sends it; false when [at] is not the mark. */
    suspend fun unreminded(visitId: String, at: Long): Boolean
    /** Records the wait of a human node and marks the run waiting. */
    suspend fun waiting(visitId: String, waitId: String): Boolean
    /** Starts the child run of a sub-workflow node and records it on the visit, atomically; idempotent per visit. */
    suspend fun startChild(visitId: String, child: WorkflowRun, first: NodeVisit): Boolean
    /** Ends an active visit with [status] (and [outcome], [output]); false when it was no longer active. Marks the run running again. */
    suspend fun complete(visitId: String, status: VisitStatus, outcome: String?, output: String?, at: Long): Boolean
    /** Opens the next visit of an unfinished run. */
    suspend fun next(runId: String, visit: NodeVisit): Boolean
    /** Ends an unfinished run. */
    suspend fun finish(runId: String, status: RunStatus, failure: String?, output: String?, at: Long): Boolean
}

/** The cloud sessions agent nodes work in, as the starter. */
interface WorkflowSessions {
    /** Creates a session whose first message is [prompt]; [key] makes a retried creation return the same session. */
    suspend fun create(starter: Principal, name: String, profileId: String, model: String?, prompt: String, key: String): String
    /** Queues [text] on the session and wakes it; idempotent per [key]. */
    suspend fun message(starter: Principal, sessionId: String, text: String, key: String)
    /** The sessions' states, in one read. */
    suspend fun progress(sessionIds: Set<String>): Map<String, SessionProgress>
}

/** [turnStartedAt] and [turnEnd] describe the latest turn: when it started and how it ended (null while it runs). */
data class SessionProgress(val state: CloudSessionState, val desired: DesiredState, val turnStartedAt: Long?, val turnEnd: String?)

/** The waits human nodes create, as the starter. */
interface WorkflowWaits {
    /** Asks [assigneeId] (the starter when null) about [sessionId]; [key] makes a retried creation return the same wait. Returns its id. */
    suspend fun ask(starter: Principal, sessionId: String, assigneeId: String?, title: String, body: String?, fields: List<WaitField>, actions: List<WaitAction>, key: String): String
    suspend fun states(ids: Set<String>): Map<String, WaitState>
    suspend fun cancel(starter: Principal, id: String)
}
/** [text] is the answer as plain lines, `label: value`. */
data class WaitState(val status: WaitStatus, val action: String?, val text: String?)
