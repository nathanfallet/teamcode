package me.nathanfallet.teamcode.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AdmissionQueueTest {
    private val actor = Principal(Member("member", "user", Role.MEMBER, false, false), "session", "team")
    private val input = AdmissionInput(10, "claude-test", "anthropic")
    private class Queue(var failure: String? = null) : AdmissionQueueStore {
        var attempts = 0
        var released = false
        val deferred = mutableListOf<Boolean>()
        override suspend fun enqueue(actor: Principal, input: AdmissionInput) = AdmissionTicket("ticket")
        override suspend fun reserveQueued(actor: Principal, ticket: AdmissionTicket, input: AdmissionInput): Reservation {
            attempts++
            failure?.let { throw TeamFailure(it, 429, "Synthetic admission failure") }
            return Reservation("reservation", AccountRecord("account", AccountIdentity("team", "anthropic", "provider", "org"), "test", 1, false, null, 0, AccountState.READY), 10)
        }
        override suspend fun renew(actor: Principal, ticket: AdmissionTicket, blocked: Boolean) { deferred += blocked }
        override suspend fun release(actor: Principal, ticket: AdmissionTicket) { released = true }
    }
    @Test fun memberTurnsInteractivePriorityAndBackgroundAging() {
        val choose = SelectQueueTurnUseCase()
        val heavy = QueueCandidate("a", "heavy", 1_000, Workload.INTERACTIVE, 5)
        val colleague = QueueCandidate("b", "colleague", 2_000, Workload.INTERACTIVE, 4)
        assertEquals("b", choose.select(listOf(heavy, heavy.copy(id = "c"), colleague), 3_000))
        val background = colleague.copy(workload = Workload.BACKGROUND)
        assertEquals("a", choose.select(listOf(heavy, background), 3_000))
        assertEquals("b", choose.select(listOf(heavy, background), 32_000))
        assertEquals("a", choose.select(listOf(heavy.copy(id = "c", createdAt = 2_000), heavy), 3_000))
        assertNull(choose.select(emptyList(), 0))
    }
    @Test fun waitsWithoutLosingPlaceForAnotherTurnAndRechecksBeforeAdmission() = runTest {
        val queue = Queue("queue_turn")
        var checked = 0
        val pending = async { AwaitAdmissionUseCase(queue).reserve(actor, input) { checked++ } }
        runCurrent()
        assertEquals(listOf(false), queue.deferred, "Waiting for a turn must not yield an older member's place to new arrivals")
        queue.failure = null
        advanceTimeBy(AwaitAdmissionUseCase.POLL_MILLIS); runCurrent()
        assertEquals("reservation", pending.await().id)
        assertEquals(2, checked)
        assertFalse(queue.released, "The reservation transaction already consumed the ticket")
    }
    @Test fun longWaitsPollLessOftenAndStayInsideTheLease() {
        val random = kotlin.random.Random(7)
        repeat(4) { assertEquals(AwaitAdmissionUseCase.POLL_MILLIS, AwaitAdmissionUseCase.pollDelay(it, random)) }
        repeat(100) {
            assertTrue(AwaitAdmissionUseCase.pollDelay(4, random) in 800..1200)
            assertTrue(AwaitAdmissionUseCase.pollDelay(20, random) in 1600..2400)
        }
        assertTrue(2_400 * 4 < AwaitAdmissionUseCase.LEASE_MILLIS, "Several late polls fit in one lease")
    }
    @Test fun cancellationAndDeadlineRemovePendingMetadataWithoutDispatching() = runTest {
        val queue = Queue("member_concurrency")
        val pending = launch { AwaitAdmissionUseCase(queue).reserve(actor, input) }
        runCurrent()
        assertEquals(listOf(true), queue.deferred)
        pending.cancelAndJoin()
        assertTrue(queue.released)
        assertEquals(1, queue.attempts)
        val timeout = Queue("queue_turn")
        assertEquals("queue_timeout", assertFailsWith<TeamFailure> { AwaitAdmissionUseCase(timeout).reserve(actor, input) }.code)
        assertTrue(timeout.released)
        assertEquals(AwaitAdmissionUseCase.MAX_WAIT_MILLIS, testScheduler.currentTime)
    }
    @Test fun policyDenialsAreNotRetriedOrTurnedIntoCapacityWaits() = runTest {
        val queue = Queue("member_allowance")
        assertEquals("member_allowance", assertFailsWith<TeamFailure> { AwaitAdmissionUseCase(queue).reserve(actor, input) }.code)
        assertEquals(1, queue.attempts)
        assertTrue(queue.deferred.isEmpty())
        assertTrue(queue.released)
        val changed = Queue()
        assertEquals("model_forbidden", assertFailsWith<TeamFailure> {
            AwaitAdmissionUseCase(changed).reserve(actor, input) { throw TeamFailure("model_forbidden", 403, "Changed policy") }
        }.code)
        assertEquals(0, changed.attempts)
        assertTrue(changed.released)
    }
}
