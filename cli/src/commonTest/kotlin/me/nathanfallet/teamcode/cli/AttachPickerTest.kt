package me.nathanfallet.teamcode.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.cloud.*
import com.github.ajalt.mordant.input.KeyboardEvent
import me.nathanfallet.teamcode.cli.terminal.*
import kotlin.test.*

/** `teamcode cloud attach` on a terminal: keys, the one-question-at-a-time picker and the live area. */
class AttachPickerTest {
    private fun questions(json: String) = (Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonArray).map { it as JsonObject }
    private val color = """{"question":"Pick a color","header":"Color","options":[{"label":"Red","description":"Warm"},{"label":"Blue"}],"multiSelect":false}"""
    private val toppings = """{"question":"Pick toppings","options":[{"label":"Cheese"},{"label":"Ham"},{"label":"Olives"}],"multiSelect":true}"""
    private val continueQuestion = """{"question":"Continue?","options":[{"label":"Yes"},{"label":"No"}],"multiSelect":false}"""

    private fun QuestionPicker.type(vararg keys: Key) = keys.map { key(it) }.last()
    private fun text(value: String) = value.map { Key.Text(it.toString()) }.toTypedArray()

    @Test fun mordantKeyEventsBecomePickerKeys() {
        assertEquals(listOf(Key.Up, Key.Down, Key.Left, Key.Right, Key.Enter, Key.Backspace, Key.Text("é"), Key.Text("😀"), Key.Text(" ")),
            listOf("ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Enter", "Backspace", "é", "😀", " ").map { KeyboardEvent(it).toKey() })
        assertEquals(listOf(Key.Interrupt, Key.EndOfInput, Key.ClearLine), listOf("c", "d", "u").map { KeyboardEvent(it, ctrl = true).toKey() })
        assertEquals(listOf(null, null, null), listOf(KeyboardEvent("F1"), KeyboardEvent("Unidentified"), KeyboardEvent("x", alt = true)).map { it.toKey() })
    }

    @Test fun oneQuestionAtATimeWithBackKeepingChoicesThenOneSubmit() {
        val picker = QuestionPicker("r1", questions("[$color,$toppings,$continueQuestion]"))
        assertTrue(picker.lines(80).first().startsWith("? Question 1/3 · Color: Pick a color"), picker.lines(80).toString())
        // Blue, then Cheese and Olives toggled with space.
        assertEquals(QuestionPicker.Outcome.None, picker.type(Key.Down, Key.Enter))
        assertEquals(1, picker.index)
        picker.type(Key.Text(" "), Key.Down, Key.Down, Key.Text(" "), Key.Enter)
        assertEquals(2, picker.index)
        // Back to the toppings: Olives is still chosen, Ham added.
        picker.key(Key.Left)
        assertEquals(1, picker.index)
        assertTrue(picker.lines(80).any { "[x] Olives" in it } && picker.lines(80).any { "[x] Cheese" in it }, picker.lines(80).toString())
        picker.type(Key.Up, Key.Text(" "), Key.Enter)
        assertTrue(picker.lines(80).any { "enter send" in it })
        val submit = assertIs<QuestionPicker.Outcome.Submit>(picker.type(Key.Enter))
        assertEquals(listOf(CloudQuestionAnswer(0, listOf(1)), CloudQuestionAnswer(1, listOf(0, 1, 2)), CloudQuestionAnswer(2, listOf(0))), submit.answers)
    }

    @Test fun otherTakesAFreeAnswerAndJoinsTheChosenLabelsOfAMultiSelectQuestion() {
        val picker = QuestionPicker("r1", questions("[$color,$toppings]"))
        // Typing anywhere writes the free answer; backspace edits it.
        picker.type(*text("Greem"), Key.Backspace, Key.Text("n"), Key.Enter)
        picker.type(Key.Text(" "), *text("anchovies"))
        val submit = assertIs<QuestionPicker.Outcome.Submit>(picker.key(Key.Enter))
        assertEquals(listOf(CloudQuestionAnswer(0, text = "Green"), CloudQuestionAnswer(1, text = "Cheese, anchovies")), submit.answers)
    }

