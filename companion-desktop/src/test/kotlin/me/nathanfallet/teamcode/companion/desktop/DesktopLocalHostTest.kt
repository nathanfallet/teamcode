package me.nathanfallet.teamcode.companion.desktop

import com.russhwolf.settings.MapSettings
import java.io.File
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.services.LocalAvailability
import me.nathanfallet.teamcode.companion.domain.services.LocalHostException
import me.nathanfallet.teamcode.companion.domain.services.LocalSessionRequest
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsAccountRepository
import kotlin.test.*

/** "On this device" through a fake CLI: what is checked first, the command line, the created id, a refusal. */
class DesktopLocalHostTest {
    private val request = LocalSessionRequest("/Users/ada/git/paywall", "claude", "Paywall", "", "high", false)

    @Test fun theCommandLineCarriesNoMessage() {
        assertEquals(listOf("remote", "start", "/Users/ada/git/paywall", "--agent", "claude", "--name", "Paywall", "--effort", "high", "--json", "--detach"),
            DesktopLocalHost.startArguments(request))
        assertEquals(listOf("remote", "start", "/w", "--agent", "codex", "--name", "W", "--model", "gpt-5", "--bypass-permissions", "--json", "--detach"),
            DesktopLocalHost.startArguments(LocalSessionRequest("/w", "codex", "W", "gpt-5", "", true)))
    }

    @Test fun theIdAndTheClisExplanationAreRead() {
        assertEquals("s-1", DesktopLocalHost.sessionId("notice\n{\"id\":\"s-1\",\"name\":\"x\"}\n"))
        assertNull(DesktopLocalHost.sessionId("{\"id\":\"../../etc\"}"))
        assertEquals("Choose a project directory: /Users/ada itself.", DesktopLocalHost.explanation(CliResult(1, "", "Warning\nError: Choose a project directory: /Users/ada itself.\n")))
        assertEquals("The TeamCode CLI did not answer in time.", DesktopLocalHost.explanation(CliResult(null, "", "")))
    }

    private fun host(directory: File, accounts: SettingsAccountRepository, appVersion: String = "1.0.0"): DesktopLocalHost {
        val environment = DesktopEnvironment(mapOf("HOME" to File(directory, "home").path, "PATH" to directory.path), os = DesktopEnvironment.Os.current())
        val processes = CliProcesses(environment)
        return DesktopLocalHost(CliLocator(environment, processes), processes, accounts, environment, appVersion, picker = { null })
    }

    private fun cliAccount() = Account(Account.CLI_ACCOUNT_ID, "https://team.example", "ada", "m1", "MEMBER", "Mac", Long.MAX_VALUE, "1.0.0")

    @Test fun availabilityChecksTheCliThenItsVersionThenItsAccount() = withTemporaryDirectory { directory -> runTest {
        if (!posix) return@runTest
        val accounts = SettingsAccountRepository(MapSettings())
        assertIs<LocalAvailability.CliMissing>(host(directory, accounts).availability("a1"))
        fakeCli(directory, version = "0.9.0")
        assertEquals(LocalAvailability.CliTooOld("0.9.0", "1.0.0", "teamcode update"), host(directory, accounts).availability("a1"))
        fakeCli(directory, version = "1.0.3")
        assertEquals(LocalAvailability.OtherAccount(null), host(directory, accounts).availability("a1"))
        accounts.save(cliAccount())
        accounts.save(Account("a1", "https://team.example", "grace", "m2", "MEMBER", "Mac", 9_000_000, "1.0.0"))
        assertEquals(LocalAvailability.OtherAccount("ada on team.example"), host(directory, accounts).availability("a1"))
        assertEquals(LocalAvailability.Ready, host(directory, accounts).availability(Account.CLI_ACCOUNT_ID))
    } }

    @Test fun startRunsTheCliAndReturnsTheSession() = withTemporaryDirectory { directory -> runTest {
        if (!posix) return@runTest
        fakeCli(directory)
        val accounts = SettingsAccountRepository(MapSettings()).apply { save(cliAccount()) }
        assertEquals("s-new", host(directory, accounts).start(Account.CLI_ACCOUNT_ID, request))
        assertTrue(File(directory, "calls").readLines().any { it.startsWith("remote start /Users/ada/git/paywall --agent claude --name Paywall --effort high --json --detach") })
    } }

    @Test fun aRefusalIsTheClisOwnSentence() = withTemporaryDirectory { directory -> runTest {
        if (!posix) return@runTest
        fakeCli(directory, remote = "echo 'Error: /Users/ada/git/paywall is not a directory.' >&2; exit 1")
        val accounts = SettingsAccountRepository(MapSettings()).apply { save(cliAccount()) }
        val error = assertFailsWith<LocalHostException> { host(directory, accounts).start(Account.CLI_ACCOUNT_ID, request) }
        assertEquals("/Users/ada/git/paywall is not a directory.", error.message)
    } }
}
