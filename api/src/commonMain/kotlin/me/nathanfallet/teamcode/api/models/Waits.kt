package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
     * node (its answer moves the run on and does not wake the session).
     */
    val kinds = listOf("permission", "question", "human_request", "turn_failed", "workflow")
    /** Who or what resolves a wait. `time` and `event` are part of the contract but refused (`422 not_available_yet`). */
    val resolvers = listOf("human", "time", "event")
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
)

/**
 * What creates a human wait. Without [assigneeId], the session's owner is asked. [sessionId] is required in this
 * version. Without [actions], one `done` action is added.
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
)

@Serializable
data class CreateWaitRequest(val wait: WaitInput)

/** [values] are checked against the fields of the wait; [action] must be one of its actions. */
@Serializable
data class AnswerWaitRequest(val action: String, val values: Map<String, JsonElement> = emptyMap())

@Serializable
data class WaitPageView(val waits: List<WaitView>, val nextBefore: String? = null)
