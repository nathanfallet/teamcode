package me.nathanfallet.teamcode.companion.desktop

import com.russhwolf.settings.MapSettings
import java.io.File
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.services.LocalAvailability
import me.nathanfallet.teamcode.companion.domain.services.LocalHostException
import me.nathanfallet.teamcode.companion.domain.services.LocalSessionRequest
import me.nathanfallet.teamcode.companion.domain.services.LocalResumables
import me.nathanfallet.teamcode.companion.domain.services.LocalResumeBlock
import me.nathanfallet.teamcode.companion.domain.models.ResumableSession
import me.nathanfallet.teamcode.companion.domain.models.ResumableSessions
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

    @Test fun aResumedConversationIsOnTheCommandLineForClaudeCodeOnly() {
        assertEquals(listOf("remote", "start", "/Users/ada/git/paywall", "--agent", "claude", "--name", "Paywall", "--effort", "high", "--resume", ID, "--json", "--detach"),
            DesktopLocalHost.startArguments(request.copy(resumeSessionId = ID)))
        assertFalse("--resume" in DesktopLocalHost.startArguments(request.copy(agent = "codex", resumeSessionId = ID)))
        assertEquals(listOf("remote", "resumable", "/Users/ada/git/paywall", "--json"), DesktopLocalHost.resumableArguments("/Users/ada/git/paywall", null))
        assertEquals(listOf("remote", "resumable", "/w", "--json", "--before", "1700000000000"), DesktopLocalHost.resumableArguments("/w", 1_700_000_000_000))
    }

    @Test fun theClisListingIsRead() {
        val page = DesktopLocalHost.resumables(CliResult(0,
            """{"sessions":[{"id":"$ID","title":"Fix the build","lastActiveAt":1700000000000,"turns":3}],"nextBefore":1700000000000,"unavailable":null}""", ""))
        assertEquals(LocalResumables.Page(ResumableSessions(listOf(ResumableSession(ID, "Fix the build", 1_700_000_000_000, 3)), 1_700_000_000_000)), page)
        assertEquals(LocalResumables.Page(ResumableSessions(emptyList())), DesktopLocalHost.resumables(CliResult(0, """{"sessions":[]}""", "")))
        assertEquals(LocalResumables.Unavailable(LocalResumeBlock.DAEMON_OFFLINE), DesktopLocalHost.resumables(CliResult(0, """{"unavailable":"daemon_offline"}""", "")))
        assertEquals(LocalResumables.Unavailable(LocalResumeBlock.DAEMON_TOO_OLD), DesktopLocalHost.resumables(CliResult(0, """{"unavailable":"daemon_too_old"}""", "")))
        assertEquals(LocalResumables.Unavailable(LocalResumeBlock.PLATFORM), DesktopLocalHost.resumables(CliResult(0, """{"unavailable":"platform_unsupported"}""", "")))
        assertEquals(LocalResumables.Unavailable(LocalResumeBlock.SERVER), DesktopLocalHost.resumables(CliResult(0, """{"unavailable":"server_unsupported"}""", "")))
        // A CLI from before the command.
        assertEquals(LocalResumables.Unavailable(LocalResumeBlock.CLI_TOO_OLD),
            DesktopLocalHost.resumables(CliResult(1, "", "Usage: teamcode remote [<options>] <command>\n\nError: no such subcommand resumable\n")))
        // Its own refusal is said as is; an id that is not a Claude Code session id is never offered.
        assertEquals("/Users/ada is not a directory.", assertFailsWith<LocalHostException> { DesktopLocalHost.resumables(CliResult(1, "", "Error: /Users/ada is not a directory.\n")) }.message)
        assertEquals(LocalResumables.Page(ResumableSessions(emptyList())),
            DesktopLocalHost.resumables(CliResult(0, """{"sessions":[{"id":"../../etc","title":"x","lastActiveAt":1,"turns":1}]}""", "")))
        assertFailsWith<LocalHostException> { DesktopLocalHost.resumables(CliResult(0, "not json", "")) }
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

    @Test fun theListingRunsTheCliInTheChosenFolder() = withTemporaryDirectory { directory -> runTest {
        if (!posix) return@runTest
        fakeCli(directory, remote = """echo '{"sessions":[{"id":"$ID","title":"Fix the build","lastActiveAt":5,"turns":2}]}'""")
        val accounts = SettingsAccountRepository(MapSettings()).apply { save(cliAccount()) }
        val found = host(directory, accounts).resumable(Account.CLI_ACCOUNT_ID, "/Users/ada/git/paywall", null)
        assertEquals(listOf(ID), (found as LocalResumables.Page).page.sessions.map { it.id })
        assertTrue(File(directory, "calls").readLines().any { it.startsWith("remote resumable /Users/ada/git/paywall --json") })
    } }

    @Test fun aRefusalIsTheClisOwnSentence() = withTemporaryDirectory { directory -> runTest {
        if (!posix) return@runTest
        fakeCli(directory, remote = "echo 'Error: /Users/ada/git/paywall is not a directory.' >&2; exit 1")
        val accounts = SettingsAccountRepository(MapSettings()).apply { save(cliAccount()) }
        val error = assertFailsWith<LocalHostException> { host(directory, accounts).start(Account.CLI_ACCOUNT_ID, request) }
        assertEquals("/Users/ada/git/paywall is not a directory.", error.message)
    } }

    private companion object { const val ID = "0000000a-aaaa-4bbb-8ccc-00000000000a" }
}
