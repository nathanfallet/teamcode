package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.api.models.RunnerGitHubView
import me.nathanfallet.teamcode.cli.cloud.agent.*
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.*

/** The agent's `gh` configuration, written and renewed by the runner from the same source as the git helper. */
class GitHubCliConfigTest {
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "tc-gh-${Random.nextInt().toUInt().toString(16)}"
    private val config by lazy { FileSystem.SYSTEM.createDirectories(directory); GitHubCliConfig(directory.toString()) }
    @AfterTest fun cleanup() { FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false) }

    private fun hosts(): String? = FileSystem.SYSTEM.metadataOrNull(config.hosts)?.let { FileSystem.SYSTEM.read(config.hosts) { readUtf8() } }
    /** Every file left in the directory, so a test can prove no other copy of a token survives. */
    private fun everything(): String = FileSystem.SYSTEM.list(directory).joinToString("\n") { FileSystem.SYSTEM.read(it) { readUtf8() } }
    private fun notice(): String? = FileSystem.SYSTEM.metadataOrNull(config.notice)?.let { FileSystem.SYSTEM.read(config.notice) { readUtf8() } }

    @Test fun writesGhMultiAccountHostsForGitHubOverHttps() {
        config.write(GitHubToken("ghu_owner_token", "octo-cat"))
        assertEquals("""
            github.com:
                users:
                    "octo-cat":
                        oauth_token: ghu_owner_token
                git_protocol: https
                oauth_token: ghu_owner_token
                user: "octo-cat"

        """.trimIndent(), hosts())
        assertEquals(listOf(GitHubCliConfig.HOSTS_FILE), FileSystem.SYSTEM.list(directory).map { it.name }, "No temporary file is left behind")
    }

    @Test fun withoutALoginWritesTheSingleAccountForm() {
        config.write(GitHubToken("ghu_owner_token", null))
        assertEquals("github.com:\n    git_protocol: https\n    oauth_token: ghu_owner_token\n", hosts())
        assertEquals("github.com:\n    git_protocol: https\n    oauth_token: ghu_t\n", GitHubCliConfig.hostsYaml(GitHubToken("ghu_t", "bad login: x")),
            "A login that is not a GitHub login is left out rather than written into the YAML")
        assertTrue("user: \"octocat_acme\"" in GitHubCliConfig.hostsYaml(GitHubToken("ghu_t", "octocat_acme"))!!, "Enterprise Managed User logins")
        assertTrue("user: \"123\"" in GitHubCliConfig.hostsYaml(GitHubToken("ghu_t", "123"))!!, "Quoted: a numeric login stays a string")
    }

    @Test fun noTokenOrAnUnsafeTokenRemovesTheFileSoGhIsUnauthenticated() {
        config.write(GitHubToken("ghu_owner_token", "octo"))
        config.write(null)
        assertNull(hosts())
        config.write(GitHubToken("ghu_owner_token", "octo"))
        assertFalse(config.write(GitHubToken("ghu_x\n    oauth_token: injected", "octo")))
        assertNull(hosts(), "A token that would change the YAML's structure is never written")
        config.write(null) // Already gone: succeeds.
    }

    /** A runner's renewal loop over a scripted server; each `sleep` hands control back to the test. */
    private class Harness(scope: TestScope, val config: GitHubCliConfig, var answer: () -> RunnerGitHubView) {
        var clock = 0L
        var calls = 0
        val waits = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        private val wake = Channel<Unit>(Channel.UNLIMITED)
        val source = GitHubCredentialSource({ calls++; answer() }, now = { clock })
        val job = scope.launch {
            GitHubCliRenewal(source, config, now = { clock }, sleep = { waits += it; wake.receive(); clock += it }, log = { logs += it }, io = Dispatchers.Unconfined).run()
        }
        fun next() = wake.trySend(Unit)
    }

    @Test fun renewsBeforeExpiryFollowsTheServersCurrentOwnerAndRemovesTheFileWhenNoLinkIsLeft() = runTest {
        val h = Harness(this, config) { RunnerGitHubView("linked", "ghu_owner_a_1", 3_600_000, "member-a") }
        runCurrent()
        assertTrue("ghu_owner_a_1" in hosts()!!); assertTrue("user: \"member-a\"" in hosts()!!)
        assertEquals(listOf(3_600_000 - GitHubCredentialSource.RENEW_MARGIN_MILLIS), h.waits, "Sleeps until the renewal margin")

        // The token is renewed shortly before it expires: the file holds the new one, never both.
        h.answer = { RunnerGitHubView("linked", "ghu_owner_a_2", 7_200_000, "member-a") }
        h.next(); runCurrent()
        assertTrue("ghu_owner_a_2" in hosts()!!); assertFalse("ghu_owner_a_1" in everything())

        // The server answers for the execution's current owner on every call: member B's token replaces A's.
        h.answer = { RunnerGitHubView("linked", "ghu_owner_b", 10_800_000, "member-b") }
        h.next(); runCurrent()
        assertTrue("user: \"member-b\"" in hosts()!!); assertTrue("ghu_owner_b" in hosts()!!)
        assertFalse("ghu_owner_a" in everything() || "member-a" in everything(), "Nothing of the former owner is left")

        // B unlinks: gh becomes unauthenticated, it never falls back to an earlier token, and the directory says why.
        h.answer = { RunnerGitHubView("not_linked") }
        h.next(); runCurrent()
        assertNull(hosts()); assertFalse("ghu_" in everything())
        assertTrue("teamcode github link" in notice()!!, "An agent looking at the empty gh configuration learns why and what the owner does")
        assertEquals(listOf("The session owner has no usable GitHub link: gh and git run unauthenticated until they link GitHub again (teamcode github link)."), h.logs)
        h.logs.clear()
        assertEquals(GitHubCliRenewal.IDLE_MILLIS, h.waits.last(), "Without a link the server is asked again every few minutes, not every 30 s")

        // Linking during the session is picked up at the next check.
        h.answer = { RunnerGitHubView("linked", "ghu_owner_b_new", null, "member-b") }
        h.next(); runCurrent()
        assertTrue("ghu_owner_b_new" in hosts()!!)
        assertNull(notice(), "The notice goes once the owner is linked again")
        assertEquals(5, h.calls)
        h.job.cancel(); runCurrent()
        assertNull(hosts(), "The runner removes the file when it stops")
        assertEquals(emptyList(), FileSystem.SYSTEM.list(directory), "Nothing is left when the runner stops")
        assertTrue(h.logs.isEmpty())
    }

    @Test fun aNewOwnersRunnerNeverServesTheFormerOwnersToken() = runTest {
        // Member A's execution, then a transfer: the pod ends, and B's execution starts a new runner.
        val a = Harness(this, config) { RunnerGitHubView("linked", "ghu_member_a", 3_600_000, "member-a") }
        runCurrent()
        assertTrue("ghu_member_a" in hosts()!!)
        a.job.cancel(); runCurrent()
        assertNull(hosts())

        // Even over a directory a former runner left a file in (the memory volume normally goes with the pod).
        config.write(GitHubToken("ghu_member_a", "member-a"))
        val b = Harness(this, config) { RunnerGitHubView("linked", "ghu_member_b", 3_600_000, "member-b") }
        runCurrent()
        assertTrue("ghu_member_b" in hosts()!!)
        assertFalse("member-a" in everything())
        b.job.cancel(); runCurrent()

        // B without a link: unauthenticated gh, not A's token.
        config.write(GitHubToken("ghu_member_a", "member-a"))
        val unlinked = Harness(this, config) { RunnerGitHubView("not_linked") }
        runCurrent()
        assertNull(hosts()); assertFalse("ghu_" in everything() || "member-a" in everything())
        unlinked.job.cancel(); runCurrent()
    }

    @Test fun aFailingServerKeepsTheLastTokenRetriesThenBacksOffAndLogsOncePerStreak() = runTest {
        var fail = false
        val h = Harness(this, config) { if (fail) throw IllegalStateException("ghu_secret_in_message") else RunnerGitHubView("linked", "ghu_kept", 600_000, "octo") }
        runCurrent()
        fail = true
        h.next(); runCurrent()
        assertTrue("ghu_kept" in hosts()!!, "The owner's last token stays until it can be renewed")
        assertEquals(GitHubCredentialSource.RETRY_MILLIS, h.waits.last())
        h.next(); runCurrent()
        assertEquals(GitHubCliRenewal.IDLE_MILLIS, h.waits.last())
        assertEquals(listOf("The GitHub CLI configuration could not be renewed."), h.logs, "Logged once, without the cause")
        fail = false
        h.next(); runCurrent()
        assertTrue("ghu_kept" in hosts()!!)
        h.job.cancel(); runCurrent()
    }

    @Test fun aTokenAboutToExpireIsNotRenewedInATightLoop() = runTest {
        val h = Harness(this, config) { RunnerGitHubView("linked", "ghu_short", 60_000, "octo") }
        runCurrent()
        assertEquals(GitHubCredentialSource.RETRY_MILLIS, h.waits.single())
        h.job.cancel(); runCurrent()
    }

    @Test fun anExpiredTokenIsRemovedWhenItCannotBeRenewed() = runTest {
        var fail = false
        val h = Harness(this, config) { if (fail) throw IllegalStateException("down") else RunnerGitHubView("linked", "ghu_expiring", 1_000_000, "octo") }
        runCurrent()
        fail = true
        h.next(); runCurrent() // At the renewal margin: still valid, kept.
        assertTrue("ghu_expiring" in hosts()!!)
        h.next(); runCurrent() // 30 s later: still before the expiry.
        assertNotNull(hosts())
        h.next(); runCurrent() // Past the expiry: gh must not claim a login with a dead token.
        assertNull(hosts())
        h.job.cancel(); runCurrent()
    }

    @Test fun aTokenThatCannotBeWrittenIsLoggedOnceWithoutItsValue() = runTest {
        val h = Harness(this, config) { RunnerGitHubView("linked", "ghu-not.github's", 3_600_000, "octo") }
        runCurrent()
        assertNull(hosts())
        h.next(); runCurrent()
        assertEquals(listOf("The GitHub token was not written to the gh configuration: unexpected format."), h.logs)
        assertEquals(GitHubCliRenewal.IDLE_MILLIS, h.waits.last())
        h.job.cancel(); runCurrent()
    }

    @Test fun theFirstPassIsDoneBeforeTheLoopSoTheAgentStartsWithTheFile() = runTest {
        val source = GitHubCredentialSource({ RunnerGitHubView("linked", "ghu_first", 3_600_000, "octo") }, now = { 0 })
        val renewal = GitHubCliRenewal(source, config, now = { 0 }, io = Dispatchers.Unconfined)
        val wait = renewal.refresh()
        assertTrue("ghu_first" in hosts()!!, "Written before the runner launches the loop and starts the agent")
        assertEquals(3_600_000 - GitHubCredentialSource.RENEW_MARGIN_MILLIS, wait)
        renewal.clear()
        assertNull(hosts())
    }
}
