package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.memory.MemoryRules
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class MemoryRulesTest {
    private fun check(title: String = "Exposed only", summary: String = "Queries use the Exposed DSL", content: String = "No raw SQL in this project.", tags: List<String> = listOf("database"), refs: List<String> = emptyList(), expiresAt: Long? = null) =
        MemoryRules.validate(title, summary, content, tags, refs, expiresAt, now = 1_000)
    private fun code(block: () -> Unit) = assertFailsWith<TeamFailure> { block() }.code

    @Test fun anOrdinaryEntryIsAccepted() = check()

    @Test fun fieldsAreBounded() {
        assertEquals("invalid_memory", code { check(title = "") })
        assertEquals("invalid_memory", code { check(title = "a".repeat(MemoryRules.TITLE_LENGTH + 1)) })
        assertEquals("invalid_memory", code { check(title = "two\nlines") })
        assertEquals("invalid_memory", code { check(summary = "x".repeat(MemoryRules.SUMMARY_LENGTH + 1)) })
        assertEquals("invalid_memory", code { check(content = "y".repeat(MemoryRules.CONTENT_LENGTH + 1)) })
        assertEquals("invalid_memory", code { check(tags = List(MemoryRules.TAGS_LIMIT + 1) { "t$it" }) })
        assertEquals("invalid_memory", code { check(tags = listOf("Upper")) })
        assertEquals("invalid_memory", code { check(tags = listOf("same", "same")) })
        assertEquals("invalid_memory", code { check(refs = List(MemoryRules.REFS_LIMIT + 1) { "ref-$it" }) })
        assertEquals("invalid_memory", code { check(expiresAt = 500) })
        check(expiresAt = 2_000)
    }

    @Test fun contentThatLooksLikACredentialIsRefused() {
        val shapes = listOf(
            "-----BEGIN RSA PRIVATE KEY----- MIIE", "key sk-ant-api03-abcdefghijklmnopqrstuv", "token ghp_abcdefghijklmnopqrstuvwx0123", "github_pat_11ABCDEFG0123456789abcdef",
            "xoxb-1234567890-abcdefghij", "AKIAABCDEFGHIJKLMNOP", "a tcsk_abcdefghijklmnopqrstuv key", "Authorization: Bearer abcdefghijklmnopqrstu", "password = hunter2hunter2hunter2",
            "API_KEY: 0123456789abcdefghij",
        )
        for (shape in shapes) {
            assertEquals("memory_secret", code { check(content = "Note: $shape") }, shape)
            assertEquals("memory_secret", code { check(summary = shape) }, shape)
            assertEquals("memory_secret", code { check(refs = listOf(shape)) }, shape)
        }
        // Describing where a secret lives is fine.
        check(content = "The token is in the vault under teamcode/prod; ask the owner. Passwords are never written here.")
    }

    @Test fun searchTermsAreCleanedAndBounded() {
        assertEquals(listOf("exposed", "dsl"), MemoryRules.terms("Exposed, DSL!"))
        assertEquals(5, MemoryRules.terms("one two three four five six seven").size)
        assertEquals("invalid_memory", code { MemoryRules.terms("a ?") })
    }
}
