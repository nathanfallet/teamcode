package me.nathanfallet.teamcode.cli

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.CloudSessionView
import me.nathanfallet.teamcode.api.models.CloudTransferRecipientView
import me.nathanfallet.teamcode.cli.cloud.pickRecipient
import me.nathanfallet.teamcode.cli.cloud.pickTransferableSession
import kotlin.test.*

/** `teamcode cloud transfer` without SESSION or `--to`: the session and the recipient picked from lists. */
class TransferPickerTest {
    private fun keys(vararg keys: String) = Terminal(terminalInterface = TerminalRecorder(inputInteractive = true, outputInteractive = true).apply {
        inputEvents = keys.map { if (it == "ctrl-c") KeyboardEvent("c", ctrl = true) else KeyboardEvent(it) }.toMutableList()
    })

    private fun session(id: String, state: String, kind: String = "cloud", transferId: String? = null) = Serialization.json.decodeFromString<CloudSessionView>(
        """{"id":"$id","name":"Session $id","ownerId":"m1","createdBy":{"initiatorId":"m1","credentialId":"c","effectiveMemberId":"m1"},"state":"$state",
        "desiredState":"ready","generation":1,"currentEpoch":1,"profile":{"id":"p1","version":1},"image":{"reference":"img"},"agent":{"kind":"claude"},
        "activity":{"lastActivityAt":1,"idleTimeoutMinutes":30},"resume":{"workspace":"w","conversation":"c"},"workspaceDeletion":{"state":"none"},
        "createdAt":1,"updatedAt":1,"kind":"$kind"${transferId?.let { ",\"transferId\":\"$it\"" } ?: ""}}""")

    private val recipients = listOf(CloudTransferRecipientView("m1", "ada", true), CloudTransferRecipientView("m2", "grace", false), CloudTransferRecipientView("m3", "linus", false))

    @Test fun onlySessionsThatCanChangeOwnerAreOffered() {
        val sessions = listOf(session("s1", "preparing"), session("s2", "paused"), session("s3", "ready", kind = "remote"), session("s4", "active", transferId = "t1"), session("s5", "active"))
        // s2 and s5 only: the down arrow moves from the first to the second.
        assertEquals("s5", pickTransferableSession(sessions, keys("ArrowDown", "Enter")).id)
        assertFailsWith<UsageError> { pickTransferableSession(listOf(session("s1", "terminated")), keys("Enter")) }
        assertFailsWith<UsageError> { pickTransferableSession(sessions, keys("ctrl-c")) }
    }

    @Test fun theOwnerIsNotOfferedAndANumberedListAnswersWithoutATerminal() {
        assertEquals("m3", pickRecipient(recipients, "m1", keys("ArrowDown", "Enter")))
        val recorder = TerminalRecorder(inputInteractive = false, outputInteractive = false).apply { inputLines = mutableListOf("1") }
        assertEquals("m3", pickRecipient(recipients, "m2", Terminal(terminalInterface = recorder)))
        assertTrue("0: ada - administrator · m1" in recorder.output() && "grace" !in recorder.output(), recorder.output())
        // Input ended (a script without --to): a clear refusal, never a guess.
        assertFailsWith<UsageError> { pickRecipient(recipients, "m1", Terminal(terminalInterface = TerminalRecorder(inputInteractive = false, outputInteractive = false))) }
        assertFailsWith<UsageError> { pickRecipient(recipients.take(1), "m1", keys("Enter")) }
    }
}