    @Test fun aQuestionNeedsAnAnswerAndCanBeDeclined() {
        val picker = QuestionPicker("r1", questions("[$color]"))
        // Other without text is no answer.
        picker.type(Key.Down, Key.Down, Key.Enter)
        assertTrue(picker.lines(80).last().contains("Type your answer"), picker.lines(80).toString())
        assertEquals(QuestionPicker.Outcome.Decline, picker.type(Key.Down, Key.Enter))
        // A multi-select question with nothing chosen takes the option under the cursor.
        val multi = QuestionPicker("r2", questions("[$toppings]"))
        assertEquals(listOf(CloudQuestionAnswer(0, listOf(0))), assertIs<QuestionPicker.Outcome.Submit>(multi.key(Key.Enter)).answers)
    }

    @Test fun agentTextCannotDrawEscapeSequencesOrWiderLines() {
        val picker = QuestionPicker("r1", questions("""[{"question":"Pick\u001B[2J a very long question that goes on and on and on","options":[{"label":"A\u001B[31m"}]}]"""))
        val lines = picker.lines(30)
        assertTrue(lines.all { it.replace("\u001B[1m", "").replace("\u001B[0m", "").let { plain -> '\u001B' !in plain && plain.length <= 29 } }, lines.toString())
    }

    @Test fun theReportedQuestionOpensAsAPickerAndEnterSendsTheAnswer() {
        val requests = AttachRequests()
        val output = StringBuilder()
        val keys = KeyAttach(requests, { 100 }) { output.append(it) }
        requests.observe(CloudEventView(1, CloudEventTypes.PERMISSION_REQUEST, 0, 1, Json.parseToJsonElement(
            """{"requestId":"52ec8144","toolName":"AskUserQuestion","question":true,"input":{"questions":[{"question":"Is it you?","options":[{"label":"Yes"},{"label":"No"}],"multiSelect":false}]}}""") as JsonObject))
        keys.print("17:00:04 #26 [question 52ec8144] AskUserQuestion")
        assertTrue("? Question 1/1 · Is it you?" in output, output.toString())
        // Down then Enter: "No", sent as one answer.
        assertEquals(KeyAttach.Step.None, keys.feed(Key.Down))
        val send = assertIs<KeyAttach.Step.Send>(keys.feed(Key.Enter))
        assertEquals(AnswerCloudPermissionRequest("52ec8144", "allow", answers = listOf(CloudQuestionAnswer(0, listOf(1)))), send.answer.request)
        // Once the answer is on its way, keys type a message again.
        requests.sent("52ec8144", "i1")
        "hi".forEach { keys.feed(Key.Text(it.toString())) }
        assertEquals(KeyAttach.Step.Line("hi"), keys.feed(Key.Enter))
        assertEquals(KeyAttach.Step.Detach, keys.feed(Key.Interrupt))
    }

    @Test fun aToolUseShowsItsHintAndTakesCommandsOnTheMessageLine() {
        val requests = AttachRequests()
        val output = StringBuilder()
        val keys = KeyAttach(requests, { 100 }) { output.append(it) }
        requests.observe(CloudEventView(1, CloudEventTypes.PERMISSION_REQUEST, 0, 1, Json.parseToJsonElement("""{"requestId":"p1","toolName":"Bash","question":false,"input":{"command":"ls"}}""") as JsonObject))
        keys.refresh()
        assertTrue("/allow or /deny" in output, output.toString())
        "/allow".forEach { keys.feed(Key.Text(it.toString())) }
        assertEquals(KeyAttach.Step.Line("/allow"), keys.feed(Key.Enter))
    }

    @Test fun theLiveAreaIsClearedBeforeEachLineAboveIt() {
        val output = StringBuilder()
        val screen = LiveArea { output.append(it) }
        screen.show(listOf("a", "b", "c"))
        screen.print("event", listOf("> "))
        assertEquals("a\nb\nc" + "\r\u001B[2A\u001B[J" + "event\n" + "> ", output.toString())
    }
}
