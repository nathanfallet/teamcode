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
import me.nathanfallet.teamcode.companion.domain.models.AgentQuestion
import me.nathanfallet.teamcode.companion.domain.models.AgentQuestionOption
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.PermissionAnswer
import me.nathanfallet.teamcode.companion.domain.models.QuestionAnswer
import me.nathanfallet.teamcode.companion.domain.models.QuestionChoice
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.PendingMessage
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.AnswerPermissionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.GetSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SendMessageUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.FollowTranscriptUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.LoadTranscriptUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.Transcript
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AnswerState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.LiveState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionNotice
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionUiState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val live = Channel<LiveUpdate<TranscriptEvent>>(Channel.UNLIMITED)
    private val follows = mutableListOf<Long>()
    /** The newest page the screen loads first. */
    private var firstPage = TranscriptPage(listOf(TranscriptEvent.AgentMessage(4, 4, "Hello", null)), nextBefore = 4, lastSeq = 4)
    private val transcripts = object : TranscriptRepository {
        override suspend fun page(accountId: String, sessionId: String, before: Long?) = firstPage
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
            AnswerPermissionUseCase(sessions, features, guard, SequenceIds()),
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

    @Test fun endingTheSessionAsksTheServerThenReadsTheHeaderAndARefusalIsANotice() = runTest(dispatcher) {
        val model = viewModel()
        model.start(); settle()
        sessions.byId["s1"] = sessions.byId["s1"]!!.copy(state = me.nathanfallet.teamcode.companion.domain.models.SessionState.TERMINATED)
        model.end(); settle()
        assertEquals(1, sessions.calls.count { it == "end s1" })
        assertEquals(me.nathanfallet.teamcode.companion.domain.models.SessionState.TERMINATED, model.uiState.value.session?.state)
        assertFalse(model.uiState.value.acting)
        sessions.endFailure = CompanionException.Server(403, "forbidden")
        model.end(); settle()
        assertTrue(model.uiState.value.notice is me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionNotice.Text)
        model.stop()
    }

    // ---- Permission requests -------------------------------------------------------------------------------------

    private fun options(vararg labels: String) = labels.map { AgentQuestionOption(it, "About $it") }
    private val colour = AgentQuestion(0, "Red or blue?", "Colour", options("Red", "Blue"), multiSelect = false)
    private val sizes = AgentQuestion(1, "Which sizes?", null, options("S", "M", "L"), multiSelect = true)
    private val name = AgentQuestion(2, "Its name?", null, options("Ada"), multiSelect = false)
    private fun askEvent(seq: Long, id: String = "r1", questions: List<AgentQuestion> = listOf(colour, sizes, name)) =
        TranscriptEvent.PermissionRequest(seq, seq, id, "AskUserQuestion", true, questions, input = "{}")
    private fun toolEvent(seq: Long, id: String, leftOut: Boolean = false) =
        TranscriptEvent.PermissionRequest(seq, seq, id, "Bash", false, input = if (leftOut) null else "{\"command\": \"rm -rf build\"}", inputLeftOut = leftOut, inputPreview = if (leftOut) "{\"command\":" else null)

    @Test fun aQuestionEventInTheTranscriptYieldsAnAnswerableCardNotOnlyASystemLine() = runTest(dispatcher) {
        // Before, a question in the history was only "The agent asks a question": nothing to answer it with.
        firstPage = TranscriptPage(listOf(TranscriptEvent.Turn(3, 3, TurnPhase.STARTED, null, null), askEvent(4)), nextBefore = null, lastSeq = 4)
        val model = viewModel()
        model.start(); settle()
        val card = assertNotNull(model.uiState.value.permission)
        assertEquals("r1", card.requestId); assertTrue(card.answerable); assertEquals(colour, card.current)
        assertEquals(0, card.waiting)
        model.stop()
    }

    @Test fun questionsShowOneAtATimeBackKeepsTheDraftAndSendPostsEveryAnswerOnce() = runTest(dispatcher) {
        val model = viewModel()
        model.start(); settle()
        live.send(LiveUpdate.Item(askEvent(5))); settle()
        // Nothing chosen: no next question.
        model.nextQuestion("r1")
        assertEquals(colour, model.uiState.value.permission?.current)
        model.selectOption("r1", 0, 1); model.nextQuestion("r1")
        assertEquals(sizes, model.uiState.value.permission?.current)
        model.selectOption("r1", 1, 2); model.selectOption("r1", 1, 0)
        // Back keeps both drafts.
        model.previousQuestion("r1")
        val back = assertNotNull(model.uiState.value.permission)
        assertEquals(colour, back.current); assertEquals(setOf(1), back.draft.choices[0]?.options)
        model.nextQuestion("r1")
        assertEquals(setOf(0, 2), model.uiState.value.permission?.draft?.choices?.get(1)?.options)
        model.nextQuestion("r1")
        val last = assertNotNull(model.uiState.value.permission)
        assertTrue(last.last); assertEquals(name, last.current)
        // "Other" is exclusive in a single choice: typing picks it over the option.
        model.selectOption("r1", 2, 0); model.onOtherText("r1", 2, "  Grace  ")
        assertEquals(QuestionChoice(emptySet(), other = true, otherText = "  Grace  "), model.uiState.value.permission?.draft?.choices?.get(2))
        model.sendAnswers("r1"); settle()
        val (answer, _) = sessions.answers.single()
        assertEquals(PermissionAnswer("r1", allow = true, answers = listOf(QuestionAnswer(0, listOf(1)), QuestionAnswer(1, listOf(0, 2)), QuestionAnswer(2, text = "Grace"))), answer)
        assertEquals(AnswerState.SENT, model.uiState.value.permission?.draft?.state)
        // Once sent, nothing changes it.
        model.selectOption("r1", 2, 0)
        assertEquals(AnswerState.SENT, model.uiState.value.permission?.draft?.state)
        // The agent took it: the card goes.
        live.send(LiveUpdate.Item(TranscriptEvent.InputChanged(6, 6, "ans-r1", "consumed", null, requestId = "r1"))); settle()
        assertNull(model.uiState.value.permission)
        assertTrue(model.uiState.value.drafts.isEmpty())
        model.stop()
    }

    @Test fun aFailedAnswerIsRetriedWithTheSameKeyAndAnEditedOneGetsANewKey() = runTest(dispatcher) {
        val model = viewModel()
        model.start(); settle()
        live.send(LiveUpdate.Item(askEvent(5, questions = listOf(colour)))); settle()
        model.selectOption("r1", 0, 0)
        sessions.answerResult = { throw CompanionException.Unreachable() }
        model.sendAnswers("r1"); settle()
        assertEquals(AnswerState.FAILED, model.uiState.value.permission?.draft?.state)
        sessions.answerResult = { throw CompanionException.Server(503, null) }
        model.retryAnswer("r1"); settle()
        sessions.answerResult = { throw CompanionException.RateLimited(5) }
        model.retryAnswer("r1"); settle()
        assertEquals(AnswerState.FAILED, model.uiState.value.permission?.draft?.state)
        // An edit is another answer: it goes under a new key.
        model.selectOption("r1", 0, 1)
        assertEquals(AnswerState.EDITING, model.uiState.value.permission?.draft?.state)
        sessions.answerResult = { me.nathanfallet.teamcode.companion.domain.models.InputReceipt("ans", "queued", false, null) }
        model.sendAnswers("r1"); settle()
        val keys = sessions.answers.map { it.second }
        assertEquals(4, keys.size)
        assertEquals(1, keys.take(3).toSet().size)
        assertNotEquals(keys[0], keys[3])
        assertEquals(listOf(0, 0, 0, 1), sessions.answers.map { it.first.answers.single().options.single() })
        assertEquals(AnswerState.SENT, model.uiState.value.permission?.draft?.state)
        model.stop()
    }

    @Test fun aConflictClosesTheCardWithANoticeAndAnInvalidAnswerKeepsTheDraft() = runTest(dispatcher) {
        val model = viewModel()
        model.start(); settle()
        live.send(LiveUpdate.Item(askEvent(5, questions = listOf(colour)))); settle()
        live.send(LiveUpdate.Item(askEvent(6, id = "r2", questions = listOf(sizes)))); settle()
        assertEquals(1, model.uiState.value.permission?.waiting)
        model.selectOption("r1", 0, 0)
        sessions.answerResult = { throw CompanionException.Server(400, "invalid_permission_answer") }
        model.sendAnswers("r1"); settle()
        val kept = assertNotNull(model.uiState.value.permission)
        assertEquals("r1", kept.requestId); assertEquals(AnswerState.EDITING, kept.draft.state); assertEquals(setOf(0), kept.draft.choices[0]?.options)
        assertEquals(SessionNotice.PermissionInvalid("invalid_permission_answer"), model.uiState.value.notice)
        sessions.answerResult = { throw CompanionException.Server(409, "permission_request_answered") }
        model.sendAnswers("r1"); settle()
        assertEquals(SessionNotice.PermissionClosed("permission_request_answered"), model.uiState.value.notice)
        // The next open request is shown.
        assertEquals("r2", model.uiState.value.permission?.requestId)
        assertNotEquals(sessions.answers[0].second, sessions.answers[1].second)
        model.stop()
    }

    @Test fun declineDeniesAQuestionAndAToolUseIsAllowedOrDeniedOnlyWhenItsInputWasShown() = runTest(dispatcher) {
        val model = viewModel()
        model.start(); settle()
        live.send(LiveUpdate.Item(askEvent(5))); settle()
        model.selectOption("r1", 0, 0); model.nextQuestion("r1")
        model.deny("r1"); settle()
        assertEquals(PermissionAnswer("r1", allow = false), sessions.answers.last().first)
        live.send(LiveUpdate.Item(TranscriptEvent.InputChanged(6, 6, "ans-r1", "consumed", null, requestId = "r1")))
        live.send(LiveUpdate.Item(toolEvent(7, "t1"))); settle()
        val tool = assertNotNull(model.uiState.value.permission)
        assertTrue(tool.answerable)
        // Allowed only by the owner or an administrator: anyone else is told so and may still deny.
        sessions.answerResult = { throw CompanionException.Server(403, "permission_answer_denied") }
        model.allowTool("t1"); settle()
        assertEquals(SessionNotice.PermissionDenied, model.uiState.value.notice)
        assertEquals(PermissionAnswer("t1", allow = true), sessions.answers.last().first)
        sessions.answerResult = { me.nathanfallet.teamcode.companion.domain.models.InputReceipt("ans", "queued", false, null) }
        live.send(LiveUpdate.Item(TranscriptEvent.Turn(8, 8, TurnPhase.COMPLETED, null, null)))
        live.send(LiveUpdate.Item(toolEvent(9, "t2", leftOut = true))); settle()
        val leftOut = assertNotNull(model.uiState.value.permission)
        assertEquals("t2", leftOut.requestId); assertFalse(leftOut.answerable)
        val before = sessions.answers.size
        model.allowTool("t2"); settle()
        assertEquals(before, sessions.answers.size)
        model.deny("t2"); settle()
        assertEquals(PermissionAnswer("t2", allow = false), sessions.answers.last().first)
        model.stop()
    }

    @Test fun anAcceptedAnswerTheAgentRejectedCanBeSentAgain() = runTest(dispatcher) {
        val model = viewModel()
        model.start(); settle()
        live.send(LiveUpdate.Item(askEvent(5, questions = listOf(colour)))); settle()
        model.selectOption("r1", 0, 0); model.sendAnswers("r1"); settle()
        live.send(LiveUpdate.Item(TranscriptEvent.InputChanged(6, 6, "ans-r1", "rejected", "agent_unavailable", requestId = "r1"))); settle()
        val card = assertNotNull(model.uiState.value.permission)
        assertEquals(AnswerState.EDITING, card.draft.state)
        assertEquals(SessionNotice.PermissionRejected("agent_unavailable"), model.uiState.value.notice)
        model.sendAnswers("r1"); settle()
        assertEquals(2, sessions.answers.size)
        assertNotEquals(sessions.answers[0].second, sessions.answers[1].second)
        model.stop()
    }

    @Test fun aPausingSessionShowsNoRequest() = runTest(dispatcher) {
        firstPage = TranscriptPage(listOf(askEvent(4)), nextBefore = null, lastSeq = 4)
        sessions.byId["s1"] = cloudSession("s1", state = SessionState.PAUSING)
        val model = viewModel()
        model.start(); settle()
        assertNull(model.uiState.value.permission)
        model.stop()
    }
}

/** Runs what is due within a second of virtual time; the screens' periodic refreshes never let the scheduler go idle. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }
