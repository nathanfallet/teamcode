package me.nathanfallet.teamcode.cli.cloud

import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.api.models.CloudQuestionAnswer
import me.nathanfallet.teamcode.cli.terminal.*

/**
 * Answers the questions of one `AskUserQuestion` request, one question at a time: the options (one for a single
 * choice, any for a multi-select question), "Other" for a free answer, and "Decline". Enter confirms a question and
 * shows the next one, left (or backspace with nothing typed) goes back to the previous one with its choices kept; the
 * last Enter submits every answer together. A multi-select answer with "Other" typed is sent as text: the chosen labels
 * and the typed answer joined with ", ", the format Claude Code receives for several labels.
 */
internal class QuestionPicker(val requestId: String, private val questions: List<JsonObject>) {
    sealed interface Outcome {
        data object None : Outcome
        data class Submit(val answers: List<CloudQuestionAnswer>) : Outcome
        data object Decline : Outcome
    }

    private class Draft(var cursor: Int = 0, val chosen: MutableSet<Int> = mutableSetOf(), var other: String = "", var otherChosen: Boolean = false)
    private val drafts = questions.map { Draft() }
    var index = 0
        private set
    private var hint: String? = null

    private val question get() = questions[index]
    private val options get() = question.options()
    private val draft get() = drafts[index]
    private val other get() = options.size
    private val decline get() = options.size + 1

    fun key(key: Key): Outcome {
        hint = null
        val draft = draft
        val multi = question.multiSelect()
        when (key) {
            Key.Up -> draft.cursor = (draft.cursor - 1 + decline + 1) % (decline + 1)
            Key.Down -> draft.cursor = (draft.cursor + 1) % (decline + 1)
            Key.Left -> back()
            Key.Backspace -> if (draft.cursor == other && draft.other.isNotEmpty()) {
                draft.other = draft.other.dropLastCharacter()
                if (draft.other.isBlank()) draft.otherChosen = false
            } else back()
            Key.ClearLine -> if (draft.cursor == other) { draft.other = ""; draft.otherChosen = false }
            is Key.Text -> when {
                key.text == " " && draft.cursor < other -> if (multi) { if (!draft.chosen.remove(draft.cursor)) draft.chosen += draft.cursor } else choose(draft.cursor)
                else -> {
                    // Typing anywhere writes the free answer.
                    draft.cursor = other
                    draft.other += key.text.printable()
                    if (multi) draft.otherChosen = draft.other.isNotBlank() else { draft.chosen.clear(); draft.otherChosen = true }
                }
            }
            Key.Enter -> return confirm()
            else -> Unit
        }
        return Outcome.None
    }

    private fun choose(option: Int) { draft.chosen.clear(); draft.chosen += option; draft.otherChosen = false }

    private fun back() { if (index > 0) index-- else hint = "This is the first question." }

    private fun confirm(): Outcome {
        val draft = draft
        if (draft.cursor == decline) return Outcome.Decline
        if (question.multiSelect()) {
            if (draft.cursor < other && draft.chosen.isEmpty() && !draft.otherChosen) draft.chosen += draft.cursor
            if (draft.cursor == other && draft.other.isNotBlank()) draft.otherChosen = true
            if (draft.chosen.isEmpty() && !(draft.otherChosen && draft.other.isNotBlank())) { hint = "Choose at least one option (space), or type your own answer."; return Outcome.None }
        } else when {
            draft.cursor < other -> choose(draft.cursor)
            draft.other.isBlank() -> { hint = "Type your answer, or choose an option."; return Outcome.None }
            else -> { draft.chosen.clear(); draft.otherChosen = true }
        }
        if (index < questions.lastIndex) { index++; return Outcome.None }
        return Outcome.Submit(answers())
    }

    private fun answers() = questions.indices.map { i ->
        val draft = drafts[i]
        val typed = draft.other.trim().takeIf { draft.otherChosen && it.isNotEmpty() }
        when {
            typed == null -> CloudQuestionAnswer(i, draft.chosen.sorted())
            draft.chosen.isEmpty() -> CloudQuestionAnswer(i, text = typed)
            else -> CloudQuestionAnswer(i, text = (draft.chosen.sorted().map { questions[i].options()[it] } + typed).joinToString(", "))
        }
    }

