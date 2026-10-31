package me.nathanfallet.teamcode.companion.domain.models

/** The state of a run. [UNKNOWN] is a status a newer server added: shown as text, never as finished. */
enum class RunStatus(val wire: String) {
    RUNNING("running"), WAITING("waiting"), SUCCEEDED("succeeded"), FAILED("failed"), CANCELLED("cancelled"), UNKNOWN("");
    /** Still moving: it can be cancelled. */
    val active: Boolean get() = this == RUNNING || this == WAITING
    companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN }
}

/** What a step is: an agent that works, a person who answers, another workflow. [UNKNOWN] is a kind a newer server added. */
enum class StepKind(val wire: String) {
    AGENT("agent"), HUMAN("human"), WORKFLOW("workflow"), UNKNOWN("");
    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN }
}

enum class VisitStatus(val wire: String) {
    ACTIVE("active"), DONE("done"), FAILED("failed"), CANCELLED("cancelled"), UNKNOWN("");
    companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN }
}

/** The runs the list shows: the member's own, or (administrators) the team's. */
enum class RunScope { MINE, TEAM }

/** The chips of the runs list; the server filters on one status, so "Done" is the succeeded ones. [ALL] shows cancelled runs too. */
enum class RunFilter(val status: String?) { ALL(null), RUNNING("running"), WAITING("waiting"), DONE("succeeded"), FAILED("failed") }

/**
 * One pass through a step of a run. [title] is the step's title in the definition the run started with, else its id.
 * [sessionId], [waitId] and [childRunId] point at the session an agent works in, the wait a person answers, and the run
 * of a sub-workflow.
 */
data class RunVisit(
    val seq: Int,
    val nodeId: String,
    val title: String,
    val kind: StepKind,
    val status: VisitStatus,
    val outcome: String?,
    val output: String?,
    val sessionId: String?,
    val waitId: String?,
    val childRunId: String?,
    val startedAt: Long,
    val finishedAt: Long?,
)

/**
 * A run of a workflow, on the version it started with. In a list [visits] is empty and [currentNodeId] and
 * [currentKind] name the step in progress; on a single read [visits] holds every pass and [currentTitle] the step's title.
 */
data class WorkflowRun(
    val id: String,
    val workflowId: String,
    val workflowName: String,
    val workflowVersion: Int,
    val status: RunStatus,
    /** The wire name when [status] is [RunStatus.UNKNOWN]. */
    val rawStatus: String,
    val starterId: String,
    val inputs: Map<String, String>,
    val sessionId: String?,
    val currentNodeId: String?,
    val currentKind: StepKind?,
    val parentRunId: String?,
    /** A short code when the run failed (`session_failed`, `max_visits:review`...). */
    val failure: String?,
    val output: String?,
    val visits: List<RunVisit>,
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long?,
) {
    /** The step in progress: the active visit of a single read. */
    val activeVisit: RunVisit? get() = visits.lastOrNull { it.status == VisitStatus.ACTIVE }
    val currentTitle: String? get() = activeVisit?.title ?: currentNodeId
    /** How long it ran (until it ended, else until [now]). */
    fun durationMillis(now: Long): Long = (finishedAt ?: maxOf(now, createdAt)) - createdAt
}

data class WorkflowRunPage(val runs: List<WorkflowRun>, val nextBefore: String?)

data class WorkflowInput(val id: String, val label: String, val required: Boolean, val default: String?, val maxLength: Int)

data class StepAction(val id: String, val label: String)
data class StepTransition(val on: String, val to: String?)

/** What a step delivers: its [type] (`artifact`, `url`, `text`, `json`) and, for a `url`, the [domain] it must be on. */
data class StepDeliverable(val type: String, val domain: String?)

/** One node of a definition, read-only. [rawKind] is the wire name when [kind] is [StepKind.UNKNOWN]. */
data class WorkflowStep(
    val id: String,
    val kind: StepKind,
    val rawKind: String,
    val title: String,
    val instruction: String?,
    val deliverable: StepDeliverable?,
    val profileId: String?,
    val assignee: String?,
    val calls: String?,
    val actions: List<StepAction>,
    val transitions: List<StepTransition>,
    val maxVisits: Int,
)

/** A saved workflow: read-only here (editing definitions is still done over the API). */
data class Workflow(
    val id: String,
    val name: String,
    val description: String?,
    val version: Int,
    val start: String,
    val sessionMode: String,
    val inputs: List<WorkflowInput>,
    val steps: List<WorkflowStep>,
    val maxVisits: Int,
    val updatedAt: Long,
) {
    fun step(id: String): WorkflowStep? = steps.firstOrNull { it.id == id }
    /** Whether the run needs a profile: an agent step names none (`400 profile_required` otherwise). */
    val needsProfile: Boolean get() = steps.any { it.kind == StepKind.AGENT && it.profileId.isNullOrBlank() }
}

data class WorkflowPage(val workflows: List<Workflow>, val nextAfter: String?)

/** What is entered to start a run. */
data class RunStart(val inputs: Map<String, String>, val profileId: String?)

/** What a run says about its wait: it blocks on [waitId], which is the member's when [mine]. */
data class RunWaitState(val waitId: String, val mine: Boolean)

/** A feed event about a run (`cloud.workflow.run.updated`): nothing but ids and the status; the run is read for the rest. */
data class RunUpdate(val runId: String, val workflowId: String?, val status: RunStatus, val ownerId: String, val parentRunId: String?)
