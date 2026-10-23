package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
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
import me.nathanfallet.teamcode.companion.SequenceIds
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.cloudSession
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.PendingMessage
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.GetSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SendMessageUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.FollowTranscriptUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.LoadTranscriptUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.Transcript
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.LiveState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionUiState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val live = Channel<LiveUpdate<TranscriptEvent>>(Channel.UNLIMITED)
    private val follows = mutableListOf<Long>()
    private val transcripts = object : TranscriptRepository {
        override suspend fun page(accountId: String, sessionId: String, before: Long?) =
            TranscriptPage(listOf(TranscriptEvent.AgentMessage(4, 4, "Hello", null)), nextBefore = 4, lastSeq = 4)
        override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> { follows += after; return live.receiveAsFlow() }
    }
    private val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1") }

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): SessionViewModel {
        val features = FakeFeatures()
        val accounts = InMemoryAccountRepository(listOf(account("a1")))
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        return SessionViewModel(
            SessionRoute("a1", "s1"), GetSessionUseCase(sessions, guard), LoadTranscriptUseCase(transcripts, features, guard), FollowTranscriptUseCase(transcripts, guard),
            SendMessageUseCase(sessions, features, guard, SequenceIds()), ControlSessionUseCase(sessions, guard, SequenceIds()), accounts,
        )
    }

    @Test fun aWorkingSessionWhoseTurnStartedBeforeTheLoadedPageIsRunning() {
        val noTurn = Transcript(listOf(TranscriptEvent.Status(5, 0, "claude", "opus", null)))
        assertTrue(SessionUiState(session = cloudSession("s", state = SessionState.ACTIVE), transcript = noTurn).running)
        assertFalse(SessionUiState(session = cloudSession("s", state = SessionState.READY), transcript = noTurn).running)
        val ended = Transcript(listOf(TranscriptEvent.Turn(6, 0, TurnPhase.COMPLETED, 10, 1)))
        assertFalse(SessionUiState(session = cloudSession("s", state = SessionState.ACTIVE), transcript = ended).running)
        assertFalse(SessionUiState(transcript = noTurn).running)
    }

    @Test fun aSentMessageWaitsUntilTheTranscriptRecordsItAndAFailedOneRetriesWithItsId() = runTest(dispatcher) {
        val model = viewModel()
        model.start(); settle()
        assertEquals(listOf(4L), follows)
        assertTrue(model.uiState.value.canControl)
        sessions.sendResult = { throw CompanionException.Unreachable() }
        model.onDraftChange("Run the tests"); model.send(); settle()
        val failed = model.uiState.value.pending.single()
        assertEquals(PendingMessage.State.FAILED, failed.state)
        assertEquals("", model.uiState.value.draft)
        sessions.sendResult = { id -> me.nathanfallet.teamcode.companion.domain.models.InputReceipt("in-$id", "queued", false, null) }
        model.retry(failed.clientMessageId); settle()
        assertEquals(PendingMessage.State.SENT, model.uiState.value.pending.single().state)
        assertEquals(2, sessions.calls.count { it == "send s1 Run the tests ${failed.clientMessageId}" })
        live.send(LiveUpdate.Item(TranscriptEvent.UserMessage(5, 5, "Run the tests", "in-${failed.clientMessageId}", "m1"))); settle()
        assertTrue(model.uiState.value.pending.isEmpty())
        model.stop()
    }

    @Test fun aSignedOutHeaderRefreshKeepsTheScreenAndTheEndedStateStays() = runTest(dispatcher) {
        val model = viewModel()
        model.start(); settle()
        // The header read every 30 seconds meets an expired login: nothing escapes, the screen stays as it was.
        sessions.sessionFailure = CompanionException.Unauthorized()
        advanceTimeBy(31_000); runCurrent()
        assertEquals("s1", model.uiState.value.session?.id)
        assertTrue(sessions.calls.count { it == "get s1" } >= 2)
        // The stream ends while the header cannot be read: the final state stays.
        sessions.sessionFailure = CompanionException.Unreachable()
        live.send(LiveUpdate.Ended); settle()
        assertEquals(LiveState.ENDED, model.uiState.value.live)
        assertNull(model.uiState.value.error)
        model.stop()
    }

    @Test fun pauseShowsTheNewStateAndNothingToInterruptIsANotice() = runTest(dispatcher) {
        val model = viewModel()
        model.start(); settle()
        model.pause(); settle()
        assertEquals(me.nathanfallet.teamcode.companion.domain.models.SessionState.PAUSING, model.uiState.value.session?.state)
        sessions.interruptResult = { throw CompanionException.Server(409, "nothing_to_interrupt") }
        model.interrupt(); settle()
        assertEquals(me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionNotice.NothingRunning, model.uiState.value.notice)
        // A lost answer: tapping again repeats the same interruption; once answered, the next tap is a new one.
        sessions.interruptResult = { throw CompanionException.Unreachable() }
        model.interrupt(); settle()
        sessions.interruptResult = { me.nathanfallet.teamcode.companion.domain.models.InputReceipt("int", "queued", false, null) }
        model.interrupt(); settle()
        model.interrupt(); settle()
        val keys = sessions.calls.filter { it.startsWith("interrupt s1") }.drop(1)
        assertEquals(3, keys.size)
        assertEquals(keys[0], keys[1]); assertNotEquals(keys[1], keys[2])
        // A lost answer, then a new turn: the next tap interrupts that turn, under a new key.
        sessions.interruptResult = { throw CompanionException.Unreachable() }
        model.interrupt(); settle()
        live.send(LiveUpdate.Item(me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent.Turn(50, 50, me.nathanfallet.teamcode.companion.domain.models.TurnPhase.STARTED, null, null))); settle()
        model.interrupt(); settle()
        val later = sessions.calls.filter { it.startsWith("interrupt s1") }.takeLast(2)
        assertNotEquals(later[0], later[1])
        model.stop()
    }
}

/** Runs what is due within a second of virtual time; the screens' periodic refreshes never let the scheduler go idle. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }
