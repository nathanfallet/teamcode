package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * What the server keeps of runner event data: the nesting depth from the event, the entries per object or array, the
 * key length, and the reasoning keys (or object types) it drops. Shared by the server's sanitizer and the runner, which
 * leaves a permission request's input out rather than letting the server cut it.
 */
object CloudTranscriptLimits {
    const val MAX_DEPTH = 16
    const val MAX_ENTRIES = 200
    const val MAX_KEY_CHARS = 64
    val DROPPED_KEYS = setOf("thinking", "signature", "reasoning", "redacted_thinking")
}

/** Normalized transcript event types. Clients must tolerate unknown types and unknown `data` fields. */
object CloudEventTypes {
    const val USER_MESSAGE = "message.user"
    /**
     * The agent's text (`data.text`). With `messageId` (the agent's API message id) and `index` (the content block, when
     * known), it replaces the [AGENT_MESSAGE_DELTA] text received for that block, or for every block of the message
     * when `index` is absent.
     */
    const val AGENT_MESSAGE = "message.agent"
    /**
     * Live text of an agent message while it is written (`data.messageId`, `index`, `text`, `parentToolUseId`): `text`
     * is what the block gained since its previous delta. Replaced by the [AGENT_MESSAGE] of the same block; a client
     * that does not show live text ignores it. Claude Code sessions only.
     */
    const val AGENT_MESSAGE_DELTA = "message.agent.delta"
    const val TOOL_CALL = "tool.call"
    const val TOOL_RESULT = "tool.result"
    const val TURN = "turn"
    const val STATUS = "status"
    const val ERROR = "error"
    /** Server-written: an input changed status (`data.inputId`, `data.status`). */
    const val INPUT = "input"
    /**
     * The agent waits for a person before using a tool (`data.requestId`, `toolName`, `input`, `question`); answered
     * with `POST /cloud/sessions/{id}/permissions`. It stays open until that answer is consumed or the turn ends.
     */
    const val PERMISSION_REQUEST = "permission.request"
    /**
     * Runner-written: no answer came within the team's deadline, so the request was denied (`data.requestId`,
     * `deadlineMinutes`, `message`, the text the agent read).
     */
    const val PERMISSION_EXPIRED = "permission.expired"
    val all = listOf(USER_MESSAGE, AGENT_MESSAGE, AGENT_MESSAGE_DELTA, TOOL_CALL, TOOL_RESULT, TURN, STATUS, ERROR, INPUT, PERMISSION_REQUEST, PERMISSION_EXPIRED)
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

/**
 * `POST /cloud/sessions/{id}/permissions`: the answer to the `permission.request` event [requestId]. [behavior] is
 * `allow` or `deny`; [message] tells the agent why it was denied. [answers] answers a question (`AskUserQuestion`),
 * one entry per answered question, by index; it needs `allow`.
 */
@Serializable
data class AnswerCloudPermissionRequest(
    val requestId: String,
    val behavior: String,
    val message: String? = null,
    val answers: List<CloudQuestionAnswer>? = null,
)

/**
 * The answer to the question at index [question] (0-based, in the request's `input.questions`): the chosen [options]
 * by index (0-based, in that question's `options`; several only for a `multiSelect` question), or free [text]. Send
 * one or the other.
 */
@Serializable
data class CloudQuestionAnswer(val question: Int, val options: List<Int> = emptyList(), val text: String? = null)

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
/**
 * [permissionRelay]: the server takes `permission.request` events and their answers, so the runner may relay them.
 * [permissionDeadlineMinutes]: how long a request waits for an answer before the runner denies it; 0 waits without a deadline.
 * [liveText]: the server takes `message.agent.delta` events, so the runner may stream the agent's text as it is written.
 * [permissionMode]: a remote session's mode on its host (`ask` or `bypass`); null for a cloud session, whose pod is its sandbox.
 */
@Serializable
data class RunnerAgentView(
    val kind: String, val version: String? = null, val model: String? = null, val effort: String? = null, val conversation: String,
    val permissionRelay: Boolean = false, val permissionDeadlineMinutes: Int = 0, val liveText: Boolean = false, val permissionMode: String? = null,
)
/** [sequence] is the runner's own counter for this epoch, starting at 1; a retried batch is deduplicated on it. */
@Serializable
data class RunnerEventView(val sequence: Long, val type: String, val data: JsonObject)
@Serializable
data class RunnerEventsRequest(val teamId: String, val sessionId: String, val epoch: Int, val events: List<RunnerEventView>)
@Serializable
data class RunnerEventsAckView(val acceptedThrough: Long, val lastSeq: Long)
/**
 * Waits up to [waitMillis] (at most 20 000) for queued inputs; returns them marked `delivered`. With [desiredState],
 * the one the runner last knew (`running` or `paused`), the wait also ends as soon as the session's differs.
 */
@Serializable
data class RunnerInputsRequest(val teamId: String, val sessionId: String, val epoch: Int, val waitMillis: Long = 20_000, val desiredState: String? = null)
@Serializable
data class RunnerInputView(val id: String, val kind: String, val text: String? = null, val attempt: Int = 1)
/** [desiredState]: the session's desired state when the wait ended, so a pause reaches the runner without a heartbeat. */
@Serializable
data class RunnerInputsView(val inputs: List<RunnerInputView>, val desiredState: String? = null)
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
/** The `data` of the feed's `event: gap`: events after the requested cursor were purged; the stream resumes at [oldestSeq]. */
@Serializable
data class TeamEventGapView(val oldestSeq: Long)
