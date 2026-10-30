package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
import me.nathanfallet.teamcode.companion.SequenceIds
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionFilter
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.SessionWaiting
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.FollowSessionListUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed
import kotlinx.coroutines.cancel
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

    /** The app scope of the shared feed. */
    private val feedScope = kotlinx.coroutines.CoroutineScope(dispatcher + kotlinx.coroutines.SupervisorJob())

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); sessions.changes = feed.receiveAsFlow() }
    @AfterTest fun tearDown() { feedScope.cancel(); Dispatchers.resetMain() }

    private fun viewModel(features: FakeFeatures = FakeFeatures(), shared: SessionFeed = SessionFeed(sessions, features, feedScope)): SessionsViewModel {
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        return SessionsViewModel(accounts, LoadSessionsUseCase(sessions, features, guard), GetSessionUseCase(sessions, guard), FollowSessionListUseCase(shared, features, guard), me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase(sessions, guard, SequenceIds(), features))
    }

    @Test fun twoScreensFollowingTheSameAccountShareOneConnectionAndBothStayCurrent() = runTest(dispatcher) {
        sessions.pages = { _, _ -> SessionPage(listOf(cloudSession("a")), null) }
        val features = FakeFeatures()
        val shared = SessionFeed(sessions, features, feedScope)
        val list = viewModel(features, shared)
        val other = viewModel(features, shared)
        list.start()
        settle()
        feed.send(LiveUpdate.Opened(false))
        settle()
        // The second screen comes once the feed is open: it loads at once, on the same connection.
        other.start()
        settle()
        feed.send(LiveUpdate.Item(SessionChange(1, "cloud.turn", "a", "m1", 9, null, "started")))
        settle()
        assertEquals(1, sessions.calls.count { it.startsWith("feed") })
        assertEquals(2, sessions.calls.count { it.startsWith("list ") })
        assertEquals(SessionState.ACTIVE, list.uiState.value.list!!.sessions.single().state)
        assertEquals(SessionState.ACTIVE, other.uiState.value.list!!.sessions.single().state)
        list.stop(); other.stop()
    }

    @Test fun aSessionWaitingForAPersonIsMarkedUntilItIsAnswered() = runTest(dispatcher) {
        sessions.pages = { _, _ -> SessionPage(listOf(cloudSession("a", SessionState.ACTIVE)), null) }
        val model = viewModel(FakeFeatures(setOf("cloud.sessions.v1", "cloud.events.v1", "cloud.sessions.waiting.v1")))
        model.start()
        settle()
        assertEquals(listOf("feed waiting"), sessions.calls)
        feed.send(LiveUpdate.Opened(false))
        feed.send(LiveUpdate.Item(SessionChange(1, "cloud.session.waiting", "a", "m1", 9, null, null, waitingReason = "question", requestId = "r1")))
        settle()
        assertEquals(SessionWaiting("question", "r1", 9), model.uiState.value.list!!.sessions.single().waiting)
        feed.send(LiveUpdate.Item(SessionChange(2, "cloud.session.answered", "a", "m1", 12, null, null, requestId = "r1")))
        settle()
        assertNull(model.uiState.value.list!!.sessions.single().waiting)
        model.stop()
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
        assertEquals(listOf("feed", "list MINE OPEN null"), sessions.calls)
        model.stop()
    }

    @Test fun aRenameOnTheFeedChangesTheRowInPlaceWhenTheServerAnnouncesIt() = runTest(dispatcher) {
        sessions.pages = { _, _ -> SessionPage(listOf(cloudSession("a", createdAt = 2_000), cloudSession("b", createdAt = 1_000)), null) }
        val model = viewModel(FakeFeatures(setOf("cloud.sessions.v1", "cloud.events.v1", "cloud.sessions.rename.v1")))
        model.start()
        settle()
        assertEquals(listOf("feed renames"), sessions.calls)
        feed.send(LiveUpdate.Opened(false))
        feed.send(LiveUpdate.Item(SessionChange(1, SessionChange.RENAMED, "b", "m1", 9, SessionState.READY, null, name = "Fix the build")))
        settle()
        assertEquals(listOf("a" to "Session a", "b" to "Fix the build"), model.uiState.value.list!!.sessions.map { it.id to it.name })
        assertEquals(1, sessions.calls.count { it.startsWith("list ") }, "A rename is applied from the event, nothing is read again")
        assertTrue(model.uiState.value.renames)
        model.stop()
    }

    @Test fun theRowMenuEndsAndRenamesThroughTheSessionScreensUseCaseAndTellsAFailure() = runTest(dispatcher) {
        sessions.pages = { _, _ -> SessionPage(listOf(cloudSession("a", SessionState.PAUSED)), null) }
        sessions.byId["a"] = cloudSession("a", SessionState.PAUSED)
        val model = viewModel(FakeFeatures(setOf("cloud.sessions.v1", "cloud.events.v1", "cloud.sessions.rename.v1")))
        model.start()
        feed.send(LiveUpdate.Opened(false))
        settle()
        model.rename("a", "  Fix the build ")
        settle()
        assertEquals("Fix the build", model.uiState.value.list!!.sessions.single().name)
        assertTrue(sessions.calls.contains("rename a Fix the build"))
        assertTrue(model.uiState.value.acting.isEmpty())
        // The server's rules are checked before sending: an empty name never leaves the app.
        model.rename("a", "   ")
        settle()
        assertNotNull(model.uiState.value.actionError)
        assertTrue(sessions.calls.none { it == "rename a    " || it == "rename a " })
        model.dismissActionError()
        assertNull(model.uiState.value.actionError)
        sessions.endFailure = CompanionException.Server(409, "session_busy")
        model.end("a")
        settle()
        assertNotNull(model.uiState.value.actionError)
        sessions.endFailure = null
        model.end("a")
        settle()
        assertNull(model.uiState.value.actionError)
        assertEquals(2, sessions.calls.count { it == "end a" })
        // The row is read again after an end, as the session screen reads its header.
        assertTrue(sessions.calls.contains("get a"))
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
        model.setFilter(SessionFilter.ENDED)
        settle()
        assertEquals("list MINE ENDED null", sessions.calls.last())
        assertTrue(model.uiState.value.list!!.sessions.isEmpty())
        model.stop()
        // Stopped: the feed is closed and nothing more is read.
        val before = sessions.calls.size
        feed.send(LiveUpdate.Opened(false))
        settle()
        assertEquals(before, sessions.calls.size)
        assertFalse(model.uiState.value.following)
    }

    @Test fun unsettledRowsAreReadInTurns() = runTest(dispatcher) {
        val creating = (1..7).map { cloudSession("s$it", state = SessionState.CREATING, createdAt = 1_000L - it) }
        creating.forEach { sessions.byId[it.id] = it }
        sessions.pages = { _, _ -> SessionPage(creating, null) }
        val model = viewModel()
        model.start()
        feed.send(LiveUpdate.Opened(false))
        settle()
        advanceTimeBy(15_000); runCurrent()
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), sessions.calls.filter { it.startsWith("get ") }.map { it.removePrefix("get ") })
        sessions.calls.clear()
        advanceTimeBy(15_000); runCurrent()
        // The next round starts where the last one stopped, so the last rows are read too.
        assertEquals(listOf("s6", "s7", "s1", "s2", "s3"), sessions.calls.filter { it.startsWith("get ") }.map { it.removePrefix("get ") })
        model.stop()
    }

    @Test fun aFilterChangeAfterAnErrorStartsTheListAgain() = runTest(dispatcher) {
        var connections = 0
        sessions.changes = flow { if (connections++ == 0) throw IllegalStateException("refused") else emitAll(feed.receiveAsFlow()) }
        val model = viewModel()
        model.start()
        settle()
        assertFalse(model.uiState.value.following)
        assertNotNull(model.uiState.value.error)
        // "Load more" has no loop to read it: it is ignored rather than queued.
        model.loadMore()
        model.setFilter(SessionFilter.ENDED)
        settle()
        assertTrue(model.uiState.value.following)
        feed.send(LiveUpdate.Opened(false))
        settle()
        assertEquals("list MINE ENDED null", sessions.calls.last())
        assertFalse(model.uiState.value.loading)
        model.stop()
    }

    @Test fun aServerWithRemoteSessionsIsAskedForEveryKindAndTheStatusFilterReloadsWithoutReopeningTheFeed() = runTest(dispatcher) {
        val model = viewModel(FakeFeatures(setOf("cloud.sessions.v1", "cloud.events.v1", "cloud.sessions.remote.v1")))
        assertEquals(SessionFilter.OPEN, model.uiState.value.filter)
        model.start()
        feed.send(LiveUpdate.Opened(false))
        settle()
        assertTrue(model.uiState.value.remoteSessions)
        assertEquals(listOf("feed cloud,remote", "list MINE OPEN null cloud,remote"), sessions.calls)
        model.setFilter(SessionFilter.ALL)
        settle()
        assertEquals(listOf("feed cloud,remote", "list MINE OPEN null cloud,remote", "list MINE ALL null cloud,remote"), sessions.calls)
        assertEquals(SessionFilter.ALL, model.uiState.value.filter)
        // The same filter again reads nothing.
        model.setFilter(SessionFilter.ALL)
        settle()
        assertEquals(3, sessions.calls.size)
        model.stop()
    }

    @Test fun anOlderServerIsAskedAsBefore() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        feed.send(LiveUpdate.Opened(false))
        settle()
        assertFalse(model.uiState.value.remoteSessions)
        assertEquals(listOf("feed", "list MINE OPEN null"), sessions.calls)
        model.stop()
    }

    @Test fun loadMoreAsksForOnePageAtATime() = runTest(dispatcher) {
        sessions.pages = { _, before -> if (before == null) SessionPage(listOf(cloudSession("a")), "c1") else SessionPage(listOf(cloudSession("b", createdAt = 500)), "c2") }
        val model = viewModel()
        model.start()
        feed.send(LiveUpdate.Opened(false))
        settle()
        val gate = CompletableDeferred<Unit>().also { sessions.gate = it }
        // The button and the end of the scroll ask together while the page loads.
        model.loadMore(); model.loadMore(); settle(); model.loadMore()
        gate.complete(Unit)
        settle()
        assertEquals(listOf("list MINE OPEN c1"), sessions.calls.filter { it.endsWith(" c1") || it.endsWith(" c2") })
        assertEquals(listOf("a", "b"), model.uiState.value.list!!.sessions.map { it.id })
        model.stop()
    }
}

/** Runs what is due within a second of virtual time; the screens' periodic refreshes never let the scheduler go idle. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }
