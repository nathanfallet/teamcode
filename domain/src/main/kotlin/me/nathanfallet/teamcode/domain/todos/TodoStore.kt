package me.nathanfallet.teamcode.domain.todos

import me.nathanfallet.teamcode.domain.team.Principal

/** What a drop request's wait led to: the owner said yes ([DROP]), said no or the wait was cancelled ([KEEP]); still open is absent from the map. */
enum class DropOutcome { DROP, KEEP }

/** Persistence port of a session's todo (docs/todos.md). The rules stay in [TodoRules]; the store only makes each change atomic. */
interface TodoStore {
    suspend fun now(): Long
    suspend fun revalidate(actor: Principal)
    /** The cloud session an execution credential (`tcex_`) was issued for, or null for any other credential. */
    suspend fun executionSession(tokenId: String): String?
    /** The owner of a session of the team, or null for an unknown session. */
    suspend fun sessionOwner(teamId: String, sessionId: String): String?
    suspend fun find(teamId: String, sessionId: String): TodoList?
    /** Stores a new list; `409 todo_exists` when the session has one (decided under the session's row lock, so two replicas cannot both create). */
    suspend fun create(list: TodoList)
    /**
     * Reads the session's list under its row lock, applies [change] (which may throw to refuse), and stores the result in the
     * same transaction. [change] returns the new list and a value for the caller; `404 todo_not_found` when there is no list.
     */
    suspend fun <T> mutate(teamId: String, sessionId: String, change: (TodoList) -> Pair<TodoList, T>): T
    /** The outcome of each of these drop waits that was decided, in one read. */
    suspend fun dropOutcomes(teamId: String, waitIds: Collection<String>): Map<String, DropOutcome>
}

/** Opens and withdraws the wait that asks a session's owner to agree to drop an item; implemented on the wait store. */
interface TodoDropWaits {
    /** Asks the session's owner; returns the wait's id. Answering `yes` drops the item, anything else keeps it. */
    suspend fun open(actor: Principal, sessionId: String, item: TodoItem, reason: String): String
    suspend fun cancel(actor: Principal, waitId: String)
}
