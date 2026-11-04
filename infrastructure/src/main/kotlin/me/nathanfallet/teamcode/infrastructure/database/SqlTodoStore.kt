package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.todos.*
import me.nathanfallet.teamcode.domain.waits.WaitStatus
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Session todos in shared persistence. Every change locks the session's todo row, reads the list, applies the rule and writes
 * the difference in one transaction, so two replicas cannot both create a list or both finish an item (docs/todos.md). A list
 * costs two reads (the row, then its items in order); the drop outcomes one more, only while a drop request is open.
 */
class SqlTodoStore(private val database: Database) : TodoStore {
    override suspend fun now(): Long = database.query { nowMillis() }
    override suspend fun revalidate(actor: Principal) = database.query { requireCurrent(this, actor) }
    override suspend fun executionSession(tokenId: String): String? = database.query {
        CloudInferenceCredentials.select(CloudInferenceCredentials.sessionId).where { CloudInferenceCredentials.tokenId eq tokenId }.singleOrNull()?.get(CloudInferenceCredentials.sessionId)
    }
    override suspend fun sessionOwner(teamId: String, sessionId: String): String? = database.query {
        CloudSessions.select(CloudSessions.ownerId).where { (CloudSessions.id eq sessionId) and (CloudSessions.teamId eq teamId) }.singleOrNull()?.get(CloudSessions.ownerId)
    }

    override suspend fun find(teamId: String, sessionId: String): TodoList? = database.query { read(teamId, sessionId, lock = false) }

    override suspend fun create(list: TodoList) = database.query {
        // The session's row serializes creations: of two replicas, the second finds the first one's list.
        CloudSessions.select(CloudSessions.id).where { (CloudSessions.id eq list.sessionId) and (CloudSessions.teamId eq list.teamId) }.forUpdate().singleOrNull()
            ?: throw TeamFailure("not_found", 404, "Session not found.")
        if (SessionTodos.select(SessionTodos.sessionId).where { SessionTodos.sessionId eq list.sessionId }.limit(1).any())
            throw TeamFailure("todo_exists", 409, "This session already has a todo: use todo_add to add an item.")
        SessionTodos.insert {
            it[sessionId] = list.sessionId; it[id] = list.id; it[teamId] = list.teamId; it[reference] = list.reference; it[refs] = TodoJson.refs(list.refs)
            it[status] = list.status.wire; it[stall] = list.stall; it[createdAt] = list.createdAt; it[updatedAt] = list.updatedAt
        }
        list.items.forEachIndexed { index, item -> insertItem(list.sessionId, index, item) }
    }

    override suspend fun <T> mutate(teamId: String, sessionId: String, change: (TodoList) -> Pair<TodoList, T>): T = database.query {
        val before = read(teamId, sessionId, lock = true) ?: throw TeamFailure("todo_not_found", 404, "This session has no todo.")
        val (after, value) = change(before)
        write(before, after)
        value
    }

    override suspend fun dropOutcomes(teamId: String, waitIds: Collection<String>): Map<String, DropOutcome> = database.query {
        if (waitIds.isEmpty()) return@query emptyMap()
        Waits.select(Waits.id, Waits.status, Waits.answerAction).where { (Waits.id inList waitIds.toList()) and (Waits.teamId eq teamId) and (Waits.status neq WaitStatus.OPEN.wire) }.limit(waitIds.size)
            .associate { row ->
                row[Waits.id] to if (row[Waits.status] == WaitStatus.RESOLVED.wire && row[Waits.answerAction] == WaitBackedDropWaits.YES) DropOutcome.DROP else DropOutcome.KEEP
            }
    }

    override suspend fun artifactVersion(teamId: String, artifactId: String): Int? = database.query {
        Artifacts.select(Artifacts.currentVersion).where { (Artifacts.id eq artifactId) and (Artifacts.teamId eq teamId) and Artifacts.deletedAt.isNull() }.singleOrNull()?.get(Artifacts.currentVersion)
    }

    override suspend fun waitState(teamId: String, sessionId: String, waitId: String): WaitState? = database.query {
        Waits.select(Waits.status, Waits.answerAction).where { (Waits.id eq waitId) and (Waits.teamId eq teamId) and (Waits.sessionId eq sessionId) }.singleOrNull()
            ?.let { WaitState(it[Waits.status], it[Waits.answerAction]) }
    }

