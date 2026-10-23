package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeSessionRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.SequenceIds
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.AgentQuestion
import me.nathanfallet.teamcode.companion.domain.models.AgentQuestionOption
import me.nathanfallet.teamcode.companion.domain.models.AnswerOutcome
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.PermissionAnswer
import me.nathanfallet.teamcode.companion.domain.models.QuestionAnswer
import me.nathanfallet.teamcode.companion.domain.models.QuestionChoice
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.AnswerPermissionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.PermissionRules
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.openPermissionRequests
import kotlin.test.*

class PermissionUseCasesTest {
    private val sessions = FakeSessionRepository()
    private val features = FakeFeatures()
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), InMemoryAccountRepository(), credentials(), FixedClock()))
    private val answer = AnswerPermissionUseCase(sessions, features, guard, SequenceIds())

    private fun request(seq: Long, id: String) = TranscriptEvent.PermissionRequest(seq, seq, id, "Bash", false, input = "{}")

    @Test fun aRequestStaysOpenUntilItsAnswerIsConsumedItExpiresItsTurnEndsOrTheAgentRestarts() {
        val events = listOf(
            TranscriptEvent.Turn(1, 1, TurnPhase.STARTED, null, null),
            request(2, "a"), request(3, "b"), request(4, "c"),
            // A rejected answer leaves its request open; a consumed one closes it.
            TranscriptEvent.InputChanged(5, 5, "i1", "rejected", "invalid_answer", requestId = "a"),
            TranscriptEvent.InputChanged(6, 6, "i2", "consumed", null, requestId = "b"),
            TranscriptEvent.PermissionExpired(7, 7, "c"),
        )
        assertEquals(listOf("a"), events.openPermissionRequests().map { it.requestId })
        assertEquals(emptyList(), (events + TranscriptEvent.Turn(8, 8, TurnPhase.INTERRUPTED, null, null)).openPermissionRequests())
        assertEquals(emptyList(), (events + TranscriptEvent.Status(8, 8, "exited", null, 1)).openPermissionRequests())
        // A turn that only started (or a phase this version does not know) leaves it open; requests come oldest first.
        val later = events + TranscriptEvent.Turn(8, 8, TurnPhase.UNKNOWN, null, null) + request(9, "d")
        assertEquals(listOf("a", "d"), later.openPermissionRequests().map { it.requestId })
        // A request without an id cannot be answered.
        assertEquals(emptyList(), listOf(TranscriptEvent.PermissionRequest(1, 1, null, "Bash", false)).openPermissionRequests())
    }

    private val single = AgentQuestion(0, "Red or blue?", null, listOf(AgentQuestionOption("Red", null), AgentQuestionOption("Blue", null)), multiSelect = false)
    private val multi = AgentQuestion(3, "Which sizes?", null, listOf(AgentQuestionOption("S", null), AgentQuestionOption("M", null), AgentQuestionOption("L", null)), multiSelect = true)

    @Test fun answersAreTheApiIndexesOrAText() {
        assertNull(PermissionRules.answer(single, null))
        assertNull(PermissionRules.answer(single, QuestionChoice()))
        assertEquals(QuestionAnswer(0, listOf(1)), PermissionRules.answer(single, QuestionChoice(setOf(1))))
        assertEquals(QuestionAnswer(0, text = "Green"), PermissionRules.answer(single, QuestionChoice(other = true, otherText = " Green ")))
        // "Other" picked but left empty answers nothing.
        assertNull(PermissionRules.answer(single, QuestionChoice(other = true, otherText = "  ")))
        assertNull(PermissionRules.answer(single, QuestionChoice(other = true, otherText = "a".repeat(4_001))))
        // Indexes are the request's own, sorted, whatever order they were picked in.
        assertEquals(QuestionAnswer(3, listOf(0, 2)), PermissionRules.answer(multi, QuestionChoice(setOf(2, 0))))
        assertEquals(QuestionAnswer(3, text = "XL"), PermissionRules.answer(multi, QuestionChoice(other = true, otherText = "XL")))
        // Options and a text together: the API takes one of them, so the labels and the text go as one text, as Claude Code reads it.
        assertEquals(QuestionAnswer(3, text = "S, L, XL"), PermissionRules.answer(multi, QuestionChoice(setOf(2, 0), other = true, otherText = "XL")))
        assertNull(PermissionRules.answer(multi, QuestionChoice()))
        assertNull(PermissionRules.answers(listOf(single, multi), mapOf(0 to QuestionChoice(setOf(0)))))
        assertEquals(
            listOf(QuestionAnswer(0, listOf(0)), QuestionAnswer(3, listOf(1))),
            PermissionRules.answers(listOf(single, multi), mapOf(0 to QuestionChoice(setOf(0)), 3 to QuestionChoice(setOf(1)))),
        )
    }

    @Test fun outcomesTellWhatToDoNext() = runTest {
        val sent = PermissionAnswer("r1", allow = false)
        assertIs<AnswerOutcome.Accepted>(answer("a1", "s1", sent, "k1"))
        assertEquals("answer s1 r1 k1", sessions.calls.last())
        for (failure in listOf(CompanionException.Unreachable(), CompanionException.Server(503, null), CompanionException.Server(408, null), CompanionException.RateLimited(3))) {
            sessions.answerResult = { throw failure }
            assertIs<AnswerOutcome.Retry>(answer("a1", "s1", sent, "k1"), failure.toString())
        }
        sessions.answerResult = { throw CompanionException.Server(409, "permission_request_expired") }
        assertEquals(AnswerOutcome.Closed("permission_request_expired"), answer("a1", "s1", sent, "k1"))
        sessions.answerResult = { throw CompanionException.Server(400, "permission_input_unseen") }
        assertEquals(AnswerOutcome.Invalid("permission_input_unseen"), answer("a1", "s1", sent, "k1"))
        sessions.answerResult = { throw CompanionException.Server(403, "permission_answer_denied") }
        assertEquals("permission_answer_denied", assertIs<AnswerOutcome.Refused>(answer("a1", "s1", sent, "k1")).code)
        // A server without the feature is not asked.
        features.features = setOf("cloud.sessions.v1")
        val calls = sessions.calls.size
        assertIs<AnswerOutcome.Refused>(answer("a1", "s1", sent, "k1"))
        assertEquals(calls, sessions.calls.size)
    }
}
