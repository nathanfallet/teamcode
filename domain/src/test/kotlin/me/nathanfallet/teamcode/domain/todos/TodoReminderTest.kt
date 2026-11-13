package me.nathanfallet.teamcode.domain.todos

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.waits.WaitSessions
import kotlin.test.*

/** The server-side reminder: one judgement per turn end, the hook's counter and wait, and nothing lost on a restart or a failed delivery. */
class TodoReminderTest {
    private class Store : TodoStore, TodoReminderStore {
        var list: TodoList? = null
        var openWait = false
        var turns = mutableMapOf<String, TodoTurnEnd>()
        var disabledOwner = false
        var clock = 1_000_000L
        override suspend fun now() = clock
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun executionSession(tokenId: String): String? = null
        override suspend fun sessionOwner(teamId: String, sessionId: String) = "ada".takeIf { sessionId == "s1" }
        override suspend fun find(teamId: String, sessionId: String) = list?.takeIf { it.sessionId == sessionId }
        override suspend fun create(list: TodoList) { this.list = list }
        override suspend fun <T> mutate(teamId: String, sessionId: String, change: (TodoList) -> Pair<TodoList, T>): T { val (next, value) = change(list!!); list = next; return value }
        override suspend fun reset(teamId: String, sessionId: String, actor: Principal, check: (TodoList) -> Unit): TodoList {
            val current = list?.takeIf { it.sessionId == sessionId } ?: throw TeamFailure("todo_not_found", 404, "none")
            check(current); list = null; return current
        }
        override suspend fun artifactVersion(teamId: String, artifactId: String): Int? = null
        override suspend fun waitState(teamId: String, sessionId: String, waitId: String): WaitState? = null
        override suspend fun hasOpenWait(teamId: String, sessionId: String) = openWait
        override suspend fun dropOutcomes(teamId: String, waitIds: Collection<String>) = emptyMap<String, DropOutcome>()
        override suspend fun candidates(since: Long, limit: Int) = listOfNotNull(list?.takeIf { it.status == TodoListStatus.ACTIVE && it.updatedAt >= since }?.let { TodoReminderCandidate("team", it.sessionId, "ada") })
        override suspend fun turnEnds(sessionIds: Collection<String>) = turns.filterKeys { it in sessionIds }
        override suspend fun member(teamId: String, memberId: String) = Member(memberId, memberId, Role.MEMBER, disabledOwner, false)
    }
    private class Drops : TodoDropWaits {
        val blocked = mutableListOf<String>()
        override suspend fun open(actor: Principal, sessionId: String, item: TodoItem, reason: String) = "drop-1"
        override suspend fun cancel(actor: Principal, waitId: String) = Unit
        override suspend fun openBlocked(actor: Principal, sessionId: String, title: String, body: String, dropItem: Boolean): String { blocked += title; return "blocked-1" }
    }
    private class Sessions : WaitSessions {
        val delivered = mutableListOf<Triple<String, String, String>>()
        var fail: Exception? = null
        override suspend fun authorize(actor: Principal, sessionId: String) = Unit
        override suspend fun deliver(actor: Principal, sessionId: String, text: String, clientKey: String) { fail?.let { throw it }; delivered += Triple(sessionId, text, clientKey) }
    }

    private val ada = Principal(Member("ada", "ada", Role.MEMBER, false, false), "token", "team", null)
    private val long = "Do this item completely: what, where, why and what is out of scope, enough for a new agent to do it alone."
    private fun item(id: String) = TodoItemDefinition(id, "Item $id", long, TodoDoneWhen("done", listOf(TodoCheck.PrMerged("acme/x"))))
    private val store = Store(); private val drops = Drops(); private val sessions = Sessions()
    private val service = TodoService(store, drops)
    private fun engine() = TodoReminderEngine(store, service, sessions)
    private fun endTurn(seq: Long, startedAt: Long = store.clock - 500) { store.turns["s1"] = TodoTurnEnd(seq, startedAt) }
    private suspend fun create() { service.create(ada, "s1", "Plan: https://example.com/p (v3)", emptyList(), listOf(item("a"), item("b"))); store.list = store.list!!.copy(updatedAt = store.clock) }

    @Test fun aTurnThatEndedWithOpenItemsIsRemindedWithTheResumeMessageOnce() = runBlocking {
        create(); endTurn(10)
        assertEquals(1, engine().run())
        val (session, text, key) = sessions.delivered.single()
        assertEquals("s1", session)
        assertEquals(TodoMessages.resume(store.list!!), text)
        assertTrue(text.startsWith("[TeamCode todo] 0 of 2 items done. Items remain and no wait is open, so the turn goes on.\nPlan reference:\n  Plan: https://example.com/p (v3)"))
        assertEquals("todo-reminder-${store.list!!.id}-10", key)
        assertEquals(1, store.list!!.stall); assertEquals(10L, store.list!!.reminderSeq)
        // The same turn end is judged once: another pass, or another replica, sends nothing.
        assertEquals(0, engine().run()); assertEquals(0, TodoReminderEngine(store, TodoService(store, drops), sessions).run())
        assertEquals(1, sessions.delivered.size)
    }

