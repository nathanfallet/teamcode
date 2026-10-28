package me.nathanfallet.teamcode.cli

import com.github.ajalt.clikt.core.UsageError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.automations.*
import kotlin.test.*

/** `automations create` and `edit`: the definition built from the options, and the variables written as `--var`. */
class AutomationDefinitionTest {
    private val profile = "4f1c2d3e-0000-4000-8000-000000000001"
    private fun cron(prompt: String = "Summarize {{run.date}}") = AutomationChanges(name = "Evening", cron = "0 21 * * *", timezone = "Europe/Paris", profileId = profile, prompt = prompt)

    @Test fun `a cron automation takes its timezone and the defaults of the contract`() {
        val input = applyChanges(null, cron())
        assertEquals(AutomationTriggerView("cron", "0 21 * * *", "Europe/Paris"), input.trigger)
        assertEquals(AutomationActionView("cloud", profile, maxRuntimeMinutes = 60), input.action)
        assertEquals("skip", input.overlap); assertEquals(24, input.maxRunsPerDay); assertTrue(input.variables.isEmpty())
    }

    @Test fun `a cron automation without a timezone is refused instead of guessing the machine's zone`() {
        val error = assertFailsWith<UsageError> { applyChanges(null, AutomationChanges(name = "n", cron = "0 21 * * *", profileId = profile, prompt = "p")) }
        assertContains(error.message.orEmpty(), "--timezone")
    }

    @Test fun `a trigger is required and cannot be both`() {
        assertContains(assertFailsWith<UsageError> { applyChanges(null, AutomationChanges(name = "n", profileId = profile, prompt = "p")) }.message.orEmpty(), "--webhook")
        assertFailsWith<UsageError> { applyChanges(null, AutomationChanges(name = "n", cron = "* * * * *", webhook = true, timezone = "UTC", profileId = profile, prompt = "p")) }
    }

    @Test fun `a webhook automation carries its variables and sample and refuses cron options`() {
        val variable = AutomationVariableView("ticket", "\$.ticket.number", "int")
        val sample = JsonObject(mapOf("ticket" to JsonPrimitive(1)))
        val input = applyChanges(null, AutomationChanges(name = "Hook", webhook = true, profileId = profile, prompt = "Fix {{ticket}}", variables = listOf(variable), samplePayload = sample))
        assertEquals(AutomationTriggerView("webhook"), input.trigger); assertEquals(listOf(variable), input.variables); assertEquals(sample, input.samplePayload)
        assertFailsWith<UsageError> { applyChanges(null, AutomationChanges(name = "Hook", webhook = true, timezone = "UTC", profileId = profile, prompt = "p")) }
    }

    @Test fun `variables on a cron automation are refused`() {
        assertFailsWith<UsageError> { applyChanges(null, cron().let { AutomationChanges(it.name, it.cron, timezone = it.timezone, profileId = profile, prompt = "p", variables = listOf(AutomationVariableView("a", "\$.a"))) }) }
    }

    @Test fun `a cloud action needs a profile and a remote one is sent for the server to refuse`() {
        assertContains(assertFailsWith<UsageError> { applyChanges(null, AutomationChanges(name = "n", webhook = true, prompt = "p")) }.message.orEmpty(), "--profile")
        val remote = applyChanges(null, AutomationChanges(name = "n", webhook = true, action = "remote", prompt = "p"))
        assertEquals(AutomationActionView("remote", null), remote.action)
    }

    @Test fun `name and prompt are required for a new automation and a blank prompt is refused`() {
        assertContains(assertFailsWith<UsageError> { applyChanges(null, AutomationChanges(webhook = true, profileId = profile, prompt = "p")) }.message.orEmpty(), "--name")
        assertContains(assertFailsWith<UsageError> { applyChanges(null, AutomationChanges(name = "n", webhook = true, profileId = profile)) }.message.orEmpty(), "--prompt")
        assertContains(assertFailsWith<CliUserException> { applyChanges(null, cron(prompt = "  ")) }.message.orEmpty(), "empty")
    }

    @Test fun `an edit changes only what is given`() {
        val base = applyChanges(null, cron().let { AutomationChanges(it.name, it.cron, timezone = it.timezone, profileId = profile, prompt = it.prompt, model = "sonnet", overlap = "queue") })
        val edited = applyChanges(base, AutomationChanges(timezone = "UTC", maxRunsPerDay = 3))
        assertEquals(AutomationTriggerView("cron", "0 21 * * *", "UTC"), edited.trigger)
        assertEquals(base.copy(trigger = edited.trigger, maxRunsPerDay = 3), edited)
        assertEquals("sonnet", edited.action.model); assertEquals("queue", edited.overlap)
    }

    @Test fun `an edit can switch to a webhook and clear the variables`() {
        val base = applyChanges(null, AutomationChanges(name = "n", webhook = true, profileId = profile, prompt = "{{a}}", variables = listOf(AutomationVariableView("a", "\$.a"))))
        assertTrue(applyChanges(base, AutomationChanges(variables = emptyList())).variables.isEmpty())
        val cronBase = applyChanges(null, cron())
        val toHook = applyChanges(cronBase, AutomationChanges(webhook = true))
        assertEquals("webhook", toHook.trigger.kind); assertNull(toHook.trigger.timezone)
    }

    @Test fun `a variable reads its path and options`() {
        assertEquals(AutomationVariableView("ticket", "\$.ticket.number"), parseVariable("ticket=\$.ticket.number"))
        assertEquals(AutomationVariableView("n", "\$.items[0].id", "int", maxLength = 20, required = false, sensitive = true), parseVariable("n=\$.items[0].id;type=int;max=20;optional;sensitive"))
        assertEquals(AutomationVariableView("p", "\$.priority", "enum", listOf("low", "high")), parseVariable("p=\$.priority;values=low,high"))
    }

    @Test fun `a semicolon inside a quoted path segment is part of the path`() {
        assertEquals("\$['a;b'].c", parseVariable("v=\$['a;b'].c;optional").path)
    }

    @Test fun `a malformed variable says what to write`() {
        listOf("novalue", "=\$.a", "a=ticket.number", "a=\$.a;type=date", "a=\$.a;max=0", "a=\$.a;max=5000", "a=\$.a;color=red", "a=\$.a;type=enum", "a=\$.a;type=int;values=x,y", "a=\$.a;values=")
            .forEach { assertFailsWith<UsageError>(it) { parseVariable(it) } }
    }

    @Test fun `a variables file is a JSON array of variables`() {
        val variables = parseVariablesFile("""[{"name":"a","path":"${'$'}.a","type":"boolean","required":false}]""")
        assertEquals(listOf(AutomationVariableView("a", "\$.a", "boolean", required = false)), variables)
        assertFailsWith<CliUserException> { parseVariablesFile("""{"name":"a"}""") }
    }
}
