package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.cli.accounts.*
import kotlin.test.*

class TeamClaudeImportTest {
    @Test fun `account credentials parsed without importing proxy privileges or identity hints`() {
        val parsed = parseTeamClaudeConfig("""{"proxy":{"apiKey":"operator-secret"},"accounts":[{"type":"oauth","name":"Synthetic","accountUuid":"untrusted","orgUuid":"untrusted","accessToken":"access-secret","refreshToken":"refresh-secret","expiresAt":2000000000000}]}""")
        val account = assertIs<ImportEntry.Ready>(parsed.single())
        assertEquals("Synthetic", account.label)
        assertEquals("access-secret", account.credentials.accessToken)
        assertFalse(account.toString().contains("access-secret"))
        assertFalse(account.credentials.toString().contains("refresh-secret"))
    }
    @Test fun `Codex OAuth is imported without trusting source identity hints`() {
        val parsed = parseTeamClaudeConfig("""{"accounts":[{"provider":"codex","type":"oauth","accessToken":"synthetic","refreshToken":"refresh","expiresAt":2000000000000,"accountId":"untrusted"}]}""")
        assertEquals("codex", assertIs<ImportEntry.Ready>(parsed.single()).credentials.provider)
    }
    @Test fun `invalid or unsupported entries produce a per account report`() {
        val parsed = parseTeamClaudeConfig("""{"accounts":[null,{"type":"apikey","name":"API","apiKey":"hidden"},{"type":"oauth","provider":"codex","name":"Codex"},{"type":"oauth","name":"Expired","accessToken":"access","expiresAt":1}]}""")
        assertEquals(4, parsed.size)
        assertTrue(parsed.all { it is ImportEntry.Rejected })
        assertFalse(parsed.toString().contains("hidden"))
    }
    @Test fun `malformed configuration error cannot echo credentials`() {
        val error = assertFailsWith<com.github.ajalt.clikt.core.CliktError> { parseTeamClaudeConfig("{\"accessToken\":\"secret-value") }
        assertFalse(error.toString().contains("secret-value"))
        assertNull(error.cause)
    }
    @Test fun `no accounts is different from missing account list`() {
        assertEquals(emptyList(), parseTeamClaudeConfig("""{"accounts":[]}"""))
        assertFailsWith<com.github.ajalt.clikt.core.CliktError> { parseTeamClaudeConfig("{}") }
    }
}
