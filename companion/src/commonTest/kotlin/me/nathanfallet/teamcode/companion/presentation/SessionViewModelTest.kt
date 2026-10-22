package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.GetSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SendMessageUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.FollowTranscriptUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.LoadTranscriptUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionRoute
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

    @Test fun aSentMessageWaitsUntilTheTranscriptRecordsItAndAFailedOneRetriesWithItsId() = runTest(dispatcher) {
        val model = viewModel()
        model.start(); advanceUntilIdle()
        assertEquals(listOf(4L), follows)
        assertTrue(model.uiState.value.canControl)
        sessions.sendResult = { throw CompanionException.Unreachable() }
        model.onDraftChange("Run the tests"); model.send(); advanceUntilIdle()
        val failed = model.uiState.value.pending.single()
        assertEquals(PendingMessage.State.FAILED, failed.state)
        assertEquals("", model.uiState.value.draft)
        sessions.sendResult = { id -> me.nathanfallet.teamcode.companion.domain.models.InputReceipt("in-$id", "queued", false, null) }
        model.retry(failed.clientMessageId); advanceUntilIdle()
        assertEquals(PendingMessage.State.SENT, model.uiState.value.pending.single().state)
        assertEquals(2, sessions.calls.count { it == "send s1 Run the tests ${failed.clientMessageId}" })
        live.send(LiveUpdate.Item(TranscriptEvent.UserMessage(5, 5, "Run the tests", "in-${failed.clientMessageId}", "m1"))); advanceUntilIdle()
        assertTrue(model.uiState.value.pending.isEmpty())
        model.stop()
    }

    @Test fun pauseShowsTheNewStateAndNothingToInterruptIsANotice() = runTest(dispatcher) {
        val model = viewModel()
        model.start(); advanceUntilIdle()
        model.pause(); advanceUntilIdle()
        assertEquals(me.nathanfallet.teamcode.companion.domain.models.SessionState.PAUSING, model.uiState.value.session?.state)
        sessions.interruptResult = { throw CompanionException.Server(409, "nothing_to_interrupt") }
        model.interrupt(); advanceUntilIdle()
        assertEquals("Nothing is running.", model.uiState.value.notice)
        model.stop()
    }
}
