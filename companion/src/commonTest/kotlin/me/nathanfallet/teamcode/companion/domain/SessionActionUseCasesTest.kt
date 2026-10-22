package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.test.runTest
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
import me.nathanfallet.teamcode.companion.domain.models.InputReceipt
import me.nathanfallet.teamcode.companion.domain.models.NewSession
import me.nathanfallet.teamcode.companion.domain.models.PendingMessage
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.CreateSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SendMessageUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.reconciled
import kotlin.test.*

class SessionActionUseCasesTest {
    private val sessions = FakeSessionRepository()
    private val features = FakeFeatures()
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), InMemoryAccountRepository(), credentials(), FixedClock()))
    private val send = SendMessageUseCase(sessions, features, guard, SequenceIds())

    @Test fun aFailedMessageIsRetriedWithTheSameIdAndARefusalIsFinal() = runTest {
        val draft = send.draft("Fix the test")
        assertEquals(PendingMessage.State.SENDING, draft.state)
        sessions.sendResult = { throw CompanionException.Unreachable() }
        val failed = send("a1", "s1", draft)
        assertEquals(PendingMessage.State.FAILED, failed.state)
        sessions.sendResult = { InputReceipt("in-1", "queued", woke = true, reason = null) }
        val sent = send("a1", "s1", failed)
        assertEquals(PendingMessage.State.SENT, sent.state); assertEquals("in-1", sent.inputId); assertTrue(sent.woke)
        assertEquals(2, sessions.calls.count { it == "send s1 Fix the test ${draft.clientMessageId}" })
        sessions.sendResult = { throw CompanionException.RateLimited(30) }
        assertEquals(PendingMessage.State.FAILED, send("a1", "s1", send.draft("again")).state)
        sessions.sendResult = { throw CompanionException.Server(503, null) }
        assertEquals(PendingMessage.State.FAILED, send("a1", "s1", send.draft("again")).state)
        sessions.sendResult = { throw CompanionException.Server(409, "session_not_running") }
        val refused = send("a1", "s1", send.draft("late"))
        assertEquals(PendingMessage.State.REFUSED, refused.state); assertEquals("session_not_running", refused.reason)
    }

    @Test fun invalidMessagesAreRefusedBeforeAnyRequestAndAServerWithoutMessagesSaysSo() = runTest {
        for (text in listOf("", "   ", "a".repeat(32_001), "bell\u0007")) assertFailsWith<IllegalArgumentException>(text) { send.draft(text) }
        send.draft("line one\nline two\ttab")
        features.features = setOf("cloud.sessions.v1")
        assertEquals(PendingMessage.State.REFUSED, send("a1", "s1", send.draft("hello")).state)
        assertTrue(sessions.calls.none { it.startsWith("send") })
    }

    @Test fun theTranscriptReplacesAPendingMessageAndAnInputEventRefusesIt() {
        val pending = listOf(
            PendingMessage("c1", "one", PendingMessage.State.SENT, inputId = "i1"),
            PendingMessage("c2", "two", PendingMessage.State.SENT, inputId = "i2"),
            PendingMessage("c3", "three", PendingMessage.State.SENDING),
        )
        val events = listOf(TranscriptEvent.UserMessage(1, 1, "one", "i1", "m1"), TranscriptEvent.InputChanged(2, 2, "i2", "rejected", "delivery_failed"))
        val left = pending.reconciled(events)
        assertEquals(listOf("c2", "c3"), left.map { it.clientMessageId })
        assertEquals(PendingMessage.State.REFUSED, left[0].state); assertEquals("delivery_failed", left[0].reason)
    }

    @Test fun nothingToInterruptIsNotAFailureAndControlsFollowTheRole() = runTest {
        val control = ControlSessionUseCase(sessions, guard, SequenceIds())
        assertTrue(control.interrupt("a1", "s1"))
        sessions.interruptResult = { throw CompanionException.Server(409, "nothing_to_interrupt") }
        assertFalse(control.interrupt("a1", "s1"))
        sessions.interruptResult = { throw CompanionException.Server(409, "session_not_running") }
        assertFailsWith<CompanionException.Server> { control.interrupt("a1", "s1") }
        assertEquals(SessionState.PAUSING, control.pause("a1", "s1").state)
        val mine = cloudSession("s", ownerId = "m1"); val theirs = cloudSession("s", ownerId = "m2")
        assertTrue(ControlSessionUseCase.canControl(account(memberId = "m1"), mine))
        assertFalse(ControlSessionUseCase.canControl(account(memberId = "m1"), theirs))
        assertTrue(ControlSessionUseCase.canControl(account(memberId = "m1").copy(role = "ADMIN"), theirs))
        assertTrue(ControlSessionUseCase.canResume(cloudSession("p", SessionState.PAUSED)))
        assertFalse(ControlSessionUseCase.canMessage(cloudSession("t", SessionState.TERMINATED)))
    }

    @Test fun creationTrimsAndSendsTheFirstMessageOnlyWhenTheServerTakesIt() = runTest {
        features.features += "cloud.sessions.initial-message.v1"
        val create = CreateSessionUseCase(sessions, features, guard)
        var sent: NewSession? = null
        sessions.createResult = { sent = it; cloudSession("new") }
        create("a1", NewSession("  Fix  ", "p1", " ", "high ", "Start here"), "key-1")
        assertEquals(NewSession("Fix", "p1", null, "high", "Start here"), sent)
        assertEquals("create Fix key-1", sessions.calls.last())
        assertFailsWith<IllegalArgumentException> { create("a1", NewSession(" ", "p1", null, null, null), "key-2") }
        assertFailsWith<IllegalArgumentException> { create("a1", NewSession("x".repeat(121), "p1", null, null, null), "key-2") }
        features.features = setOf("cloud.sessions.v1", "cloud.messages.v1")
        assertFailsWith<CompanionException.Unsupported> { create("a1", NewSession("Fix", "p1", null, null, "Start here"), "key-3") }
        assertEquals(1, sessions.calls.count { it.startsWith("create") })
        val (_, takesMessage) = LoadProfilesUseCase(sessions, features, guard)("a1")
        assertFalse(takesMessage)
    }
}
