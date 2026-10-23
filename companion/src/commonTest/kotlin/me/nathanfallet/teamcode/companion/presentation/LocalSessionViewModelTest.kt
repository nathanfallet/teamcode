package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeSessionRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.SequenceIds
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.PendingMessage
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.services.LocalAvailability
import me.nathanfallet.teamcode.companion.domain.services.LocalHost
import me.nathanfallet.teamcode.companion.domain.services.LocalHostException
import me.nathanfallet.teamcode.companion.domain.services.LocalSessionRequest
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SendMessageUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.StartLocalSessionUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.NewSessionRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.LocalSessionViewModel
import kotlin.test.*

private class FakeLocalHost(var availability: LocalAvailability = LocalAvailability.Ready) : LocalHost {
    val started = mutableListOf<LocalSessionRequest>()
    var picked: String? = "/Users/ada/git/paywall"
    var failure: Exception? = null
    override suspend fun availability(accountId: String) = availability
    override suspend fun pickDirectory() = picked
    override suspend fun start(accountId: String, request: LocalSessionRequest): String {
        failure?.let { throw it }
        started += request
        return "s-${started.size}"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LocalSessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val sessions = FakeSessionRepository()
    private val features = FakeFeatures(FakeFeatures().features + Features.REMOTE_SESSIONS)
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), InMemoryAccountRepository(), credentials(), FixedClock()))
    private fun useCase(host: LocalHost) = StartLocalSessionUseCase(host, features, SendMessageUseCase(sessions, features, guard, SequenceIds()))

    @Test fun theFolderNamesTheSessionAndTheFirstMessageGoesThroughTheServer() = runTest(dispatcher) {
        val host = FakeLocalHost()
        val model = LocalSessionViewModel(NewSessionRoute("cli"), useCase(host), host)
        settle()
        assertEquals(LocalAvailability.Ready, model.uiState.value.availability)
        assertFalse(model.uiState.value.canStart)
        model.chooseDirectory(); settle()
        assertEquals("paywall", model.uiState.value.name)
        // A name the member typed is kept when another folder is chosen.
        model.onName("Paywall redesign")
        host.picked = "C:\\Users\\ada\\other"
        model.chooseDirectory(); settle()
        assertEquals("Paywall redesign", model.uiState.value.name)
        assertEquals("C:\\Users\\ada\\other", model.uiState.value.directory)
        model.onAgent("codex"); model.onModel(" gpt-5 "); model.onMessage("Fix the tests"); model.onBypass(true)
        model.create(); settle()
        val request = host.started.single()
        assertEquals(LocalSessionRequest("C:\\Users\\ada\\other", "codex", "Paywall redesign", "gpt-5", "", true), request)
        // The message is not part of what the CLI is given: it is sent to the created session.
        assertTrue(sessions.calls.any { it.startsWith("send s-1") }, sessions.calls.toString())
        assertEquals("s-1", model.uiState.value.started?.sessionId)
        assertEquals(PendingMessage.State.SENT, model.uiState.value.started?.firstMessage?.state)
        assertFalse(model.uiState.value.messageFailed)
    }

    @Test fun aFirstMessageThatFailsIsSaidAndTheSessionStillOpens() = runTest(dispatcher) {
        val host = FakeLocalHost()
        sessions.sendResult = { throw CompanionException.Unreachable() }
        val model = LocalSessionViewModel(NewSessionRoute("cli"), useCase(host), host)
        settle()
        model.chooseDirectory(); model.onMessage("Hello"); settle()
        model.create(); settle()
        assertEquals("s-1", model.uiState.value.started?.sessionId)
        assertTrue(model.uiState.value.messageFailed)
    }

    @Test fun whatTheCliNeedsIsShownAndCheckedAgain() = runTest(dispatcher) {
        val host = FakeLocalHost(LocalAvailability.CliMissing("curl -fsSL https://example/install.sh | sh"))
        val model = LocalSessionViewModel(NewSessionRoute("cli"), useCase(host), host)
        settle()
        assertIs<LocalAvailability.CliMissing>(model.uiState.value.availability)
        model.chooseDirectory(); settle()
        assertFalse(model.uiState.value.canStart)
        host.availability = LocalAvailability.Ready
        model.check(); settle()
        assertTrue(model.uiState.value.canStart)
    }

    @Test fun aServerWithoutRemoteSessionsIsSaidBeforeTheCliIsLookedFor() = runTest(dispatcher) {
        val host = FakeLocalHost(LocalAvailability.CliMissing("x"))
        val model = LocalSessionViewModel(NewSessionRoute("cli"), StartLocalSessionUseCase(host, FakeFeatures(), SendMessageUseCase(sessions, FakeFeatures(), guard, SequenceIds())), host)
        settle()
        assertEquals(LocalAvailability.ServerUnsupported, model.uiState.value.availability)
    }

    @Test fun theClisRefusalIsShownAsIs() = runTest(dispatcher) {
        val host = FakeLocalHost().apply { failure = LocalHostException("Choose a project directory: /Users/ada itself.") }
        val model = LocalSessionViewModel(NewSessionRoute("cli"), useCase(host), host)
        settle()
        model.chooseDirectory(); settle()
        model.create(); settle()
        assertEquals("Choose a project directory: /Users/ada itself.", model.uiState.value.error)
        assertNull(model.uiState.value.started)
        assertTrue(model.uiState.value.canStart)
    }

    @Test fun theDefaultNameIsTheFoldersOwnName() {
        assertEquals("paywall", StartLocalSessionUseCase.defaultName("/Users/ada/git/paywall/"))
        assertEquals("other", StartLocalSessionUseCase.defaultName("C:\\Users\\ada\\other"))
    }
}

/** Runs what is due within a second of virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }
