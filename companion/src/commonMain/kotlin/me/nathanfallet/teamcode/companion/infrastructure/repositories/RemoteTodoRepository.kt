package me.nathanfallet.teamcode.companion.infrastructure.repositories

import me.nathanfallet.teamcode.api.models.TodoItemView
import me.nathanfallet.teamcode.api.models.TodoView
import me.nathanfallet.teamcode.companion.domain.models.SessionTodo
import me.nathanfallet.teamcode.companion.domain.models.TodoHookEvent
import me.nathanfallet.teamcode.companion.domain.models.TodoItem
import me.nathanfallet.teamcode.companion.domain.models.TodoItemState
import me.nathanfallet.teamcode.companion.domain.models.TodoProof
import me.nathanfallet.teamcode.companion.domain.repositories.TodoRepository

/** The session todo over the typed client (`GET /api/v1/todos/session?sessionId=`), one request per call. */
class RemoteTodoRepository(private val calls: AccountCalls) : TodoRepository {
    override suspend fun todo(accountId: String, sessionId: String): SessionTodo? =
        calls.withAccount(accountId) { it.sessionTodo(sessionId).todo?.domain() }
}

internal fun TodoView.domain() = SessionTodo(
    sessionId = sessionId, reference = reference, artifactId = refs.firstOrNull { it.kind == "artifact" }?.id, status = status,
    items = items.map { it.domain() }, updatedAt = updatedAt, hookEvents = hookEvents.map { TodoHookEvent(it.at, it.kind, it.text) },
)

internal fun TodoItemView.domain() = TodoItem(
    id = id, name = name, description = description, doneWhen = doneWhen.text, state = TodoItemState.of(status),
    addedLater = origin == "added", addedReason = originReason,
    proof = proof?.let { TodoProof(it.at, it.passed, it.text, it.urls) }, unverified = unverified, dropReason = dropReason,
)
