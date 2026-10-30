package me.nathanfallet.teamcode.cli

import kotlin.test.*

/** `teamcode automations` is wired into the CLI with every subcommand of the plan. */
class AutomationCommandsRegistrationTest {
    @Test fun `the group lists every subcommand`() {
        val group = Cli().registeredSubcommands().single { it.commandName == "automations" }
        assertEquals(setOf("list", "show", "create", "edit", "enable", "disable", "delete", "rotate-secret", "runs", "run"), group.registeredSubcommands().map { it.commandName }.toSet())
    }
}
