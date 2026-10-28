package me.nathanfallet.teamcode.domain.automations

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class AutomationTemplateTest {
    private fun payload(text: String) = Json.parseToJsonElement(text).jsonObject
    private val ticket = AutomationVariable("ticket.number", "$.ticket.number", VariableType.INT)

    @Test fun declaredVariablesAreExtractedByPathAndRendered() {
        val variables = listOf(ticket, AutomationVariable("label", "$.labels[1]"), AutomationVariable("title", "$['the title']", maxLength = 10))
        val extraction = AutomationTemplate.extract(variables, payload("""{"ticket":{"number":735},"labels":["a","bug"],"the title":"Crash"}"""))
        assertTrue(extraction.problems.isEmpty())
        assertEquals(mapOf("ticket.number" to "735", "label" to "bug", "title" to "Crash"), extraction.values)
        assertEquals("Fix 735 (bug): Crash on 2026-10-28", AutomationTemplate.render("Fix {{ticket.number}} ({{ label }}): {{title}} on {{run.date}}", extraction.values + ("run.date" to "2026-10-28")))
    }

    @Test fun eachRuleNamesItsPathNeverTheValue() {
        val variables = listOf(ticket, AutomationVariable("kind", "$.kind", VariableType.ENUM, values = listOf("bug", "feature")),
            AutomationVariable("note", "$.note", maxLength = 3), AutomationVariable("flag", "$.flag", VariableType.BOOLEAN), AutomationVariable("missing", "$.nope"),
            AutomationVariable("object", "$.ticket"))
        val problems = AutomationTemplate.extract(variables, payload("""{"ticket":{"number":"abc"},"kind":"secret-kind","note":"toolong","flag":"yes"}""")).problems
        assertEquals(listOf(VariableProblem("$.ticket.number", "type:int"), VariableProblem("$.kind", "enum"), VariableProblem("$.note", "max_length"),
            VariableProblem("$.flag", "type:boolean"), VariableProblem("$.nope", "required"), VariableProblem("$.ticket", "not_scalar")), problems)
        assertFalse(problems.toString().contains("secret-kind"))
    }

    @Test fun anOptionalMissingVariableRendersEmpty() {
        val extraction = AutomationTemplate.extract(listOf(AutomationVariable("note", "$.note", required = false)), payload("{}"))
        assertTrue(extraction.problems.isEmpty())
        assertEquals("[]", AutomationTemplate.render("[{{note}}]", extraction.values))
        assertFalse(extraction.extracted.single().present)
    }

    @Test fun numbersAndStringsKeepTheirText() {
        val values = AutomationTemplate.extract(listOf(AutomationVariable("n", "$.n", VariableType.NUMBER), AutomationVariable("s", "$.s"), AutomationVariable("i", "$.i", VariableType.INT)),
            payload("""{"n":1.5,"s":42,"i":9007199254740993}""")).values
        assertEquals(mapOf("n" to "1.5", "s" to "42", "i" to "9007199254740993"), values)
        assertEquals("type:int", AutomationTemplate.extract(listOf(AutomationVariable("i", "$.i", VariableType.INT)), payload("""{"i":1.5}""")).problems.single().rule)
    }

    @Test fun aValueIsNeverRescannedForPlaceholders() {
        val values = AutomationTemplate.extract(listOf(AutomationVariable("t", "$.t")), payload("""{"t":"{{run.id}}"}""")).values
        assertEquals("say {{run.id}}", AutomationTemplate.render("say {{t}}", values + ("run.id" to "r1")))
    }

    @Test fun sensitiveValuesAreMaskedInTheExtraction() {
        val extraction = AutomationTemplate.extract(listOf(AutomationVariable("token", "$.token", sensitive = true)), payload("""{"token":"s3cret"}"""))
        assertNull(extraction.extracted.single().value)
        assertEquals("s3cret", extraction.values["token"], "the prompt still receives it")
    }

    @Test fun theDefinitionIsCheckedAtSave() {
        fun refused(prompt: String, variables: List<AutomationVariable> = emptyList(), webhook: Boolean = true) =
            assertEquals("invalid_automation", assertFailsWith<TeamFailure>(prompt) { AutomationTemplate.validateDefinition(prompt, variables, webhook) }.code, prompt)
        refused("Fix {{ticket.number}}")
        refused("Hello {{run.unknown}}")
        refused("")
        refused("x".repeat(32_001))
        refused("Fix {{t}}", listOf(AutomationVariable("t", "ticket")), )
        refused("Fix {{t}}", listOf(AutomationVariable("t", "$.a..b")))
        refused("Fix {{run.x}}", listOf(AutomationVariable("run.x", "$.a")))
        refused("Fix {{t}}", listOf(AutomationVariable("t", "$.a"), AutomationVariable("t", "$.b")))
        refused("Fix {{t}}", listOf(AutomationVariable("t", "$.a", VariableType.ENUM)))
        refused("Fix {{t}}", listOf(AutomationVariable("t", "$.a", maxLength = 0)))
        refused("Fix {{t}}", listOf(AutomationVariable("t", "$.a")), webhook = false)
        refused("Fix {{t}}", (1..33).map { AutomationVariable("t$it", "$.a") } + AutomationVariable("t", "$.a"))
        AutomationTemplate.validateDefinition("Fix {{ ticket.number }} on {{run.date}} for {{automation.name}}", listOf(ticket), webhook = true)
        AutomationTemplate.validateDefinition("Unused variables are fine {{run.id}}", listOf(ticket), webhook = true)
    }

    @Test fun theDigestDependsOnValuesOnly() {
        assertEquals(AutomationTemplate.digest(mapOf("a" to "1", "b" to "2")), AutomationTemplate.digest(mapOf("b" to "2", "a" to "1")))
        assertNotEquals(AutomationTemplate.digest(mapOf("a" to "1")), AutomationTemplate.digest(mapOf("a" to "2")))
    }
}
