package me.nathanfallet.teamcode.companion.domain.models

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

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
    /** For a wait that ended: `true` when it was withdrawn rather than answered. */
    val cancelled: Boolean = false,
    /** When it ended, server time in milliseconds. */
    val resolvedAt: Long? = null,
    /** What was answered, for a wait that was. */
    val answer: WaitAnswer? = null,
) {
    val status: WaitStatus get() = if (open) WaitStatus.OPEN else if (cancelled) WaitStatus.CANCELLED else WaitStatus.RESOLVED

    /** The action chosen, in the words of the wait's own action. */
    val answerActionLabel: String? get() = answer?.let { given -> actions.firstOrNull { it.id == given.action }?.label ?: given.action }

    /** The values given, one line per field in the wait's order (a value of a field the wait no longer shows keeps its id). */
    fun answerLines(): List<AnswerLine> {
        val values = answer?.values ?: return emptyList()
        val known = fields.filter { it.id in values && it.type != FieldType.INFO }.map { field -> AnswerLine(field.label, values.getValue(field.id).shown(field)) }
        val unknown = values.filterKeys { id -> fields.none { it.id == id } }.map { (id, value) -> AnswerLine(id, value.shown(null)) }
        return known + unknown
    }
}

/** One line of a given answer: the field's label and its value; [flag] is set for a yes or no, which the screen words. */
data class AnswerLine(val label: String, val value: AnswerValue)

sealed interface AnswerValue {
    data class Words(val text: String) : AnswerValue
    data class Flag(val value: Boolean) : AnswerValue
}

private fun JsonElement.shown(field: WaitField?): AnswerValue {
    fun label(value: String) = field?.options?.firstOrNull { it.value == value }?.label ?: value
    return when (this) {
        is JsonPrimitive -> if (!isString && (content == "true" || content == "false")) AnswerValue.Flag(content == "true") else AnswerValue.Words(label(content))
        is JsonArray -> AnswerValue.Words(joinToString(", ") { (it as? JsonPrimitive)?.content?.let(::label) ?: it.toString() })
        else -> AnswerValue.Words(toString())
    }
}

/** The answer given to a wait: the action chosen, the values by field id, and when. */
data class WaitAnswer(val action: String, val values: Map<String, JsonElement> = emptyMap(), val authorId: String? = null, val answeredAt: Long? = null)

/** Which waits a list shows: those still open, those answered, those withdrawn. */
enum class WaitStatus(val wire: String) { OPEN("open"), RESOLVED("resolved"), CANCELLED("cancelled") }

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
