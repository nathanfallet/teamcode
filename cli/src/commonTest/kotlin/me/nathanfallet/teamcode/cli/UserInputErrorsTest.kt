package me.nathanfallet.teamcode.cli

import com.github.ajalt.clikt.command.parse
import com.github.ajalt.clikt.core.CliktError
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** A mistake in what the user typed is reported with its own message, never as an unexpected failure. */
class UserInputErrorsTest {
    @Test fun cloudDeleteWithoutYesExplainsHowToConfirm() = runTest {
        val error = assertFailsWith<CliktError> { Cli().parse(listOf("cloud", "delete", "session-1")) }
        assertEquals("Deleting session-1 also deletes its workspace and cannot be undone. Add --yes to confirm.", error.message)
        // main() leaves CliktError to Clikt: message on stderr, non-zero exit. Anything else is "failed unexpectedly".
        assertFalse(describeFailure(IllegalArgumentException("x"), null).message.contains("Add --yes"))
    }

    @Test fun incompatibleOptionsAreUsageErrors() = runTest {
        val error = assertFailsWith<CliktError> { Cli().parse(listOf("cloud", "list", "--all", "--member", "m1")) }
        assertTrue(error.message.orEmpty().contains("--all or --member"))
    }
}
