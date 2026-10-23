package me.nathanfallet.teamcode.cli.cloud

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.*

/** What `teamcode cloud attach` does with a line typed while it follows a session. */
internal sealed interface AttachAction {
    /** Send [text] as a message. */
    data class Message(val text: String) : AttachAction
    /** Answer an open permission request or question; [summary] says what was chosen. */
    data class Answer(val request: AnswerCloudPermissionRequest, val summary: String) : AttachAction
    /** Send nothing and show [text] (an index the question does not have, the next question to answer). */
    data class Notice(val text: String) : AttachAction
}

/**
 * The permission requests and questions `attach` saw open in the transcript, and the answer being typed to the
 * newest one. While a question waits, a typed line answers it instead of being sent as a message: the agent's turn
 * waits on the question, so a message would only queue behind it. Questions are answered one at a time, each by an
 * option index (several, separated by commas or spaces, for a multi-select question) or by any other text, a free
 * answer ("Other"). `/allow` and `/deny [reason]` decide a tool use; `/deny` also declines a question, and
 * `/message TEXT` sends a message whatever waits. Not thread-safe: `attach` calls it from one thread.
 */
internal class AttachRequests {
    private class Open(val id: String, val question: Boolean, val toolName: String, val questions: List<JsonObject>?) {
        /** The answers typed so far to [questions], in order. */
        val answers = mutableListOf<CloudQuestionAnswer>()
        /** The input id of an answer the server accepted, until it is consumed or rejected. */
        var sent: String? = null
    }
    private val open = LinkedHashMap<String, Open>()

    /** The newest open request that no answer is on its way to. */
    private val target: Open? get() = open.values.lastOrNull { it.sent == null }

    /** Follows the transcript; returns the prompt to show when [event] opened a request. */
    fun observe(event: CloudEventView): String? {
        val data = event.data
        when (event.type) {
            CloudEventTypes.PERMISSION_REQUEST -> {
                val id = data.string("requestId") ?: return null
                val question = (data["question"] as? JsonPrimitive)?.booleanOrNull == true
                // A question whose input was left out of the event (inputTruncated) can only be declined.
                val questions = ((data["input"] as? JsonObject)?.get("questions") as? JsonArray)?.map { it as? JsonObject ?: JsonObject(emptyMap()) }
                open[id] = Open(id, question, data.string("toolName") ?: "a tool", questions?.takeIf { it.isNotEmpty() })
                return prompt()
            }
            CloudEventTypes.PERMISSION_EXPIRED -> data.string("requestId")?.let(open::remove)
            // A request is open until its turn ends.
            CloudEventTypes.TURN -> if (data.string("phase") != "started") open.clear()
            CloudEventTypes.INPUT -> {
                val request = data.string("requestId")?.let(open::get) ?: open.values.firstOrNull { it.sent != null && it.sent == data.string("inputId") } ?: return null
                when (data.string("status")) {
                    CloudInputStatuses.CONSUMED -> open.remove(request.id)
                    CloudInputStatuses.REJECTED, CloudInputStatuses.CANCELLED ->
                        // These two leave the request open; any other reason means it is gone.
                        if (data.string("reason") in setOf("invalid_answer", "agent_unavailable")) { reopen(request.id); return prompt() } else open.remove(request.id)
                }
            }
        }
        return null
    }

    /** The server accepted the answer to [requestId] as input [inputId]: lines are messages again until it is settled. */
    fun sent(requestId: String, inputId: String) { open[requestId]?.sent = inputId }

    /** The answer to [requestId] did not reach the server: it can be typed again. */
    fun reopen(requestId: String) { open[requestId]?.let { it.sent = null; it.answers.clear() } }

    /** The server says [requestId] can no longer be answered (answered by someone else, ended). */
    fun close(requestId: String) { open.remove(requestId) }

