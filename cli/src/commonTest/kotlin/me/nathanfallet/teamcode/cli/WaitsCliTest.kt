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
        parseField("x:secret:Key:required:oneTime", emptyMap()).let { assertEquals("Key", it.label); assertTrue(it.required && it.oneTime); assertEquals("secret", it.type) }
        assertFalse(parseField("x:secret:Key", emptyMap()).oneTime)
        assertFailsWith<UsageError> { parseField("x:text:Key:oneTime", emptyMap()) }
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

    @Test fun `ask flags for a delay, an instant or a webhook`() {
        assertNull(timedSettings(null, null, false, null, null, null), "no flag: a wait for a person")
        val delay = assertNotNull(timedSettings(3600, null, false, null, null, "Check CI"))
        assertEquals("time", delay.resolver); assertEquals(3600L, delay.after); assertEquals("Check CI", delay.resumeMessage)
        assertEquals(JsonPrimitive("2026-11-02T09:00:00Z"), timedSettings(null, "2026-11-02T09:00:00Z", false, null, null, null)!!.at)
        assertEquals(JsonPrimitive(1800000000000L), timedSettings(null, "1800000000000", false, null, null, null)!!.at)
        val hook = assertNotNull(timedSettings(null, null, true, 7200, "turn_failed", null))
        assertEquals("event", hook.resolver); assertEquals("webhook", hook.source); assertEquals(7200L, hook.timeoutSeconds); assertEquals("turn_failed", hook.onTimeout)
    }

    @Test fun `ask flags that do not fit are refused before anything is sent`() {
        listOf<() -> Unit>(
            { timedSettings(10, null, false, null, null, null) }, { timedSettings(30L * 24 * 3600 + 1, null, false, null, null, null) },
            { timedSettings(60, "2026-11-02T09:00:00Z", false, null, null, null) }, { timedSettings(60, null, true, null, null, null) },
            { timedSettings(null, "tomorrow", false, null, null, null) }, { timedSettings(60, null, false, 60, null, null) },
            { timedSettings(null, null, true, 5, null, null) }, { timedSettings(null, null, true, 7L * 24 * 3600 + 1, null, null) },
            { timedSettings(null, null, false, 60, null, null) }, { timedSettings(null, null, false, null, "wake", null) },
            { timedSettings(60, null, false, null, null, "x".repeat(2001)) },
        ).forEachIndexed { index, block -> assertFailsWith<UsageError>("case $index") { block() } }
    }

    @Test fun `a wait that resolves by itself is listed with when it resumes`() {
        val timed = wait().copy(resolver = "time", kind = "timed", dueAt = 1_800_000_000_000)
        assertContains(timed.line(0), "resumes at 2027-01-15T08:00:00Z")
        val ci = wait().copy(resolver = "event", kind = "timed", timeoutAt = 1_800_000_000_000, eventSource = "webhook", onTimeout = "turn_failed", eventUrl = "https://t/x?token=wev_s")
        assertContains(ci.line(0), "waits for webhook, times out at 2027-01-15T08:00:00Z (turn_failed)")
        assertContains(ci.announcement(), "curl -X POST 'https://t/x?token=wev_s'")
        assertFalse("curl" in ci.copy(eventUrl = null).announcement())
        assertFalse("resumes" in wait().line(0), "a person's wait says nothing of the kind")
    }

    private val sentinel = "S3NTINEL-cli-pa55"

    @Test fun `a secret comes from set-secret only, never from set or set-json`() {
        val values = answerValues(wait(), listOf("notes" to "fine"), emptyList(), mapOf("pw" to sentinel))
        assertEquals(JsonPrimitive(sentinel), values["pw"]); assertEquals(JsonPrimitive("fine"), values["notes"])
        val viaSet = assertFailsWith<CliUserException> { answerValues(wait(), listOf("pw" to sentinel), emptyList()) }
        assertContains(viaSet.message.orEmpty(), "--set-secret pw"); assertFalse(sentinel in viaSet.message.orEmpty())
        val viaJson = assertFailsWith<CliUserException> { answerValues(wait(), emptyList(), listOf("pw" to "\"$sentinel\"")) }
        assertContains(viaJson.message.orEmpty(), "--set-secret pw"); assertFalse(sentinel in viaJson.message.orEmpty())
        assertFailsWith<CliUserException> { answerValues(wait(), emptyList(), emptyList(), mapOf("notes" to "x")) }
        assertFailsWith<CliUserException> { answerValues(wait(), emptyList(), emptyList(), mapOf("nope" to "x")) }
        assertFailsWith<CliUserException> { answerValues(wait(), emptyList(), emptyList(), mapOf("pw" to "x".repeat(Waits.MAX_SECRET_BYTES + 1))) }
        assertFalse(sentinel in AnswerWaitRequest("approve", values).toString(), "the request never prints its values")
    }

    @Test fun `secrets are read without echo and a single one may span lines`() {
        val prompts = mutableListOf<String>()
        val typed = ArrayDeque(listOf("one", "two"))
        val hidden = readSecrets(listOf("a", "b"), false, { prompts += it }, { typed.removeFirst() }, { error("not stdin") }, { error("not stdin") })
        assertEquals(mapOf("a" to "one", "b" to "two"), hidden); assertEquals(2, prompts.size); assertTrue(prompts.none { "one" in it || "two" in it })
        val piped = ArrayDeque(listOf("first", "second"))
        assertEquals(mapOf("a" to "first", "b" to "second"), readSecrets(listOf("a", "b"), true, { error("no prompt") }, { error("hidden") }, { piped.removeFirst() }, { error("all") }))
        assertEquals(mapOf("key" to "-----BEGIN-----\nabc\n-----END-----"), readSecrets(listOf("key"), true, { error("no prompt") }, { error("hidden") }, { error("line") }, { "-----BEGIN-----\nabc\n-----END-----\n" }))
        assertFailsWith<CliUserException> { readSecrets(listOf("a"), false, {}, { null }, { null }, { "" }) }
    }

    @Test fun `show tells how to give a secret and never a value`() {
        val text = wait().copy(fields = fields + WaitFieldView("token", "secret", "Token", oneTime = true)).details()
        assertContains(text, "--set-secret token"); assertContains(text, "TEAMCODE_WAIT_TOKEN"); assertContains(text, "one time")
    }
}