    /** The picker's lines, none wider than [width] columns. */
    fun lines(width: Int): List<String> {
        val multi = question.multiSelect()
        val draft = draft
        val header = question.string("header")?.takeIf { it.isNotBlank() }?.let { "${it.printable()}: " } ?: ""
        val descriptions = (question["options"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { (it as? JsonObject)?.string("description") }
        val result = mutableListOf("? Question ${index + 1}/${questions.size} · $header${(question.string("question") ?: "").printable()}")
        options.forEachIndexed { i, label ->
            val mark = if (multi) (if (i in draft.chosen) "[x]" else "[ ]") else (if (i in draft.chosen) "(*)" else "( )")
            result += item(i, "$mark ${label.printable()}" + (descriptions.getOrNull(i)?.takeIf { it.isNotBlank() && it != label }?.let { " - ${it.printable()}" } ?: ""))
        }
        val otherMark = if (multi) (if (draft.otherChosen && draft.other.isNotBlank()) "[x]" else "[ ]") else (if (draft.otherChosen) "(*)" else "( )")
        result += item(other, "$otherMark Other: " + draft.other.ifEmpty { if (draft.cursor == other) "" else "type your own answer" } + if (draft.cursor == other) "_" else "")
        result += item(decline, "    Decline the question")
        val confirm = if (index < questions.lastIndex) "enter next" else "enter send"
        result += "  up/down move · ${if (multi) "space toggle · " else ""}$confirm${if (index > 0) " · left back" else ""} · ctrl-c detach"
        hint?.let { result += "  $it" }
        // Cut before styling, so no escape sequence is cut; the line under the cursor is bold.
        return result.map { fit(it, width) }.map { if (it.startsWith("> ")) "\u001B[1m$it\u001B[0m" else it }
    }

    private fun item(i: Int, text: String) = (if (draft.cursor == i) "> " else "  ") + text
}

/**
 * `attach` on a terminal in raw mode: the newest open question shown as a [QuestionPicker], otherwise a message line
 * (with a hint while a tool use waits for `/allow` or `/deny`, and the [status] line, the files pending for the next
 * message), both in a [LiveArea] under the transcript. Not
 * thread-safe: `attach` calls it from the thread that follows the transcript.
 */
internal class KeyAttach(private val requests: AttachRequests, private val columns: () -> Int, private val status: () -> String? = { null }, write: (String) -> Unit) {
    sealed interface Step {
        data object None : Step
        data object Detach : Step
        data class Line(val text: String) : Step
        data class Send(val answer: AttachAction.Answer) : Step
    }

    private val area = LiveArea(write)
    private val editor = LineEditor()
    /** Pickers by request id, so choices survive an event, a refused answer or another request opening. */
    private val pickers = mutableMapOf<String, QuestionPicker>()

    private fun width() = columns().takeIf { it > 0 } ?: 80

    private fun picker(): QuestionPicker? {
        val waiting = requests.waiting() ?: return null
        val questions = waiting.questions?.takeIf { waiting.question } ?: return null
        return pickers.getOrPut(waiting.id) { QuestionPicker(waiting.id, questions) }
    }

    private fun lines(): List<String> = picker()?.lines(width()) ?: listOfNotNull(requests.prompt()?.let { fit(it, width()) }, status()?.let { fit(it, width()) }, editor.line(width()))

    /** Prints a transcript line or a notice above the live area. */
    fun print(text: String) = area.print(text, lines())

    /** Draws the live area again after the state changed. */
    fun refresh() = area.show(lines())

    fun feed(key: Key): Step {
        if (key == Key.Interrupt) return Step.Detach
        val picker = picker()
        if (key == Key.EndOfInput) return if (picker == null && editor.text.isEmpty()) Step.Detach else Step.None
        if (picker != null) return when (val outcome = picker.key(key)) {
            QuestionPicker.Outcome.None -> Step.None
            is QuestionPicker.Outcome.Submit -> requests.answer(picker.requestId, outcome.answers)?.let { Step.Send(it) } ?: Step.None
            QuestionPicker.Outcome.Decline -> requests.decline(picker.requestId)?.let { Step.Send(it) } ?: Step.None
        }
        return editor.key(key)?.let { Step.Line(it) } ?: Step.None
    }

    /** Clears the live area. */
    fun close() = area.show(emptyList())
}
