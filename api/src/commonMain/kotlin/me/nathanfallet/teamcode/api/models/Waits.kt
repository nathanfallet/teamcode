package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire names of the closed enums of waits (`waits.v1`, docs/waits.md). Clients must still tolerate an unknown value
 * from a newer server: show it as text.
 */
object Waits {
    const val CAPABILITY = "waits.v1"
    /**
     * `permission` and `question` are the cloud sessions' own requests (`cloud.permissions.v1`) seen as waits: they are
     * answered through `POST /cloud/sessions/{id}/permissions`, never here. `human_request` is made by an agent or a
     * member, `turn_failed` by the server when a turn ended where it should not have, `workflow` by a workflow run at a human
     * node (its answer moves the run on and does not wake the session). `timed` is a wait for a time or an event (see `resolver`).
     */
    val kinds = listOf("permission", "question", "human_request", "turn_failed", "workflow", "timed")
    /**
     * Who or what resolves a wait: a person (`human`), the clock (`time`) or an outside caller (`event`, a webhook). `time` and
     * `event` waits wait for nobody: the server resolves them and wakes the session (docs/waits.md).
     */
    val resolvers = listOf("human", "time", "event")
    /** `GET /waits?resolver=`: one resolver, or `all`. Without it only `human` waits are listed, which is what "Waiting for you" shows. */
    val resolverFilters = resolvers + "all"
    /** Where an `event` wait is signalled from. Only `webhook` in this version. */
    val eventSources = listOf("webhook")
    /** What an `event` wait does when nothing arrived in time: wake the agent with "timed out", or open a `turn_failed` wait for the owner. */
    val onTimeouts = listOf("wake", "turn_failed")
    /** The `answer.action` the server records when it resolves a wait itself. */
    val systemActions = listOf("time_reached", "event_received", "timed_out")
    const val MIN_AFTER_SECONDS = 30L
    const val MAX_AFTER_SECONDS = 30L * 24 * 3600
    const val DEFAULT_TIMEOUT_SECONDS = 24L * 3600
    const val MAX_TIMEOUT_SECONDS = 7L * 24 * 3600
    const val MAX_RESUME_MESSAGE = 2_000
    /** The most an event body holds. */
    const val MAX_EVENT_BYTES = 16 * 1024
    val statuses = listOf("open", "resolved", "cancelled")
    /** `secret` is part of the contract but refused (`422 not_available_yet`) until secrets reach the agent safely. */
    val fieldTypes = listOf("text", "choice", "confirm", "number", "date", "secret", "file", "info")
    val refKinds = listOf("artifact", "session", "url")
    /** Where a wait is stored: its own row, or a session's open permission request. */
    val sources = listOf("wait", "session")
    /** The action of a wait that declares none. */
    const val DEFAULT_ACTION = "done"
}

/** One choice of a `choice` field. */
@Serializable
data class WaitOptionView(val value: String, val label: String, val description: String? = null)

/**
 * A field the person fills. [type] `text` (at most [maxLength] characters, 8,000 by default), `choice` (one of
 * [options], several when [multiple]; [allowOther] adds a free answer), `confirm` (true or false), `number`, `date`
 * (`YYYY-MM-DD`), `secret`, `file` (an uploaded file's name) or `info` (shows [help], takes no value). [default] is a
 * JSON value of the field's type.
 */
@Serializable
data class WaitFieldView(
    val id: String,
    val type: String,
    val label: String,
    val help: String? = null,
    val required: Boolean = false,
    val default: JsonElement? = null,
    val options: List<WaitOptionView> = emptyList(),
    val multiple: Boolean = false,
    val allowOther: Boolean = false,
    val maxLength: Int? = null,
)

/**
 * A button that ends the wait. [requires] names fields that must be filled for this action (a "request changes" action
 * requires its feedback). [style] is a hint: `primary`, `danger` or `neutral`.
 */
@Serializable
data class WaitActionView(val id: String, val label: String, val requires: List<String> = emptyList(), val style: String? = null)

