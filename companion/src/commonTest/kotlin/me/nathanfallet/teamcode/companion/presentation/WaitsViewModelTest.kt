package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeSessionRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.FieldInput
import me.nathanfallet.teamcode.companion.domain.models.FieldType
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.Wait
import me.nathanfallet.teamcode.companion.domain.models.WaitAction
import me.nathanfallet.teamcode.companion.domain.models.WaitAnswer
import me.nathanfallet.teamcode.companion.domain.models.WaitField
import me.nathanfallet.teamcode.companion.domain.models.WaitKind
import me.nathanfallet.teamcode.companion.domain.models.WaitPage
import me.nathanfallet.teamcode.companion.domain.models.WaitSource
import me.nathanfallet.teamcode.companion.domain.models.WaitStatus
import me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed
import me.nathanfallet.teamcode.companion.domain.usecases.waits.AnswerWaitUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.FollowWaitsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.LoadWaitsUseCase
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WaitFilter
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WaitsViewModel
import kotlin.test.*

/** The waits of the member: the open list and its badge kept live by the feed, the filters, the pages and the answers. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaitsViewModelTest {
    private class Waits : WaitRepository {
        val calls = mutableListOf<String>()
        /** The waits by status, newest first; a page is [pageSize] long and its cursor is the id of its last wait. */
        val byStatus = mutableMapOf<WaitStatus, List<Wait>>()
        var pageSize = 50
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun list(accountId: String, status: WaitStatus, before: String?): WaitPage {
            calls += "list ${status.wire} ${before.orEmpty()}".trim()
            gate?.await()
            failure?.let { throw it }
            val all = byStatus[status].orEmpty()
            val rest = if (before == null) all else all.dropWhile { it.id != before }.drop(1)
            val page = rest.take(pageSize)
            return WaitPage(page, if (rest.size > page.size) page.last().id else null)
        }
        override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>): Wait {
            calls += "answer $waitId $action"
            val wait = byStatus.getValue(WaitStatus.OPEN).first { it.id == waitId }
            byStatus[WaitStatus.OPEN] = byStatus.getValue(WaitStatus.OPEN) - wait
            val done = wait.copy(open = false, answer = WaitAnswer(action, values))
            byStatus[WaitStatus.RESOLVED] = listOf(done) + byStatus[WaitStatus.RESOLVED].orEmpty()
            return done
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val feed = Channel<LiveUpdate<SessionChange>>(Channel.UNLIMITED)
    private val feedScope = kotlinx.coroutines.CoroutineScope(dispatcher + kotlinx.coroutines.SupervisorJob())
    private val sessions = FakeSessionRepository()
    private val waits = Waits()
    private val features = FakeFeatures().also { it.features += "waits.v1" }
    private val accounts = InMemoryAccountRepository(listOf(account("a1")))

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); sessions.changes = feed.receiveAsFlow() }
    @AfterTest fun tearDown() { feedScope.cancel(); Dispatchers.resetMain() }

    private fun viewModel(): WaitsViewModel {
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        return WaitsViewModel(accounts, LoadWaitsUseCase(waits, features, guard), AnswerWaitUseCase(waits, guard), FollowWaitsUseCase(SessionFeed(sessions, features, feedScope), features, guard))
    }

    private fun wait(id: String, open: Boolean = true, cancelled: Boolean = false, session: String = "Fix the build", source: WaitSource = WaitSource.WAIT) = Wait(
        id, source, WaitKind.HUMAN_REQUEST, open, "s-$id", session, null, "m1", "Review $id", null, emptyList(),
        listOf(WaitField("feedback", FieldType.TEXT, "Feedback")), listOf(WaitAction("approve", "Approve"), WaitAction("request_changes", "Request changes", requires = listOf("feedback"))),
        createdAt = 100, cancelled = cancelled,
    )

    private fun event(type: String, member: String = "m1", seq: Long = 1) = SessionChange(seq, type, "s1", member, seq, null, null, waitId = "w1")

    private suspend fun TestScope.opened(model: WaitsViewModel) {
        model.start(); settle()
        feed.send(LiveUpdate.Opened(false)); settle()
    }

    @Test fun theOpenWaitsLoadWhenTheFeedOpensAndTheBadgeCountsThem() = runTest(dispatcher) {
        waits.byStatus[WaitStatus.OPEN] = listOf(wait("w2"), wait("w1"))
        val model = viewModel()
        model.start(); settle()
        // Nothing is read before the feed is open, so nothing is missed between the load and the stream.
        assertEquals(emptyList(), waits.calls)
        feed.send(LiveUpdate.Opened(false)); settle()
        assertEquals(listOf("list open"), waits.calls)
        val state = model.uiState.value
        assertTrue(state.available && state.loaded)
        assertEquals(2, state.openCount)
        assertFalse(state.moreOpen)
        model.stop()
    }

    @Test fun theBadgeFollowsTheFeedEventsOfThisMembersWaitsOnly() = runTest(dispatcher) {
        val model = viewModel()
        opened(model)
        assertEquals(0, model.uiState.value.openCount)

        waits.byStatus[WaitStatus.OPEN] = listOf(wait("w1"))
        feed.send(LiveUpdate.Item(event(SessionChange.WAIT_CREATED))); settle()
        assertEquals(1, model.uiState.value.openCount)

        // Another member's wait, and an event that is not about waits, load nothing.
        val before = waits.calls.size
        feed.send(LiveUpdate.Item(event(SessionChange.WAIT_CREATED, member = "m2", seq = 2)))
        feed.send(LiveUpdate.Item(SessionChange(3, "cloud.turn", "s1", "m1", 3, null, "started"))); settle()
        assertEquals(before, waits.calls.size)

        waits.byStatus[WaitStatus.OPEN] = emptyList()
        feed.send(LiveUpdate.Item(event(SessionChange.WAIT_RESOLVED, seq = 4))); settle()
        assertEquals(0, model.uiState.value.openCount)

        // A session asking for a permission or a question is a wait too: its events reload the list.
        waits.byStatus[WaitStatus.OPEN] = listOf(wait("s9", source = WaitSource.SESSION))
        feed.send(LiveUpdate.Item(SessionChange(5, SessionChange.WAITING, "s9", "m1", 5, null, null, waitingReason = "permission", requestId = "r1"))); settle()
        assertEquals(1, model.uiState.value.openCount)
        waits.byStatus[WaitStatus.OPEN] = emptyList()
        feed.send(LiveUpdate.Item(SessionChange(6, SessionChange.ANSWERED, "s9", "m1", 6, null, null, requestId = "r1"))); settle()
        assertEquals(0, model.uiState.value.openCount)
        model.stop()
    }

    @Test fun aGapInTheFeedReloadsAndAWithdrawnWaitLeavesTheBadge() = runTest(dispatcher) {
        waits.byStatus[WaitStatus.OPEN] = listOf(wait("w1"))
        val model = viewModel()
        opened(model)
        assertEquals(1, model.uiState.value.openCount)
        // Events were lost: the list is read again, whatever they were.
        waits.byStatus[WaitStatus.OPEN] = listOf(wait("w3"), wait("w2"), wait("w1"))
        feed.send(LiveUpdate.Reconnecting(1_000)); settle()
        assertTrue(model.uiState.value.reconnecting)
        feed.send(LiveUpdate.Opened(true)); feed.send(LiveUpdate.Gap); settle()
        assertFalse(model.uiState.value.reconnecting)
        assertEquals(listOf("w3", "w2", "w1"), model.uiState.value.waits.map { it.id })
        waits.byStatus[WaitStatus.OPEN] = listOf(wait("w3"), wait("w1"))
        feed.send(LiveUpdate.Item(event(SessionChange.WAIT_CANCELLED))); settle()
        assertEquals(2, model.uiState.value.openCount)
        model.stop()
    }

    @Test fun moreOpenWaitsThanAPageGiveABadgeWithAPlusAndLoadMoreAppendsTheNextPage() = runTest(dispatcher) {
        waits.pageSize = 2
        waits.byStatus[WaitStatus.OPEN] = listOf(wait("w5"), wait("w4"), wait("w3"), wait("w2"), wait("w1"))
        val model = viewModel()
        opened(model)
        assertEquals(2, model.uiState.value.openCount)
        assertTrue(model.uiState.value.moreOpen)
        model.loadMore(); settle()
        assertEquals(listOf("w5", "w4", "w3", "w2"), model.uiState.value.waits.map { it.id })
        assertEquals(listOf("list open", "list open w4"), waits.calls)
        model.loadMore(); settle()
        assertEquals(listOf("w5", "w4", "w3", "w2", "w1"), model.uiState.value.waits.map { it.id })
        assertFalse(model.uiState.value.moreOpen)
        // No cursor left: nothing more is asked.
        model.loadMore(); settle()
        assertEquals(3, waits.calls.size)
        model.stop()
    }

    @Test fun aReloadDuringLoadMoreDoesNotMixTwoPages() = runTest(dispatcher) {
        waits.pageSize = 2
        waits.byStatus[WaitStatus.OPEN] = listOf(wait("w4"), wait("w3"), wait("w2"), wait("w1"))
        val model = viewModel()
        opened(model)
        waits.gate = CompletableDeferred()
        model.loadMore(); settle()
        assertTrue(model.uiState.value.loadingMore)
        // A newer wait arrives meanwhile: the first page is read again before the old second page comes back.
        waits.byStatus[WaitStatus.OPEN] = listOf(wait("w5"), wait("w4"), wait("w3"), wait("w2"), wait("w1"))
        feed.send(LiveUpdate.Item(event(SessionChange.WAIT_CREATED))); settle()
        waits.gate!!.complete(Unit); settle()
        assertEquals(listOf("w5", "w4"), model.uiState.value.waits.map { it.id }.take(2))
        assertEquals(model.uiState.value.waits.map { it.id }.distinct(), model.uiState.value.waits.map { it.id })
        assertFalse(model.uiState.value.loadingMore)
        model.stop()
    }

    @Test fun theAnsweredAndCancelledFiltersReadTheirOwnPagesAndStayCurrent() = runTest(dispatcher) {
        waits.pageSize = 1
        waits.byStatus[WaitStatus.OPEN] = listOf(wait("w3"))
        waits.byStatus[WaitStatus.RESOLVED] = listOf(wait("w2", open = false).copy(answer = WaitAnswer("approve", mapOf("feedback" to JsonPrimitive("ok")))), wait("w1", open = false))
        waits.byStatus[WaitStatus.CANCELLED] = listOf(wait("w0", open = false, cancelled = true))
        val model = viewModel()
        opened(model)
        assertEquals(WaitFilter.OPEN, model.uiState.value.filter)

        model.setFilter(WaitFilter.ANSWERED); settle()
        var state = model.uiState.value
        assertEquals(listOf("w2"), state.shown.map { it.id })
        assertEquals("Approve", state.shown.single().answerActionLabel)
        assertEquals("w2", state.shownNext)
        model.loadMore(); settle()
        assertEquals(listOf("w2", "w1"), model.uiState.value.shown.map { it.id })
        assertNull(model.uiState.value.shownNext)
        assertEquals(listOf("list open", "list resolved", "list resolved w2"), waits.calls)
        // The badge still counts the open waits while another list shows.
        assertEquals(1, model.uiState.value.openCount)

        // A wait answered elsewhere reaches the answered list live.
        waits.byStatus[WaitStatus.RESOLVED] = listOf(wait("w3", open = false)) + waits.byStatus.getValue(WaitStatus.RESOLVED)
        waits.byStatus[WaitStatus.OPEN] = emptyList()
        feed.send(LiveUpdate.Item(event(SessionChange.WAIT_RESOLVED))); settle()
        state = model.uiState.value
        assertEquals(listOf("w3"), state.shown.map { it.id })
        assertEquals(0, state.openCount)

        model.setFilter(WaitFilter.CANCELLED); settle()
        assertTrue(model.uiState.value.shown.single().cancelled)
        model.setFilter(WaitFilter.OPEN); settle()
        assertEquals(emptyList(), model.uiState.value.shown.map { it.id })
        assertTrue(model.uiState.value.shownLoaded)
        model.stop()
    }

    @Test fun aFailedLoadKeepsWhatIsShownAndRetryReadsTheFirstPageAgain() = runTest(dispatcher) {
        waits.byStatus[WaitStatus.OPEN] = listOf(wait("w1"))
        val model = viewModel()
        opened(model)
        waits.failure = CompanionException.Unreachable()
        feed.send(LiveUpdate.Item(event(SessionChange.WAIT_CREATED))); settle()
        assertTrue(model.uiState.value.error)
        assertEquals(1, model.uiState.value.openCount, "what was shown stays")
        waits.failure = null
        model.retry(); settle()
        assertFalse(model.uiState.value.error)
        model.setFilter(WaitFilter.ANSWERED)
        waits.failure = CompanionException.Unreachable(); settle()
        model.retry(); settle()
        assertTrue(model.uiState.value.error)
        assertTrue(model.uiState.value.shown.isEmpty() && !model.uiState.value.shownLoaded)
        model.stop()
    }

    @Test fun aServerWithoutWaitsHidesEverything() = runTest(dispatcher) {
        features.features = setOf("cloud.sessions.v1", "cloud.events.v1")
        val model = viewModel()
        opened(model)
        assertFalse(model.uiState.value.available)
        assertEquals(emptyList(), waits.calls)
        model.stop()
    }

    @Test fun anOpenWaitIsAnsweredInPlaceAndLeavesTheBadge() = runTest(dispatcher) {
        waits.byStatus[WaitStatus.OPEN] = listOf(wait("w2"), wait("w1"))
        val model = viewModel()
        opened(model)
        val first = model.uiState.value.waits.first()
        model.answer(first, "request_changes"); settle()
        assertNotNull(model.uiState.value.drafts["w2"]?.problem, "the action needs the feedback")
        model.input("w2", "feedback", FieldInput.Text("Split step 2"))
        model.answer(first, "request_changes"); settle()
        assertEquals(listOf("w1"), model.uiState.value.waits.map { it.id })
        assertEquals(1, model.uiState.value.openCount)
        assertNull(model.uiState.value.drafts["w2"])
        model.stop()
    }

    @Test fun stoppingClosesTheFollowAndStartingReadsAgain() = runTest(dispatcher) {
        waits.byStatus[WaitStatus.OPEN] = listOf(wait("w1"))
        val model = viewModel()
        opened(model)
        model.stop(); settle()
        waits.byStatus[WaitStatus.OPEN] = listOf(wait("w2"), wait("w1"))
        feed.send(LiveUpdate.Item(event(SessionChange.WAIT_CREATED))); settle()
        assertEquals(1, model.uiState.value.openCount, "not followed while stopped")
        model.start(); settle()
        feed.send(LiveUpdate.Opened(false)); settle()
        assertEquals(2, model.uiState.value.openCount)
        model.stop()
    }
}

private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }
