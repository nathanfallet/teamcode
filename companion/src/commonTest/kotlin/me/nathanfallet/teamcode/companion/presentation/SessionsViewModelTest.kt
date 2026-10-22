package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeSessionRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.cloudSession
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.FollowSessionListUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.GetSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadSessionsUseCase
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionsViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val sessions = FakeSessionRepository()
    private val feed = Channel<LiveUpdate<SessionChange>>(Channel.UNLIMITED)
    private val accounts = InMemoryAccountRepository(listOf(account("a1")))

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); sessions.changes = feed.receiveAsFlow() }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): SessionsViewModel {
        val features = FakeFeatures()
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        return SessionsViewModel(accounts, LoadSessionsUseCase(sessions, features, guard), GetSessionUseCase(sessions, guard), FollowSessionListUseCase(sessions, features, guard))
    }

    @Test fun nothingIsLoadedBeforeTheFeedOpensAndAChangeDuringTheLoadIsAppliedAfterIt() = runTest(dispatcher) {
        sessions.pages = { _, _ -> SessionPage(listOf(cloudSession("a")), null) }
        val gate = CompletableDeferred<Unit>().also { sessions.gate = it }
        val model = viewModel()
        model.start()
        settle()
        assertEquals(listOf("feed"), sessions.calls)
        feed.send(LiveUpdate.Opened(false))
        feed.send(LiveUpdate.Item(SessionChange(1, "cloud.turn", "a", "m1", 9, null, "started")))
        settle()
        assertTrue(model.uiState.value.loading)
        gate.complete(Unit)
        settle()
        // The answer was read before the turn started: the change applies on top of it.
        assertEquals(SessionState.ACTIVE, model.uiState.value.list!!.sessions.single().state)
        assertEquals(listOf("feed", "list MINE ACTIVE null"), sessions.calls)
        model.stop()
    }

    @Test fun anUnknownSessionIsReadOnceAndAFilterChangeReloads() = runTest(dispatcher) {
        sessions.byId["n"] = cloudSession("n")
        val model = viewModel()
        model.start()
        feed.send(LiveUpdate.Opened(false))
        feed.send(LiveUpdate.Item(SessionChange(1, "cloud.session.created", "n", "m1", 9, SessionState.CREATING, null)))
        settle()
        assertEquals(listOf("n"), model.uiState.value.list!!.sessions.map { it.id })
        assertEquals(1, sessions.calls.count { it == "get n" })
        model.setGroup(SessionGroup.PAUSED)
        settle()
        assertEquals("list MINE PAUSED null", sessions.calls.last())
        assertTrue(model.uiState.value.list!!.sessions.isEmpty())
        model.stop()
        // Stopped: the feed is closed and nothing more is read.
        val before = sessions.calls.size
        feed.send(LiveUpdate.Opened(false))
        settle()
        assertEquals(before, sessions.calls.size)
        assertFalse(model.uiState.value.following)
    }
}

/** Runs what is due within a second of virtual time; the screens' periodic refreshes never let the scheduler go idle. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }
