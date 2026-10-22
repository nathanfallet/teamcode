package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.cli.config.*
import kotlin.test.*

class ConfigurationTest {
    @Test fun `configuration honors XDG or falls back to home dot config`() {
        assertEquals("/custom/teamcode/config.json", configPath { if (it == "XDG_CONFIG_HOME") "/custom" else null })
        assertEquals("/users/me/.config/teamcode/config.json", configPath { if (it == "HOME") "/users/me" else null })
        assertFailsWith<CliUserException> { configPath { if (it == "XDG_CONFIG_HOME") "relative" else null } }
    }
    @Test fun `remote plaintext redirects and embedded credentials are not acceptable origins`() {
        for (value in listOf("", "   ", "http://team.example", "https://user:secret@team.example", "https://team.example/path", "https://team.example?token=secret", "https://team.example#fragment")) {
            val error = assertFailsWith<CliUserException>(value) { validateServerUrl(value) }
            assertFalse(error.message.contains("secret"), "The refusal must not repeat the value")
        }
        assertEquals("https://self.hosted.example", validateServerUrl("https://self.hosted.example/"))
        assertEquals("http://127.0.0.1:8080", validateServerUrl("http://127.0.0.1:8080"))
    }
    @Test fun `configuration debug rendering redacts the access token`() {
        assertFalse(CliConfig("https://team.example", "secret-value").toString().contains("secret-value"))
    }
}