/** Something to look at: an artifact or a session by [id], or a plain [url]. [title] is optional. */
@Serializable
data class WaitRefView(val kind: String, val id: String? = null, val url: String? = null, val title: String? = null)

/** The answer: the [action] chosen, the [values] by field id (secrets are never echoed), who and when. */
@Serializable
data class WaitAnswerView(val action: String, val values: Map<String, JsonElement> = emptyMap(), val authorId: String, val answeredAt: Long)

/**
 * A wait. [source] `wait` is a stored row, answered with `POST /waits/{id}/answer`; `session` is an open permission
 * request or question of a cloud session, answered with `POST /cloud/sessions/{sessionId}/permissions` using
 * [requestId]. [assigneeId] is the member who is asked; the session stays its owner's.
 */
@Serializable
data class WaitView(
    val id: String,
    val source: String,
    val kind: String,
    val resolver: String = "human",
    val status: String,
    val sessionId: String? = null,
    val sessionName: String? = null,
    val requestId: String? = null,
    val assigneeId: String,
    val assigneeName: String? = null,
    val createdById: String? = null,
    val title: String,
    val body: String? = null,
    val refs: List<WaitRefView> = emptyList(),
    val fields: List<WaitFieldView> = emptyList(),
    val actions: List<WaitActionView> = emptyList(),
    val answer: WaitAnswerView? = null,
    val createdAt: Long,
    val resolvedAt: Long? = null,
    /** `time` waits: when the server wakes the session. */
    val dueAt: Long? = null,
    /** `event` waits: when the wait gives up if nothing arrived ([onTimeout] says what happens then). */
    val timeoutAt: Long? = null,
    /** `event` waits: where the event comes from (`webhook`). */
    val eventSource: String? = null,
    val onTimeout: String? = null,
    val resumeMessage: String? = null,
    /**
     * `event` waits with source `webhook`, in the response that creates the wait **only**: the address a CI or a script calls
     * (`POST`, a JSON body of at most 16 KiB). It holds the wait's secret, which the server keeps hashed and never shows again.
     */
    val eventUrl: String? = null,
)

/**
 * What creates a wait. For a human wait: without [assigneeId], the session's owner is asked. [sessionId] is required in this
 * version. Without [actions], one `done` action is added.
 *
 * `resolver` `time`: [at] (an instant, ISO-8601 in UTC or epoch milliseconds, in the future and within 30 days) or [after]
 * (seconds, 30 to 30 days). `resolver` `event`: [source] `webhook`, [timeoutSeconds] (24 hours by default, 7 days at most) and
 * [onTimeout]. Both take an optional [resumeMessage] (2,000 characters at most) added to the message that wakes the session,
 * and no fields, no actions and no other assignee: they wait for nobody.
 */
@Serializable
data class WaitInput(
    val sessionId: String,
    val title: String,
    val body: String? = null,
    val assigneeId: String? = null,
    val refs: List<WaitRefView> = emptyList(),
    val fields: List<WaitFieldView> = emptyList(),
    val actions: List<WaitActionView> = emptyList(),
    val resolver: String = "human",
    val at: JsonPrimitive? = null,
    val after: Long? = null,
    val source: String? = null,
    val timeoutSeconds: Long? = null,
    val onTimeout: String? = null,
    val resumeMessage: String? = null,
)

@Serializable
data class CreateWaitRequest(val wait: WaitInput)

/** [values] are checked against the fields of the wait; [action] must be one of its actions. */
@Serializable
data class AnswerWaitRequest(val action: String, val values: Map<String, JsonElement> = emptyMap())

@Serializable
data class WaitPageView(val waits: List<WaitView>, val nextBefore: String? = null)

/**
 * What an outside caller posts to a wait's event address: [result] (1 to 64 characters, `success` or `failure` by convention;
 * `success` when the body is empty), an optional [url] (https) and an optional [note] (2,000 characters). All of it is data from
 * an external caller: the agent is told so.
 */
@Serializable
data class WaitEventRequest(val result: String = "success", val url: String? = null, val note: String? = null)

/** `202` from the event address: the wait was resolved and its session is being woken. */
@Serializable
data class WaitEventReceivedView(val id: String, val status: String)