    /** What the member can type now, or null when nothing waits for them. */
    fun prompt(): String? {
        val request = target ?: return null
        val questions = request.questions
        // Every question answered: the answer is on its way.
        if (request.question && questions != null && request.answers.size >= questions.size) return null
        return when {
            !request.question -> "The agent waits for permission to use ${request.toolName.printable()}: /allow or /deny [reason]. Other lines are sent as messages."
            questions == null -> "The agent asks a question that is too large to show; it can only be declined: /deny [reason]."
            else -> {
                val index = request.answers.size
                val question = questions[index]
                val options = question.options()
                val choose = when {
                    options.isEmpty() -> "type your answer"
                    question.multiSelect() -> "type the indexes of your choices (for example 0,2) or your own answer"
                    else -> "type an option index or your own answer"
                }
                "Question ${index + 1} of ${questions.size}: ${(question.string("question") ?: "").quoted().printable()}" +
                    (if (options.isEmpty()) "" else " (" + options.mapIndexed { i, label -> "$i: ${label.quoted()}" }.joinToString(" / ").printable() + ")") +
                    "; $choose. /deny declines, /message TEXT sends a message instead."
            }
        }
    }

    /** What to do with [line] (already known not to be `/detach` or `/interrupt`). */
    fun handle(line: String): AttachAction {
        val trimmed = line.trim()
        val command = trimmed.substringBefore(' ')
        val rest = trimmed.substringAfter(' ', "").trim()
        val request = target
        when (command) {
            "/message" -> return if (rest.isEmpty()) AttachAction.Notice("Use /message TEXT.") else AttachAction.Message(rest)
            "/deny" -> {
                request ?: return AttachAction.Notice("Nothing waits for an answer.")
                return AttachAction.Answer(AnswerCloudPermissionRequest(request.id, "deny", rest.ifEmpty { null }), if (request.question) "Declined the question." else "Denied ${request.toolName.printable()}.")
            }
            "/allow" -> return when {
                request == null -> AttachAction.Notice("Nothing waits for an answer.")
                request.question -> AttachAction.Notice("A question is answered by typing the answer, not with /allow.")
                else -> AttachAction.Answer(AnswerCloudPermissionRequest(request.id, "allow"), "Allowed ${request.toolName.printable()}.")
            }
        }
        // Only a question with its questions shown takes typed answers; anything else is a message.
        val questions = request?.questions?.takeIf { request.question } ?: return AttachAction.Message(line)
        val question = questions[request.answers.size]
        val options = question.options()
        val indexes = trimmed.split(',', ' ', '\t').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: -1 }
        val answer = if (options.isNotEmpty() && indexes.isNotEmpty() && trimmed.all { it.isDigit() || it == ',' || it.isWhitespace() }) {
            if (indexes.any { it !in options.indices }) return AttachAction.Notice("This question has options 0 to ${options.lastIndex}.")
            if (indexes.size > 1 && !question.multiSelect()) return AttachAction.Notice("Choose one option for this question.")
            CloudQuestionAnswer(request.answers.size, indexes.distinct().sorted())
        } else CloudQuestionAnswer(request.answers.size, text = trimmed)
        request.answers += answer
        if (request.answers.size < questions.size) return AttachAction.Notice(prompt()!!)
        val summary = request.answers.joinToString("; ") { a ->
            val q = questions[a.question]
            (q.string("question") ?: "").quoted() + " = " + (a.text?.quoted() ?: a.options.joinToString(", ") { q.options()[it].quoted() })
        }
        return AttachAction.Answer(AnswerCloudPermissionRequest(request.id, "allow", answers = request.answers.toList()), "Answered: $summary".printable())
    }

    private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.options() = (this["options"] as? JsonArray).orEmpty().map { (((it as? JsonObject)?.get("label") as? JsonPrimitive)?.contentOrNull ?: "") }
    private fun JsonObject.multiSelect() = (this["multiSelect"] as? JsonPrimitive)?.booleanOrNull == true
}
