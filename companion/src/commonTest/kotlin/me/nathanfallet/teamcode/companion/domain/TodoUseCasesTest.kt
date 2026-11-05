package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeSessionRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionTodo
import me.nathanfallet.teamcode.companion.domain.models.TodoItem
import me.nathanfallet.teamcode.companion.domain.models.TodoItemState
import me.nathanfallet.teamcode.companion.domain.repositories.TodoRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.office.SessionActivity
import me.nathanfallet.teamcode.companion.domain.usecases.office.Steps
import me.nathanfallet.teamcode.companion.domain.usecases.office.withTodo
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed
import me.nathanfallet.teamcode.companion.domain.usecases.todos.FollowTodoUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.todos.ReadTodoUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.todos.TodoShortcuts
import me.nathanfallet.teamcode.companion.domain.usecases.todos.TodoUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun item(id: String, state: TodoItemState, unverified: Boolean = false) =
    TodoItem(id, "Point $id", "", "Done when $id", state, addedLater = false, addedReason = null, proof = null, unverified = unverified, dropReason = null)

private fun todo(vararg items: TodoItem) = SessionTodo("s1", "Plan", null, "active", items.toList(), 1)

class TodoUseCasesTest {
    private class FakeTodos : TodoRepository {
        var current: SessionTodo? = null
        var failure: Exception? = null
        val reads = mutableListOf<String>()
        override suspend fun todo(accountId: String, sessionId: String): SessionTodo? { reads += sessionId; failure?.let { throw it }; return current }
    }

    private fun guard() = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), InMemoryAccountRepository(), credentials(), FixedClock()))
    private fun change(seq: Long, session: String) = SessionChange(seq, SessionChange.TODO, session, "m1", seq, null, null, todoDone = 1, todoTotal = 2)

    @Test fun theCounterCountsWhatIsStillToDoAndNeverTheDroppedItems() {
        val list = todo(item("a", TodoItemState.DONE), item("b", TodoItemState.DOING), item("c", TodoItemState.DROPPED), item("d", TodoItemState.TODO))
        assertEquals(1, list.done); assertEquals(3, list.total)
        assertEquals("b", list.current?.id)
    }

    @Test fun aListIsFlaggedWhenMoreThanAThirdOfItsItemsRestOnTheAgentsWord() {
        assertTrue(todo(item("a", TodoItemState.DONE, true), item("b", TodoItemState.DONE, true), item("c", TodoItemState.TODO)).mostlyAttested)
        assertEquals(false, todo(item("a", TodoItemState.DONE, true), item("b", TodoItemState.DONE), item("c", TodoItemState.TODO), item("d", TodoItemState.TODO)).mostlyAttested)
    }

    @Test fun theListLoadsWhenTheFeedOpensAfterAGapAndAtItsSessionsTodoEventsOnly() = runTest {
        val todos = FakeTodos().also { it.current = todo(item("a", TodoItemState.TODO)) }
        val sessions = FakeSessionRepository(); val features = FakeFeatures().also { it.features += setOf("todos.v1", "todos.live.v1") }
        sessions.changes = flowOf(
            LiveUpdate.Opened(false), LiveUpdate.Item(change(1, "s1")), LiveUpdate.Item(change(2, "other")),
            LiveUpdate.Item(SessionChange(3, "cloud.turn", "s1", "m1", 3, null, "started")), LiveUpdate.Reconnecting(2_000), LiveUpdate.Opened(true), LiveUpdate.Gap,
        )
        val updates = FollowTodoUseCase(todos, SessionFeed(sessions, features, backgroundScope), features, guard())("a1", "s1").toList()
        // The feed opens (a load), the session's own event (a load), the connection drops (the list on screen is kept), a gap (a load).
        assertEquals(listOf(false, false, true, false), updates.map { it.reconnecting })
        assertEquals(listOf("s1", "s1", "s1"), todos.reads)
        assertTrue(sessions.calls.any { it.startsWith("feed") && it.endsWith(" todos") }, sessions.calls.toString())
    }

    @Test fun aServerWithoutTheLiveFeedIsReadOnceAndOneWithoutTodosIsNotReadAtAll() = runTest {
        val todos = FakeTodos().also { it.current = todo(item("a", TodoItemState.TODO)) }
        val features = FakeFeatures().also { it.features = setOf("todos.v1") }
        val updates = FollowTodoUseCase(todos, SessionFeed(FakeSessionRepository(), features, backgroundScope), features, guard())("a1", "s1").toList()
        assertEquals(1, updates.size); assertEquals(1, todos.reads.size)
        val none = FakeFeatures().also { it.features = setOf("cloud.events.v1") }
        assertEquals(emptyList(), FollowTodoUseCase(todos, SessionFeed(FakeSessionRepository(), none, backgroundScope), none, guard())("a1", "s1").toList())
        assertEquals(1, todos.reads.size)
        assertNull(ReadTodoUseCase(todos, none, guard())("a1", "s1"))
    }

    @Test fun aListThatCannotBeReadLeavesTheScreenAsItIsAndNeverFailsTheConversation() = runTest {
        val todos = FakeTodos().also { it.failure = CompanionException.Unreachable() }
        val features = FakeFeatures().also { it.features = setOf("todos.v1") }
        assertEquals(emptyList<TodoUpdate>(), FollowTodoUseCase(todos, SessionFeed(FakeSessionRepository(), features, backgroundScope), features, guard())("a1", "s1").toList())
    }

    @Test fun theOfficeCardReadsTheTodoInsteadOfTheTranscriptsTodoWriteSteps() {
        val activity = SessionActivity(steps = Steps(1, 3, "from the transcript"))
        val list = todo(item("a", TodoItemState.DONE), item("b", TodoItemState.DOING), item("c", TodoItemState.TODO))
        assertEquals(Steps(1, 3, "Point b"), activity.withTodo(list).steps)
        assertEquals(activity, activity.withTodo(null))
        assertEquals(activity, activity.withTodo(todo()))
    }

    @Test fun theShortcutsAreOrdinaryMessagesAskingForTodoAddAndAnAddedItemKeepsTheMembersWords() {
        assertTrue("todo_add" in TodoShortcuts.VERIFY)
        assertEquals("Add to your todo: Update the changelog. Write it as a complete item (name, description, done-when) with todo_add.", TodoShortcuts.add("  Update the changelog  "))
    }
}
