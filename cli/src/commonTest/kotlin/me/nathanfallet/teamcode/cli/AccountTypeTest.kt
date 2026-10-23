package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.cli.accounts.*
import me.nathanfallet.teamcode.client.API_KEY_UNSUPPORTED
import me.nathanfallet.teamcode.client.AccountType
import kotlin.test.*

class AccountTypeTest {
    private fun choose(type: String? = null, provider: String? = null, input: String? = null, out: MutableList<String> = mutableListOf()) =
        chooseAccountType(type, provider, { out += it }) { input }

    @Test fun theSelectorListsThreeNumberedTypes() {
        val out = mutableListOf<String>()
        choose(input = "2", out = out)
        assertTrue(out.any { it.startsWith("  1. Anthropic subscription") })
        assertTrue(out.any { it.startsWith("  2. Codex subscription") })
        assertTrue(out.any { it.startsWith("  3. Anthropic API key (Claude Console") })
    }

    @Test fun numbersAndDefaultPickTheType() {
        assertEquals(AccountType.ANTHROPIC_SUBSCRIPTION, choose(input = "1"))
        assertEquals(AccountType.ANTHROPIC_SUBSCRIPTION, choose(input = ""))
        assertEquals(AccountType.CODEX_SUBSCRIPTION, choose(input = " 2 "))
        assertEquals(AccountType.CODEX_SUBSCRIPTION, choose(input = "codex-subscription"))
    }

    @Test fun badAnswersAndClosedStdinFailWithGuidance() {
        assertContains(assertFailsWith<CliUserException> { choose(input = "9") }.message, "Invalid choice")
        assertContains(assertFailsWith<CliUserException> { choose(input = "abc") }.message, "Invalid choice")
        assertContains(assertFailsWith<CliUserException> { choose(input = null) }.message, "--type")
    }

    @Test fun typeFlagNeverPrompts() {
        val out = mutableListOf<String>()
        assertEquals(AccountType.CODEX_SUBSCRIPTION, chooseAccountType("codex-subscription", null, { out += it }) { error("no prompt") })
        assertTrue(out.isEmpty())
    }

    @Test fun legacyProviderFlagStillWorksAndConflictsAreRefused() {
        assertEquals(AccountType.ANTHROPIC_SUBSCRIPTION, chooseAccountType(null, "claude-max", {}) { error("no prompt") })
        assertEquals(AccountType.CODEX_SUBSCRIPTION, chooseAccountType(null, "codex", {}) { error("no prompt") })
        assertFailsWith<CliUserException> { choose(type = "codex-subscription", provider = "claude") }
        assertEquals(AccountType.CODEX_SUBSCRIPTION, choose(type = "codex-subscription", provider = "codex"))
    }

    @Test fun apiKeyIsExplainedNotSilentlyAnotherType() {
        assertEquals(API_KEY_UNSUPPORTED, assertFailsWith<CliUserException> { choose(type = "anthropic-api") }.message)
        assertEquals(API_KEY_UNSUPPORTED, assertFailsWith<CliUserException> { choose(input = "3") }.message)
    }
}
