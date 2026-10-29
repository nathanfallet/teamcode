package me.nathanfallet.teamcode.cli

import com.github.ajalt.clikt.core.UsageError
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.waits.*
import me.nathanfallet.teamcode.client.TeamCodeException
import kotlin.test.*

class WaitsCliTest {
    private val fields = listOf(
        WaitFieldView("notes", "text", "Notes", required = true),
        WaitFieldView("count", "number", "Count"),
        WaitFieldView("ok", "confirm", "Sure?"),
        WaitFieldView("when", "date", "When"),
        WaitFieldView("size", "choice", "Size", options = listOf(WaitOptionView("s", "Small"), WaitOptionView("l", "Large"))),
        WaitFieldView("tags", "choice", "Tags", multiple = true, options = listOf(WaitOptionView("a", "A"), WaitOptionView("b", "B"))),
        WaitFieldView("pw", "secret", "Secret"),
    )
    private fun wait(actions: List<WaitActionView> = listOf(WaitActionView("approve", "Approve")), source: String = "wait") = WaitView(
        "w1", source, "human_request", status = "open", sessionId = "s1", sessionName = "Fix", assigneeId = "m1", title = "Review", fields = fields, actions = actions, createdAt = 0)
    private fun message(status: Int, code: String) = describeFailure(TeamCodeException(status, "TeamCode returned HTTP $status.", code), "https://team.example").message

    @Test fun `the group lists every subcommand`() {
        val group = Cli().registeredSubcommands().single { it.commandName == "waits" }
        assertEquals(setOf("list", "show", "answer", "cancel", "ask"), group.registeredSubcommands().map { it.commandName }.toSet())
    }

    @Test fun `values follow the declared type of each field`() {
        val values = answerValues(wait(), listOf("notes" to "fine", "count" to "3", "ok" to "yes", "when" to "2026-10-29", "size" to "l", "tags" to "a", "tags" to "b"), emptyList())
        assertEquals(JsonPrimitive("fine"), values["notes"]); assertEquals(JsonPrimitive(3L), values["count"]); assertEquals(JsonPrimitive(true), values["ok"])
        assertEquals(JsonPrimitive("2026-10-29"), values["when"]); assertEquals(JsonPrimitive("l"), values["size"])
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), values["tags"])
    }

    @Test fun `a decimal number stays a number`() = assertEquals(JsonPrimitive(2.5), answerValues(wait(), listOf("count" to "2.5"), emptyList())["count"])

    @Test fun `set-json is used as given`() {
        val values = answerValues(wait(), emptyList(), listOf("tags" to "[\"a\"]", "count" to "7"))
        assertEquals(JsonArray(listOf(JsonPrimitive("a"))), values["tags"]); assertEquals(JsonPrimitive(7), values["count"])
    }

    @Test fun `an unknown field is refused and names the known ones`() {
        val error = assertFailsWith<CliUserException> { answerValues(wait(), listOf("nope" to "x"), emptyList()) }
        assertContains(error.message.orEmpty(), "nope"); assertContains(error.message.orEmpty(), "notes")
    }

    @Test fun `unconvertible values are refused`() {
        listOf("count" to "many", "ok" to "maybe", "when" to "tomorrow", "size" to "xl", "pw" to "x").forEach { (id, text) ->
            assertFailsWith<CliUserException>(id) { answerValues(wait(), listOf(id to text), emptyList()) }
        }
        assertFailsWith<CliUserException> { answerValues(wait(), listOf("size" to "s", "size" to "l"), emptyList()) }
        assertFailsWith<CliUserException> { answerValues(wait(), emptyList(), listOf("count" to "{oops")) }
        assertFailsWith<CliUserException> { answerValues(wait(), listOf("count" to "1"), listOf("count" to "2")) }
    }

    @Test fun `the only action is used when none is given`() {
        assertEquals("approve", chooseAction(wait(), null))
        assertEquals("done", chooseAction(wait(emptyList()), null))
    }

    @Test fun `several actions need a choice and an unknown one is refused`() {
        val two = wait(listOf(WaitActionView("approve", "Approve"), WaitActionView("changes", "Changes")))
        assertContains(assertFailsWith<CliUserException> { chooseAction(two, null) }.message.orEmpty(), "approve, changes")
        assertEquals("changes", chooseAction(two, "changes"))
        assertFailsWith<CliUserException> { chooseAction(two, "reject") }
    }

    @Test fun `ask options parse and are checked`() {
        val field = parseField("size:choice:Pick one:required", mapOf("size" to listOf("s", "l")))
        assertEquals("Pick one", field.label); assertTrue(field.required); assertEquals(listOf("s", "l"), field.options.map { it.value })
        assertEquals("a:b", parseField("n:text:a:b", emptyMap()).label)
        assertFailsWith<UsageError> { parseField("size:choice:Pick", emptyMap()) }
        assertFailsWith<UsageError> { parseField("x:secret:Key", emptyMap()) }
        assertFailsWith<UsageError> { parseField("x:blob:Key", emptyMap()) }
        assertEquals(WaitActionView("ok", "All good: yes"), parseAction("ok:All good: yes"))
        assertFailsWith<UsageError> { parseAction("ok") }
        assertEquals("https://x.test/a:b", parseRef("url:https://x.test/a:b").url)
        assertEquals("a1", parseRef("artifact:a1").id)
        assertFailsWith<UsageError> { parseRef("file:x") }
    }

    @Test fun `the list marks a session request as answered in the session`() {
        val row = wait(source = "session").line(90_000)
        assertContains(row, "session (answer it in the session)"); assertContains(row, "Fix"); assertContains(row, "1m")
        assertEquals("2d", age(0, 2 * 86_400_000L))
    }

    @Test fun `show lists fields, actions and options`() {
        val text = wait(listOf(WaitActionView("changes", "Changes", requires = listOf("notes")))).details()
        assertContains(text, "notes\ttext\tNotes (required)"); assertContains(text, "options: s (Small), l (Large)"); assertContains(text, "changes\tChanges (requires notes)")
    }

    @Test fun `server refusals read as sentences`() {
        assertContains(message(409, "wait_closed"), "already answered or cancelled")
        assertContains(message(409, "answer_through_session"), "in the session")
        assertContains(message(400, "invalid_answer"), "waits show")
    }
}
