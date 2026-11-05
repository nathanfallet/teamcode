package me.nathanfallet.teamcode.companion.domain.repositories

import me.nathanfallet.teamcode.companion.domain.models.SessionTodo

/** The todo of sessions (`todos.v1`). Failures are [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s. */
interface TodoRepository {
    /** The session's todo, or null while the agent has not created one. */
    suspend fun todo(accountId: String, sessionId: String): SessionTodo?
}