    private fun JdbcTransaction.read(teamId: String, sessionId: String, lock: Boolean): TodoList? {
        val query = SessionTodos.selectAll().where { (SessionTodos.sessionId eq sessionId) and (SessionTodos.teamId eq teamId) }
        val row = (if (lock) query.forUpdate() else query).singleOrNull() ?: return null
        val items = SessionTodoItems.selectAll().where { SessionTodoItems.sessionId eq sessionId }.orderBy(SessionTodoItems.position).limit(TodoRules.MAX_ITEMS).map { item(it) }
        val last = row[SessionTodos.lastDone]?.let { TodoProgress(it, row[SessionTodos.lastItems]!!, row[SessionTodos.lastNotes]!!) }
        return TodoList(
            row[SessionTodos.id], sessionId, teamId, row[SessionTodos.reference], TodoJson.refs(row[SessionTodos.refs]),
            TodoListStatus.parse(row[SessionTodos.status]) ?: error("Unknown todo status."), row[SessionTodos.stall], last, items, row[SessionTodos.createdAt], row[SessionTodos.updatedAt],
        )
    }

    private fun item(row: ResultRow) = TodoItem(
        row[SessionTodoItems.id], row[SessionTodoItems.name], row[SessionTodoItems.description], TodoJson.doneWhen(row[SessionTodoItems.doneWhen]),
        TodoJson.strings(row[SessionTodoItems.dependsOn]), TodoOrigin.parse(row[SessionTodoItems.origin]) ?: error("Unknown todo origin."), row[SessionTodoItems.originReason],
        TodoItemStatus.parse(row[SessionTodoItems.status]) ?: error("Unknown todo item status."), row[SessionTodoItems.proof]?.let(TodoJson::proof),
        TodoJson.notes(row[SessionTodoItems.notes]), row[SessionTodoItems.dropReason], row[SessionTodoItems.dropWaitId],
        TodoJson.links(row[SessionTodoItems.links]),
    )

    private fun JdbcTransaction.insertItem(sessionId: String, index: Int, item: TodoItem) {
        SessionTodoItems.insert {
            it[SessionTodoItems.sessionId] = sessionId; it[id] = item.id; it[position] = index; fill(it, item)
        }
    }

    private fun fill(it: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>, item: TodoItem) {
        it[SessionTodoItems.name] = item.name; it[SessionTodoItems.description] = item.description; it[SessionTodoItems.doneWhen] = TodoJson.doneWhen(item.doneWhen)
        it[SessionTodoItems.dependsOn] = TodoJson.strings(item.dependsOn); it[SessionTodoItems.origin] = item.origin.wire; it[SessionTodoItems.originReason] = item.originReason
        it[SessionTodoItems.status] = item.status.wire; it[SessionTodoItems.proof] = item.proof?.let(TodoJson::proof); it[SessionTodoItems.notes] = TodoJson.notes(item.notes)
        it[SessionTodoItems.dropReason] = item.dropReason; it[SessionTodoItems.dropWaitId] = item.dropWaitId
        it[SessionTodoItems.links] = item.links.takeIf { it.isNotEmpty() }?.let(TodoJson::links)
    }

    /** Writes only what changed: the list row, new items, and the items whose content or place moved. Nothing is deleted. */
    private fun JdbcTransaction.write(before: TodoList, after: TodoList) {
        require(after.items.map { it.id }.containsAll(before.items.map { it.id })) { "A change never removes an item." }
        if (before.copy(items = emptyList()) != after.copy(items = emptyList())) {
            SessionTodos.update({ SessionTodos.sessionId eq after.sessionId }) {
                it[status] = after.status.wire; it[stall] = after.stall; it[lastDone] = after.lastStop?.done; it[lastItems] = after.lastStop?.items
                it[lastNotes] = after.lastStop?.notes; it[updatedAt] = after.updatedAt
            }
        }
        val old = before.items.withIndex().associate { it.value.id to it }
        after.items.forEachIndexed { index, item ->
            val previous = old[item.id]
            when {
                previous == null -> insertItem(after.sessionId, index, item)
                previous.value != item || previous.index != index -> SessionTodoItems.update({ (SessionTodoItems.sessionId eq after.sessionId) and (SessionTodoItems.id eq item.id) }) {
                    it[position] = index; fill(it, item)
                }
            }
        }
    }
}
