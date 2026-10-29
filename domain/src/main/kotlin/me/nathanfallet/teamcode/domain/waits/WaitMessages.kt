package me.nathanfallet.teamcode.domain.waits

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.domain.cloud.CloudConversationRules

/**
 * The session's side of a wait: what the server tells the agent when its request is answered, so the answer wakes the
 * session (docs/waits.md). The agent's instructions describe this exact shape ([PREFIX] first, then `wait:`, `action:`,
 * `answered by:` and one `<label>: <value>` line per filled field), so changing it is a change to those instructions.
 */
object WaitMessages {
    const val PREFIX = "[TeamCode wait answered]"
    private const val TRUNCATED = "\n[truncated]"

    /** Scoped to the session and the author by the conversation store: the same answer sent twice queues one message. */
    fun clientKey(waitId: String) = "wait-answer-$waitId"

    fun answered(wait: Wait, answeredBy: String): String {
        val answer = requireNotNull(wait.answer) { "A wait without an answer has no message." }
        val action = wait.actions.firstOrNull { it.id == answer.action }
        val text = buildString {
            append(PREFIX).append(' ').append(line(wait.title)).append('\n')
            append("wait: ").append(wait.id).append('\n')
            append("action: ").append(line(action?.label ?: answer.action)).append(" (").append(answer.action).append(")\n")
            append("answered by: ").append(line(answeredBy))
            for (field in wait.fields) {
                val value = answer.values[field.id]?.takeUnless { it is JsonNull } ?: continue
                append('\n').append(line(field.label)).append(": ")
                append(render(field, value).trim().replace("\n", "\n  "))
            }
        }
        return if (text.length <= CloudConversationRules.MAX_MESSAGE_CHARS) text else text.take(CloudConversationRules.MAX_MESSAGE_CHARS - TRUNCATED.length) + TRUNCATED
    }

    /** A choice reads with its label beside the value; control characters (but newlines and tabs) are dropped, as a message may not carry them. */
    private fun render(field: WaitField, value: JsonElement): String {
        fun one(element: JsonElement): String {
            val raw = (element as? JsonPrimitive)?.content ?: element.toString()
            val label = if (field.type == FieldType.CHOICE) field.options.firstOrNull { it.value == raw }?.label?.takeIf { it != raw } else null
            return clean(raw) + (label?.let { " (${clean(it)})" } ?: "")
        }
        return if (value is JsonArray) value.joinToString(", ") { one(it) } else one(value)
    }

    private fun clean(text: String) = text.filterNot { it.isISOControl() && it != '\n' && it != '\t' }.replace("\r", "")
    private fun line(text: String) = clean(text).replace('\n', ' ').replace('\t', ' ')
}
