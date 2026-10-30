package me.nathanfallet.teamcode.domain.waits

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class WaitRulesTest {
    private fun definition(vararg fields: WaitField, actions: List<WaitAction> = emptyList()) = WaitDefinition("s1", "Questions", fields = fields.toList(), actions = actions)
    private fun code(block: () -> Unit) = assertFailsWith<TeamFailure> { block() }.code
    private val action = listOf(WaitAction("done", "Done"))

    @Test fun aQuestionnaireWithEveryKindOfFieldIsValid() {
        val fields = listOf(
            WaitField("intro", FieldType.INFO, "Context", help = "Read first"),
            WaitField("name", FieldType.TEXT, "Name", required = true, maxLength = 50),
            WaitField("stack", FieldType.CHOICE, "Stack", options = listOf(WaitOption("kmp", "Kotlin"), WaitOption("js", "JS")), allowOther = true),
            WaitField("tags", FieldType.CHOICE, "Tags", options = listOf(WaitOption("a", "A"), WaitOption("b", "B")), multiple = true),
            WaitField("ok", FieldType.CONFIRM, "Sure?"), WaitField("n", FieldType.NUMBER, "How many"),
            WaitField("when", FieldType.DATE, "By when"), WaitField("shot", FieldType.FILE, "Screenshot"),
        )
        val valid = WaitRules.validate(definition(*fields.toTypedArray()))
        assertEquals(listOf("done"), valid.actions.map { it.id })
        WaitRules.checkAnswer(valid.fields, valid.actions, "done", mapOf(
            "name" to JsonPrimitive("Ada"), "stack" to JsonPrimitive("Rust"), "tags" to buildJsonArray { add("a"); add("b") },
            "ok" to JsonPrimitive(true), "n" to JsonPrimitive(3), "when" to JsonPrimitive("2026-11-02"), "shot" to JsonPrimitive("a.png"),
        ))
    }

    @Test fun secretsAndOtherResolversAreNotAvailableYet() {
        assertEquals("not_available_yet", code { WaitRules.validate(definition(WaitField("pw", FieldType.SECRET, "Password"))) })
        assertEquals("not_available_yet", code { WaitRules.validate(WaitDefinition("s1", "Wait for CI", resolver = WaitResolver.EVENT)) })
    }

    @Test fun definitionsAreBounded() {
        assertEquals("invalid_wait", code { WaitRules.validate(WaitDefinition("s1", " ")) })
        assertEquals("invalid_wait", code { WaitRules.validate(WaitDefinition("s1", "x".repeat(201))) })
        assertEquals("invalid_wait", code { WaitRules.validate(definition(WaitField("a", FieldType.TEXT, "A"), WaitField("a", FieldType.TEXT, "B"))) })
        assertEquals("invalid_wait", code { WaitRules.validate(definition(WaitField("1bad", FieldType.TEXT, "A"))) })
        assertEquals("invalid_wait", code { WaitRules.validate(definition(WaitField("c", FieldType.CHOICE, "C"))) }, "a choice needs options")
        assertEquals("invalid_wait", code { WaitRules.validate(definition(WaitField("t", FieldType.TEXT, "T", options = listOf(WaitOption("a", "A"))))) })
        assertEquals("invalid_wait", code { WaitRules.validate(definition(WaitField("t", FieldType.TEXT, "T", default = JsonPrimitive(3)))) })
        assertEquals("invalid_wait", code { WaitRules.validate(definition(WaitField("i", FieldType.INFO, "I"), actions = listOf(WaitAction("go", "Go", requires = listOf("i"))))) })
        assertEquals("invalid_wait", code { WaitRules.validate(definition(actions = listOf(WaitAction("go", "Go"), WaitAction("go", "Again")))) })
        assertEquals("invalid_wait", code { WaitRules.validate(definition(actions = listOf(WaitAction("go", "Go", style = "huge")))) })
        assertEquals("invalid_wait", code { WaitRules.validate(definition(actions = (1..9).map { WaitAction("a$it", "A") })) })
    }

    @Test fun referencesAreArtifactsSessionsOrHttpsLinks() {
        fun refs(vararg ref: WaitRef) = WaitDefinition("s1", "Look", refs = ref.toList())
        WaitRules.validate(refs(WaitRef("artifact", id = "a1"), WaitRef("session", id = "s2"), WaitRef("url", url = "https://example.com/pr/1", title = "PR")))
        assertEquals("invalid_wait", code { WaitRules.validate(refs(WaitRef("url", url = "http://example.com"))) })
        assertEquals("invalid_wait", code { WaitRules.validate(refs(WaitRef("url", url = "javascript:alert(1)"))) })
        assertEquals("invalid_wait", code { WaitRules.validate(refs(WaitRef("artifact"))) })
        assertEquals("invalid_wait", code { WaitRules.validate(refs(WaitRef("artifact", id = "a1", url = "https://x.test"))) })
        assertEquals("invalid_wait", code { WaitRules.validate(refs(WaitRef("file", id = "f"))) })
    }

    @Test fun answersAreCheckedAgainstTheirFields() {
        val fields = listOf(
            WaitField("stack", FieldType.CHOICE, "Stack", options = listOf(WaitOption("kmp", "Kotlin"))),
            WaitField("ok", FieldType.CONFIRM, "Sure?"), WaitField("n", FieldType.NUMBER, "N"), WaitField("when", FieldType.DATE, "When"),
            WaitField("note", FieldType.TEXT, "Note", maxLength = 5), WaitField("intro", FieldType.INFO, "Info"),
        )
        fun answer(id: String, value: JsonElement) = code { WaitRules.checkAnswer(fields, action, "done", mapOf(id to value)) }
        assertEquals("invalid_answer", answer("stack", JsonPrimitive("rust")))
        assertEquals("invalid_answer", answer("ok", JsonPrimitive("true")), "a string is not a boolean")
        assertEquals("invalid_answer", answer("n", JsonPrimitive("3")))
        assertEquals("invalid_answer", answer("when", JsonPrimitive("02/11/2026")))
        assertEquals("invalid_answer", answer("when", JsonPrimitive("2026-02-31")))
        assertEquals("invalid_answer", answer("note", JsonPrimitive("too long")))
        assertEquals("invalid_answer", answer("intro", JsonPrimitive("x")))
        assertEquals("invalid_answer", answer("ghost", JsonPrimitive("x")))
        WaitRules.checkAnswer(fields, action, "done", mapOf("stack" to JsonNull))
    }

    @Test fun requiredFieldsAndRequiredByActionAreEnforced() {
        val fields = listOf(WaitField("a", FieldType.TEXT, "A", required = true), WaitField("b", FieldType.CHOICE, "B", options = listOf(WaitOption("x", "X")), multiple = true))
        val actions = listOf(WaitAction("send", "Send"), WaitAction("send_b", "Send with B", requires = listOf("b")))
        assertEquals("invalid_answer", code { WaitRules.checkAnswer(fields, actions, "send", emptyMap()) })
        WaitRules.checkAnswer(fields, actions, "send", mapOf("a" to JsonPrimitive("x")))
        assertEquals("invalid_answer", code { WaitRules.checkAnswer(fields, actions, "send_b", mapOf("a" to JsonPrimitive("x"), "b" to JsonArray(emptyList()))) })
        WaitRules.checkAnswer(fields, actions, "send_b", mapOf("a" to JsonPrimitive("x"), "b" to buildJsonArray { add("x") }))
    }
}
