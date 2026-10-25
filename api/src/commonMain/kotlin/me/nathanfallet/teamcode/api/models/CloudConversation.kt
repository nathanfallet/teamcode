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
    /** A consumed message: `data.text`, `inputId`, `authorId`, and `files` (`[{id, name, size, contentType}]`) when it carried files. */
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
    /**
     * Runner-written ([CloudTasks.FEATURE]): the agent's live background tasks, the full set each time it changes
     * (`data.tasks`: `[{id, type, description, subagentType, toolUseId, startedAt}]`, at most [CloudTasks.MAX_TASKS] of
     * `data.count`; `data.scheduled`: how many scheduled wake-ups the agent holds; `data.stoppable`: whether the runner can stop a task). An empty set when the last one ended or the agent's process stopped. Claude Code sessions only.
     */
    const val TASKS = "tasks"
    val all = listOf(USER_MESSAGE, AGENT_MESSAGE, AGENT_MESSAGE_DELTA, TOOL_CALL, TOOL_RESULT, TURN, STATUS, ERROR, INPUT, PERMISSION_REQUEST, PERMISSION_EXPIRED, TASKS)
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
 * [expectedLastEventSeq] refuses the message (`409 stale_view`) when newer events exist. [fileIds] (`cloud.files.v1`, at
 * most 5) names files uploaded to this session by the caller and not sent yet; with files, [text] may be empty.
 */
@Serializable
data class SendCloudMessageRequest(
    val text: String,
    val wake: Boolean = true,
    val clientMessageId: String? = null,
    val expectedLastEventSeq: Long? = null,
    val fileIds: List<String> = emptyList(),
)

/**
 * A file uploaded to a session (`POST /cloud/sessions/{id}/files`, `cloud.files.v1`). [name] is the cleaned name the host
 * writes it under; [contentType] the type the server recognized from its bytes. Send its [id] in a message's `fileIds`
 * before [expiresAt]; it is removed once the session's host received it.
 */
@Serializable
data class CloudFileView(
    val id: String,
    val sessionId: String,
    val name: String,
    val size: Long,
    val contentType: String,
    val sha256: String,
    val createdAt: Long,
    val expiresAt: Long,
)

/**
 * `GET /cloud/files/policy`: whether the caller's team can send files now ([available]; [reason] `file_storage_not_configured`
 * when it has no tested bucket), and its limits.
 */
@Serializable
data class CloudFilePolicyView(
    val available: Boolean,
    val reason: String? = null,
    val maxFileBytes: Long,
    val maxMessageBytes: Long,
    val maxFilesPerMessage: Int,
    val types: List<String>,
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

/** `POST /cloud/sessions/{id}/tasks/stop` ([CloudTasks.FEATURE]): stops the background task [taskId] of the session's running agent. */
@Serializable
data class StopCloudTaskRequest(val taskId: String)

/**
 * `cloud.sessions.background-tasks.v1`: the runner reports the agent's background tasks (subagents, background commands,
 * monitors) as [CloudEventTypes.TASKS] events, and a member who may message a session stops one of them.
 */
object CloudTasks {
    const val FEATURE = "cloud.sessions.background-tasks.v1"
    /** The input kind of a stop; its text is the task id. */
    const val KIND = "stop_task"
    /** Tasks one event lists at most, and characters of a description; the rest is left out. */
    const val MAX_TASKS = 50
    const val MAX_DESCRIPTION_CHARS = 200
    /** The task ids the server takes: Claude Code's are short letters and digits. */
    val TASK_ID = Regex("[A-Za-z0-9_-]{1,64}")
    /** Rejected by a runner (or for a runner) that cannot stop a task: an older one, or Codex. */
    const val UNSUPPORTED = "task_stop_unsupported"
    /** Rejected by the runner: no live task has this id (it ended meanwhile). */
    const val UNKNOWN = "unknown_task"
}

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

/** `cloud.files.v1` limits and refusals, as the server applies them. */
object CloudFiles {
    /** The capability a server advertises when it takes files. */
    const val FEATURE = "cloud.files.v1"
    const val MAX_FILES_PER_MESSAGE = 5
    const val FILE_TOO_LARGE = "file_too_large"
    const val FILE_TYPE_NOT_ALLOWED = "file_type_not_allowed"
    const val UPLOAD_QUOTA = "upload_quota"
    const val STORAGE_NOT_CONFIGURED = "file_storage_not_configured"
    /** `503`: the team's bucket did not answer; sending the file again may work. */
    const val STORAGE_UNAVAILABLE = "file_storage_unavailable"
    /** `409` on a message: a named file expired, was sent already or belongs to someone else. */
    const val FILE_UNAVAILABLE = "file_unavailable"
    /** `413` on a message: its files together exceed the team's per-message limit. */
    const val FILES_TOO_LARGE = "files_too_large"
    /** The reason a message with files is rejected for a runner that does not download them. */
    const val UNSUPPORTED = "files_unsupported"
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
 * [backgroundTasks]: the server takes `tasks` events, so the runner may report the agent's background tasks.
 * [owner]: the member the execution runs for, read at each execution start (a transfer starts a new one); null from an
 * older server, and then the agent is not told who runs the session.
 */
@Serializable
data class RunnerAgentView(
    val kind: String, val version: String? = null, val model: String? = null, val effort: String? = null, val conversation: String,
    val permissionRelay: Boolean = false, val permissionDeadlineMinutes: Int = 0, val liveText: Boolean = false, val permissionMode: String? = null,
    /** The server takes [CloudEventTypes.TASKS] events ([CloudTasks.FEATURE]); an older one refuses the batch that holds one. */
    val backgroundTasks: Boolean = false,
    val owner: RunnerOwnerView? = null,
)
/**
 * Who the execution runs for, as the agent is told: the member's [username] and, when they linked GitHub, the linked
 * account's [githubLogin]. Never an email or a credential.
 */
@Serializable
data class RunnerOwnerView(val username: String, val githubLogin: String? = null)
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
data class RunnerInputsRequest(
    val teamId: String, val sessionId: String, val epoch: Int, val waitMillis: Long = 20_000, val desiredState: String? = null,
    /** The runner downloads the [RunnerInputView.files] of a message; without it, a message with files is rejected (`files_unsupported`). */
    val acceptsFiles: Boolean = false,
    /**
     * The runner stops a background task ([CloudTasks.KIND] inputs); without it, such an input is rejected
     * (`task_stop_unsupported`) instead of being delivered, so an older runner never reads it as a message.
     */
    val acceptsTaskStops: Boolean = false,
)
/** [files]: what a message carries; download each from `GET /cloud/runner/files/{id}` and check its [RunnerFileView.sha256]. */
@Serializable
data class RunnerInputView(val id: String, val kind: String, val text: String? = null, val attempt: Int = 1, val files: List<RunnerFileView> = emptyList())
/** A file of a message, as its host writes it: [name] is already cleaned, [contentType] recognized from the bytes. */
@Serializable
data class RunnerFileView(val id: String, val name: String, val size: Long, val contentType: String, val sha256: String)
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