    @Test fun aTurnTheHookAlreadyJudgedIsLeftAlone() = runBlocking {
        create(); store.clock = 2_000_000
        // The hook asked at 1 999 900, inside the turn that started at 1 999 500 and ended at 2 000 000.
        service.stop(ada, "s1", TodoStopInput(TodoStopPhase.BEGIN).also { store.clock = 1_999_900 })
        store.clock = 2_000_000; endTurn(10, startedAt = 1_999_500)
        assertEquals(0, engine().run()); assertEquals(emptyList(), sessions.delivered)
        assertEquals(10L, store.list!!.reminderSeq, "the turn is marked judged")
        // A later turn the hook did not see is reminded: it began after the hook's last question.
        store.clock = 2_100_000; endTurn(11, startedAt = 2_050_000)
        assertEquals(1, engine().run()); assertEquals(1, sessions.delivered.size)
    }

    @Test fun aWaitOpenOrNothingOpenOrNoCompletedTurnMeansNoReminder() = runBlocking {
        create(); store.openWait = true; endTurn(10)
        assertEquals(0, engine().run()); assertEquals(emptyList(), sessions.delivered)
        store.openWait = false
        // The turn is still running, or was interrupted or failed: not an end the reminder judges (the store only reports `completed`).
        store.turns.clear()
        assertEquals(0, engine().run())
        // Every item done or dropped: nothing to remind of.
        endTurn(12)
        store.list = store.list!!.copy(items = store.list!!.items.map { it.copy(status = TodoItemStatus.DONE) })
        assertEquals(0, engine().run()); assertEquals(emptyList(), sessions.delivered)
        // A finished list, a disabled owner: nothing either.
        store.list = store.list!!.copy(status = TodoListStatus.DONE); assertEquals(0, engine().run())
    }

    @Test fun theThirdTurnWithoutProgressOpensTheBlockedWaitInsteadOfAMessage() = runBlocking {
        create()
        for (seq in 10L..11L) { endTurn(seq); assertEquals(1, engine().run()) }
        assertEquals(2, store.list!!.stall); assertEquals(2, sessions.delivered.size)
        endTurn(12)
        assertEquals(1, engine().run())
        assertEquals(2, sessions.delivered.size, "no third message: the owner is asked")
        assertEquals(1, drops.blocked.size)
        assertEquals(TodoItemStatus.DROP_REQUESTED, store.list!!.item("a")!!.status)
        assertEquals(0, store.list!!.stall); assertEquals("blocked", store.list!!.hookEvents.last().kind)
    }

    @Test fun progressBetweenTurnsKeepsTheCounterDown() = runBlocking {
        create(); endTurn(10); engine().run(); endTurn(11); engine().run()
        service.note(ada, "s1", "a", "tried the merge queue")
        endTurn(12); engine().run()
        assertEquals(0, store.list!!.stall, "a note is progress"); assertEquals(3, sessions.delivered.size); assertEquals(emptyList(), drops.blocked)
    }

    @Test fun aFailedDeliveryPutsTheCounterBackAndTheNextPassTriesAgain() = runBlocking {
        create(); endTurn(10)
        sessions.fail = IllegalStateException("database blip")
        assertEquals(0, engine().run(), "the engine swallows it and goes on")
        assertEquals(0, store.list!!.stall); assertNull(store.list!!.reminderSeq)
        sessions.fail = null
        assertEquals(1, engine().run()); assertEquals(1, sessions.delivered.size)
    }

    @Test fun aDisabledOwnerIsNeverWrittenAsAndAnOldListIsNotLookedAt() = runBlocking {
        create(); endTurn(10); store.disabledOwner = true
        assertEquals(0, engine().run()); assertEquals(emptyList(), sessions.delivered)
        store.disabledOwner = false
        store.list = store.list!!.copy(updatedAt = store.clock - TodoReminderEngine.ACTIVE_WINDOW_MILLIS - 1)
        assertEquals(0, engine().run())
    }

    @Test fun theStopHookRecordsThatItAskedEvenWhenTheStopPassesWithoutAQuestion() = runBlocking {
        create()
        store.clock = 1_500_000; service.stop(ada, "s1", TodoStopInput(TodoStopPhase.BEGIN))
        assertEquals(1_500_000L, store.list!!.lastHookAt)
        store.openWait = true; store.clock = 1_600_000
        assertEquals("wait_open", service.stop(ada, "s1", TodoStopInput(TodoStopPhase.VERIFY)).why)
        assertEquals(1_600_000L, store.list!!.lastHookAt)
        store.openWait = false; store.clock = 1_700_000
        service.stop(ada, "s1", TodoStopInput(TodoStopPhase.BEGIN, background = true))
        assertEquals(1_700_000L, store.list!!.lastHookAt)
    }

    @Test fun theOpenItemsOfAnActiveListAreWhatAStepMustFinish() = runBlocking {
        assertNull(service.openItems("team", "s1"))
        create()
        assertEquals(listOf("a", "b"), service.openItems("team", "s1")!!.map { it.id })
        store.list = store.list!!.copy(items = store.list!!.items.mapIndexed { i, it -> if (i == 0) it.copy(status = TodoItemStatus.DROPPED) else it })
        assertEquals(listOf("b"), service.openItems("team", "s1")!!.map { it.id })
        store.list = store.list!!.copy(items = store.list!!.items.map { it.copy(status = TodoItemStatus.DONE) })
        assertNull(service.openItems("team", "s1"), "everything closed: the step may deliver")
    }
}
