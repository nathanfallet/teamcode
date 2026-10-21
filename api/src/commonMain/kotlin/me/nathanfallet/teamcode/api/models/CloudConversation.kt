package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Normalized transcript event types. Clients must tolerate unknown types and unknown `data` fields. */
object CloudEventTypes {
    const val USER_MESSAGE = "message.user"
    const val AGENT_MESSAGE = "message.agent"
    const val TOOL_CALL = "tool.call"
    const val TOOL_RESULT = "tool.result"
    const val TURN = "turn"
    const val STATUS = "status"
    const val ERROR = "error"
    /** Server-written: an input changed status (`data.inputId`, `data.status`). */
    const val INPUT = "input"
    val all = listOf(USER_MESSAGE, AGENT_MESSAGE, TOOL_CALL, TOOL_RESULT, TURN, STATUS, ERROR, INPUT)
}

/**
 * One transcript event. [seq] is assigned by the server, starts at 1 and increases by one per session; [epoch] is
 * the execution that produced it (0 for server-written events). [data] is bounded and secret-masked.
 */
@Serializable
data class CloudEventView(val seq: Long, val type: String, val at: Long, val epoch: Int, val data: JsonObject)

/**
 * A page of events in ascending order. [firstSeq] is the oldest retained event (older ones expired), [lastSeq] the
 * newest event of the session when the page was read. Continue forward with `after=nextAfter`, backward with
 * `before=nextBefore`; a null cursor means there is nothing more in that direction.
 */
@Serializable
data class CloudEventPageView(
    val events: List<CloudEventView>,
    val firstSeq: Long? = null,
    val lastSeq: Long = 0,
    val nextAfter: Long? = null,
    val nextBefore: Long? = null,
)

/**
 * `POST /cloud/sessions/{id}/messages`. [clientMessageId] is an alias of the `Idempotency-Key` header (send one or
 * the other). [wake] `false` leaves a paused session paused; the message waits for the next resume.
 * [expectedLastEventSeq] refuses the message (`409 stale_view`) when newer events exist.
 */
@Serializable
data class SendCloudMessageRequest(
    val text: String,
    val wake: Boolean = true,
    val clientMessageId: String? = null,
    val expectedLastEventSeq: Long? = null,
)

/** Input acknowledgement levels, in order. `cancelled` and `rejected` are final like `consumed`. */
object CloudInputStatuses {
    const val QUEUED = "queued"
    const val DELIVERED = "delivered"
    const val CONSUMED = "consumed"
    const val CANCELLED = "cancelled"
    const val REJECTED = "rejected"
    val all = listOf(QUEUED, DELIVERED, CONSUMED, CANCELLED, REJECTED)
}

/**
 * A message or interrupt and its acknowledgement. `woke` is true when accepting it resumed a paused session.
 * `reason` explains `cancelled`/`rejected` with a stable code.
 */
@Serializable
data class CloudInputView(
    val id: String,
    val kind: String,
    val status: String,
    val authorId: String,
    val text: String? = null,
    val wake: Boolean = true,
    val woke: Boolean = false,
    val clientMessageId: String? = null,
    val createdAt: Long,
    val deliveredAt: Long? = null,
    val deliveredEpoch: Int? = null,
    val consumedAt: Long? = null,
    val finishedAt: Long? = null,
    val reason: String? = null,
    /** The `message.user` event recording this message in the transcript, once consumed. */
    val eventSeq: Long? = null,
)
@Serializable
data class CloudInputPageView(val inputs: List<CloudInputView>, val nextBefore: String? = null)

/** An attachment lease: the CLI renews it while attached so idle detection and transfer know someone is watching. */
@Serializable
data class CloudAttachmentView(
    val id: String,
    val sessionId: String,
    val memberId: String,
    val leaseUntil: Long,
    /** What the attached member may do: `transcript`, `message`. */
    val permissions: List<String>,
    val createdAt: Long,
)

/** Cloud access permissions. */
object CloudPermissions {
    const val CREATE = "create"
    const val ATTACH = "attach"
    const val MESSAGE = "message"
    const val TRANSCRIPT = "transcript"
    val all = listOf(CREATE, ATTACH, MESSAGE, TRANSCRIPT)
}

/**
 * One rule: [subject] is `everyone`, `member:<id>` or `group:<id>`; [scope] `own` applies to the member's own
 * sessions (and to creation), `team` to every team session. Administrators always hold every permission.
 */
@Serializable
data class CloudAccessRuleView(val subject: String, val permissions: List<String>, val scope: String = "own")
/** The team's cloud access policy. `PUT` requires the current [version]; a team that never saved one has version 0. */
@Serializable
data class CloudAccessPolicyView(val version: Int = 0, val rules: List<CloudAccessRuleView> = emptyList(), val updatedAt: Long? = null, val updatedBy: String? = null)

/** Runner channel for the agent: configuration, batched events, input long-poll and acknowledgements. */
@Serializable
data class RunnerAgentRequest(val teamId: String, val sessionId: String, val epoch: Int)
@Serializable
data class RunnerAgentView(val kind: String, val version: String? = null, val model: String? = null, val effort: String? = null, val conversation: String)
/** [sequence] is the runner's own counter for this epoch, starting at 1; a retried batch is deduplicated on it. */
@Serializable
data class RunnerEventView(val sequence: Long, val type: String, val data: JsonObject)
@Serializable
data class RunnerEventsRequest(val teamId: String, val sessionId: String, val epoch: Int, val events: List<RunnerEventView>)
@Serializable
data class RunnerEventsAckView(val acceptedThrough: Long, val lastSeq: Long)
/** Waits up to [waitMillis] (at most 20 000) for queued inputs; returns them marked `delivered`. */
@Serializable
data class RunnerInputsRequest(val teamId: String, val sessionId: String, val epoch: Int, val waitMillis: Long = 20_000)
@Serializable
data class RunnerInputView(val id: String, val kind: String, val text: String? = null, val attempt: Int = 1)
@Serializable
data class RunnerInputsView(val inputs: List<RunnerInputView>)
/** `status`: `consumed` (handed to the agent) or `rejected` with a stable [reason]. */
@Serializable
data class RunnerInputAckView(val id: String, val status: String, val reason: String? = null)
@Serializable
data class RunnerInputAckRequest(val teamId: String, val sessionId: String, val epoch: Int, val acks: List<RunnerInputAckView>)

/**
 * One event of the caller-wide feed (`GET /api/v1/cloud/events/stream`). [seq] is the team's own sequence, gap-free and
 * the SSE `id`; [type] names the event (`cloud.turn`, `cloud.session.paused`, `run.stale`, ...); [subject] is `session`
 * or `run` and [subjectId] its identifier; [memberId] is the member the subject belonged to when it happened.
 * [data] is a small object of identifiers and labels, never transcript text.
 */
@Serializable
data class TeamEventView(val seq: Long, val type: String, val subject: String, val subjectId: String, val memberId: String, val at: Long, val data: JsonObject)
