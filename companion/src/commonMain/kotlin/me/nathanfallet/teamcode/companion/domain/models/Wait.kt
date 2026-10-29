package me.nathanfallet.teamcode.companion.domain.models

import kotlinx.serialization.json.JsonElement

/** Where a wait lives: its own row, answered here, or a session's permission request or question, answered in the session. */
enum class WaitSource { WAIT, SESSION }

/** The kinds of a wait (`waits.v1`). A kind this app does not know (a newer server) is shown as a plain request. */
enum class WaitKind(val wire: String) {
    PERMISSION("permission"), QUESTION("question"), HUMAN_REQUEST("human_request"), TURN_FAILED("turn_failed");
    companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: HUMAN_REQUEST }
}

/** A field type this app does not know (a newer server) is [UNKNOWN] and filled as text. */
enum class FieldType(val wire: String) {
    TEXT("text"), CHOICE("choice"), CONFIRM("confirm"), NUMBER("number"), DATE("date"), SECRET("secret"), FILE("file"), INFO("info"), UNKNOWN("");
    companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN }
}

data class WaitOption(val value: String, val label: String, val description: String? = null)

data class WaitField(
    val id: String,
    val type: FieldType,
    val label: String,
    val help: String? = null,
    val required: Boolean = false,
    val default: JsonElement? = null,
    val options: List<WaitOption> = emptyList(),
    val multiple: Boolean = false,
    val allowOther: Boolean = false,
    val maxLength: Int? = null,
)

data class WaitAction(val id: String, val label: String, val requires: List<String> = emptyList(), val style: String? = null)

/** Something to look at: an artifact or a session by [id], or a [url]. */
data class WaitRef(val kind: String, val id: String? = null, val url: String? = null, val title: String? = null)

data class Wait(
    val id: String,
    val source: WaitSource,
    val kind: WaitKind,
    val open: Boolean,
    val sessionId: String?,
    val sessionName: String?,
    /** For a session's own request: what `POST …/permissions` takes. */
    val requestId: String?,
    val assigneeId: String,
    val title: String,
    val body: String?,
    val refs: List<WaitRef>,
    val fields: List<WaitField>,
    val actions: List<WaitAction>,
    val createdAt: Long,
)

data class WaitPage(val waits: List<Wait>, val nextBefore: String?)

/** What the member typed for one field. */
sealed interface FieldInput {
    data class Text(val value: String) : FieldInput
    data class Chosen(val values: Set<String>) : FieldInput
    data class Flag(val value: Boolean) : FieldInput
}

/** Why an answer cannot be sent yet: the field to fix, in the member's words (its label). */
class WaitInputProblem(val fieldId: String, val label: String, val reason: Reason) : Exception("$label: $reason") {
    enum class Reason { MISSING, NOT_A_NUMBER, NOT_A_DATE, TOO_LONG, UNSUPPORTED }
}
