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
    /** The current version of an artifact of the team that is not deleted, or null. */
    suspend fun artifactVersion(teamId: String, artifactId: String): Int?
    /** The status and answered action of a wait of this session, or null when it is not one of the session's. */
    suspend fun waitState(teamId: String, sessionId: String, waitId: String): WaitState?
    /** Whether the session has any open wait (`exists`, on the session index): a stop with a wait open is never held back. */
    suspend fun hasOpenWait(teamId: String, sessionId: String): Boolean
    /** The current versions of these artifacts of the team that are not deleted, in one read. */
    suspend fun artifactVersions(teamId: String, artifactIds: Collection<String>): Map<String, Int> = artifactIds.mapNotNull { id -> artifactVersion(teamId, id)?.let { id to it } }.toMap()
    /** The state of these waits of the session, in one read. */
    suspend fun waitStates(teamId: String, sessionId: String, waitIds: Collection<String>): Map<String, WaitState> = waitIds.mapNotNull { id -> waitState(teamId, sessionId, id)?.let { id to it } }.toMap()
    /** The outcome of each of these drop waits that was decided, in one read. */
    suspend fun dropOutcomes(teamId: String, waitIds: Collection<String>): Map<String, DropOutcome>
}

/** An active list whose session is running: what the server-side reminder looks at. */
data class TodoReminderCandidate(val teamId: String, val sessionId: String, val ownerId: String)

/** The latest turn of a session when it ended normally (`completed`): [seq] is its event's sequence, [startedAt] when it began (epoch milliseconds), null when unknown. */
data class TodoTurnEnd(val seq: Long, val startedAt: Long?)

/** What the server-side reminder reads (docs/todos.md, "The server-side reminder"): bounded, in batches, never one query per session. */
interface TodoReminderStore {
    /** Active lists touched since [since] (epoch milliseconds) whose session is active and meant to run, at most [limit]. */
    suspend fun candidates(since: Long, limit: Int): List<TodoReminderCandidate>
    /** For each of these sessions whose latest turn ended `completed`, that turn; a session whose latest turn is running, interrupted or failed is absent. */
    suspend fun turnEnds(sessionIds: Collection<String>): Map<String, TodoTurnEnd>
    suspend fun member(teamId: String, memberId: String): me.nathanfallet.teamcode.domain.team.Member?
}

/** A wait's `status` (open, resolved, cancelled) and, once answered, the `action` chosen. */
data class WaitState(val status: String, val action: String?)

/** Opens and withdraws the wait that asks a session's owner to agree to drop an item; implemented on the wait store. */
interface TodoDropWaits {
    /** Asks the session's owner; returns the wait's id. Answering `yes` drops the item, anything else keeps it. */
    suspend fun open(actor: Principal, sessionId: String, item: TodoItem, reason: String): String
    suspend fun cancel(actor: Principal, waitId: String)
    /**
     * Asks the owner to unblock a session that stopped three times without progress. With [dropItem], answering `yes` drops that item
     * (the list's drop request names this wait); `no` resumes and `stop` tells the agent to stop. Returns the wait's id.
     */
    suspend fun openBlocked(actor: Principal, sessionId: String, title: String, body: String, dropItem: Boolean): String
}
