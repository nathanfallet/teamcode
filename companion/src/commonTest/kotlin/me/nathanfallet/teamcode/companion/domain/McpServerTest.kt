package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeMcpServerRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.McpReason
import me.nathanfallet.teamcode.companion.domain.models.McpStatus
import me.nathanfallet.teamcode.companion.domain.models.McpTransport
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.mcp.LoadMcpServersUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.mcp.McpServersAvailabilityUseCase
import me.nathanfallet.teamcode.companion.mcpServer
import kotlin.test.*

/** What the app says of a server's state, and which servers it lists. */
class McpServerTest {
    private fun status(state: String?, failure: String? = null, transport: McpTransport = McpTransport.STDIO) = mcpServer("s", transport, state, failure).status

    @Test fun theStatesOfAHostedCommandAreWorded() {
        assertEquals(McpStatus.Ready, status("ready"))
        assertEquals(McpStatus.Starting, status("starting"))
        assertEquals(McpStatus.Idle, status("idle"))
    }

    @Test fun aFailedCommandGivesTheReasonOfItsFailureCode() {
        assertEquals(McpStatus.Unavailable(McpReason.START_FAILED, null), status("failed", "start_failed"))
        for (code in listOf("crashed", "stdout_closed", "stdin_closed")) assertEquals(McpStatus.Unavailable(McpReason.STOPPED, null), status("failed", code), code)
        assertEquals(McpStatus.Unavailable(McpReason.MEMORY, null), status("failed", "memory"))
        assertEquals(McpStatus.Unavailable(McpReason.CAPACITY, null), status("failed", "capacity"))
        assertEquals(McpStatus.Unavailable(McpReason.INVALID_SPEC, null), status("failed", "invalid_spec"))
    }

    @Test fun aCodeTheAppDoesNotWordIsShownAsIs() {
        for (code in listOf("write_stall", "non_json", "oversize", "something_new")) assertEquals(McpStatus.Unavailable(null, code), status("failed", code), code)
        // No code at all: the state itself.
        assertEquals(McpStatus.Unavailable(null, "failed"), status("failed"))
        assertEquals(McpStatus.Unavailable(null, "failed"), status("failed", " "))
    }

    @Test fun backoffShowsTheFailureThatCausedItOrElseTooManyRestarts() {
        assertEquals(McpStatus.Unavailable(McpReason.BACKOFF, null), status("backoff"))
        assertEquals(McpStatus.Unavailable(McpReason.BACKOFF, null), status("backoff", "not_a_known_code"))
        assertEquals(status("failed", "crashed"), status("backoff", "crashed"))
    }

    @Test fun anHttpServerAndAServerThatDoesNotSayHaveNoState() {
        assertEquals(McpStatus.Untracked, status(null, transport = McpTransport.HTTP))
        assertEquals(McpStatus.Untracked, status("ready", transport = McpTransport.HTTP))
        assertEquals(McpStatus.Untracked, status(null))
        assertEquals(McpStatus.Untracked, status("", transport = McpTransport.STDIO))
        assertEquals(McpStatus.Untracked, status("ready", transport = McpTransport.UNKNOWN))
    }

    @Test fun aStateTheAppDoesNotKnowIsShownAsIs() {
        assertEquals(McpStatus.Other("draining"), status("draining"))
    }

    @Test fun agentsSeeTheServerWithTheTeamcodePrefix() {
        assertEquals("teamcode-github", mcpServer("github").agentName)
        assertEquals("teamcode-acme_tracker", mcpServer("acme.tracker").agentName, "The agent's name replaces dots, as the CLI does")
    }

    private val accounts = InMemoryAccountRepository(listOf(account()))
    private val features = FakeFeatures(setOf("mcp.gateway.v1"))
    private val repository = FakeMcpServerRepository()
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))

    @Test fun theEntryShowsOnlyOnASignedInAccountOfAServerWithTheGateway() = runTest {
        val availability = McpServersAvailabilityUseCase(accounts, features)
        assertTrue(availability.available("a1"))
        features.features = setOf("cloud.sessions.v1")
        assertFalse(availability.available("a1"))
        assertFalse(availability.available("unknown"))
        assertFalse(McpServersAvailabilityUseCase(InMemoryAccountRepository(listOf(account(signedIn = false))), FakeFeatures(setOf("mcp.gateway.v1"))).available("a1"))
    }

    @Test fun theListIsReadOnceByNameAndRefusedWithoutTheGateway() = runTest {
        val load = LoadMcpServersUseCase(repository, features, guard)
        val servers = load("a1")
        assertEquals(listOf("docs", "filesystem", "github", "playwright", "postgres"), servers.map { it.name })
        assertEquals(listOf("a1"), repository.calls)
        features.features = setOf("cloud.sessions.v1")
        val refused = assertFailsWith<CompanionException.Unsupported> { load("a1") }
        assertEquals("mcp.gateway.v1", refused.feature)
        assertEquals(1, repository.calls.size)
    }
}
