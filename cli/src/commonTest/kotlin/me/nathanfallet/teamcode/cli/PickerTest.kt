package me.nathanfallet.teamcode.cli

import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import me.nathanfallet.teamcode.cli.terminal.PickerOption
import me.nathanfallet.teamcode.cli.terminal.pickMany
import me.nathanfallet.teamcode.cli.terminal.pickOne
import kotlin.test.*

/** The shared CLI picker: arrow keys on a terminal, a numbered list otherwise. */
class PickerTest {
    private val options = listOf(PickerOption("Red", "Warm"), PickerOption("Blue"), PickerOption("Red"))

    private fun interactive(vararg keys: String) = TerminalRecorder(inputInteractive = true, outputInteractive = true).apply {
        inputEvents = keys.map { if (it == "ctrl-c") KeyboardEvent("c", ctrl = true) else KeyboardEvent(it) }.toMutableList()
    }

    @Test fun arrowsAndEnterPickOneAndRepeatedLabelsStayDistinct() {
        val recorder = interactive("ArrowDown", "ArrowDown", "Enter")
        assertEquals(2, pickOne("Color?", options, terminal = Terminal(terminalInterface = recorder)))
        assertTrue("Color?" in recorder.output(), recorder.output())
        assertEquals(1, pickOne("Color?", options, initial = 1, terminal = Terminal(terminalInterface = interactive("Enter"))))
        assertNull(pickOne("Color?", options, terminal = Terminal(terminalInterface = interactive("ctrl-c"))))
    }

    @Test fun spaceTogglesAndEnterConfirmsSeveral() {
        val terminal = Terminal(terminalInterface = interactive(" ", "ArrowDown", "ArrowDown", " ", "Enter"))
        assertEquals(setOf(0, 2), pickMany("Toppings?", options, terminal = terminal))
        assertEquals(setOf(1), pickMany("Toppings?", options, initial = setOf(1), terminal = Terminal(terminalInterface = interactive("Enter"))))
    }

    @Test fun withoutATerminalANumberedListTakesOneLine() {
        val recorder = TerminalRecorder(inputInteractive = false, outputInteractive = false).apply { inputLines = mutableListOf("7", "0,1", "1") }
        assertEquals(1, pickOne("Color?", options, terminal = Terminal(terminalInterface = recorder)))
        assertTrue("  0: Red - Warm" in recorder.output() && "from 0 to 2" in recorder.output() && "Choose one." in recorder.output(), recorder.output())
        val many = TerminalRecorder(inputInteractive = false, outputInteractive = false).apply { inputLines = mutableListOf("2, 0") }
        assertEquals(setOf(0, 2), pickMany("Toppings?", options, terminal = Terminal(terminalInterface = many)))
        assertNull(pickOne("Color?", options, terminal = Terminal(terminalInterface = TerminalRecorder(inputInteractive = false, outputInteractive = false))))
    }
}
