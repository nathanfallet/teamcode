package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.cloud.CloudEventType
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState
import me.nathanfallet.teamcode.domain.cloud.DesiredState
import me.nathanfallet.teamcode.domain.team.Member
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamEventType
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
class SqlTodoStore(private val database: Database) : TodoStore, TodoReminderStore {
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
        publish(list)
    }

    override suspend fun <T> mutate(teamId: String, sessionId: String, change: (TodoList) -> Pair<TodoList, T>): T = database.query {
        val before = read(teamId, sessionId, lock = true) ?: throw TeamFailure("todo_not_found", 404, "This session has no todo.")
        val (after, value) = change(before)
        write(before, after)
        if (visible(before) != visible(after)) publish(after)
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

    override suspend fun candidates(since: Long, limit: Int): List<TodoReminderCandidate> = database.query {
        // Active lists touched since [since] (index session_todo_active), joined to their session by primary key: only a session that is
        // active and meant to run is looked at.
        (SessionTodos innerJoin CloudSessions).select(SessionTodos.teamId, SessionTodos.sessionId, CloudSessions.ownerId)
            .where { (SessionTodos.status eq TodoListStatus.ACTIVE.wire) and (SessionTodos.updatedAt greaterEq since) and (CloudSessions.state eq CloudSessionState.ACTIVE.wire) and (CloudSessions.desiredState eq DesiredState.RUNNING.wire) }
            .orderBy(SessionTodos.updatedAt, SortOrder.DESC).limit(limit)
            .map { TodoReminderCandidate(it[SessionTodos.teamId], it[SessionTodos.sessionId], it[CloudSessions.ownerId]) }
    }

    override suspend fun turnEnds(sessionIds: Collection<String>): Map<String, TodoTurnEnd> = database.query {
        if (sessionIds.isEmpty()) return@query emptyMap()
        val ids = sessionIds.toList()
        val seq = CloudSessionEvents.seq.max()
        fun latest(below: Map<String, Long>?): Map<String, Long> {
            val condition: Op<Boolean> = if (below == null) CloudSessionEvents.sessionId inList ids
                else below.entries.map { (id, s) -> (CloudSessionEvents.sessionId eq id) and (CloudSessionEvents.seq less s) }.reduce { a, b -> a or b }
            return CloudSessionEvents.select(CloudSessionEvents.sessionId, seq).where { (CloudSessionEvents.type eq CloudEventType.TURN.wire) and condition }
                .groupBy(CloudSessionEvents.sessionId).associate { it[CloudSessionEvents.sessionId] to it[seq]!! }
        }
        fun events(at: Map<String, Long>): Map<String, Pair<String?, Long>> {
            if (at.isEmpty()) return emptyMap()
            val condition = at.entries.map { (id, s) -> (CloudSessionEvents.sessionId eq id) and (CloudSessionEvents.seq eq s) }.reduce { a, b -> a or b }
            return CloudSessionEvents.select(CloudSessionEvents.sessionId, CloudSessionEvents.data, CloudSessionEvents.at).where { condition }.associate {
                it[CloudSessionEvents.sessionId] to (runCatching { Json.parseToJsonElement(it[CloudSessionEvents.data]).jsonObject["phase"]?.jsonPrimitive?.contentOrNull }.getOrNull() to it[CloudSessionEvents.at])
            }
        }
        val last = latest(null)
        val lastEvents = events(last)
        // Only a turn that ended `completed`; the event before it tells when it began.
        val completed = lastEvents.filter { it.value.first == "completed" }.keys
        val before = if (completed.isEmpty()) emptyMap() else events(latest(last.filterKeys { it in completed }))
        completed.associateWith { id -> TodoTurnEnd(last.getValue(id), before[id]?.second ?: lastEvents.getValue(id).second) }
    }

    override suspend fun member(teamId: String, memberId: String): Member? = database.query {
        Members.selectAll().where { (Members.id eq memberId) and (Members.teamId eq teamId) }.singleOrNull()
            ?.let { Member(it[Members.id], it[Members.username], readMemberRole(it), it[Members.disabled], it[Members.mustChangePassword]) }
    }

    override suspend fun hasOpenWait(teamId: String, sessionId: String): Boolean = database.query {
        Waits.select(Waits.id).where { (Waits.sessionId eq sessionId) and (Waits.status eq WaitStatus.OPEN.wire) and (Waits.teamId eq teamId) }.limit(1).any()
    }

    override suspend fun artifactVersions(teamId: String, artifactIds: Collection<String>): Map<String, Int> = database.query {
        if (artifactIds.isEmpty()) return@query emptyMap()
        Artifacts.select(Artifacts.id, Artifacts.currentVersion).where { (Artifacts.id inList artifactIds.toList()) and (Artifacts.teamId eq teamId) and Artifacts.deletedAt.isNull() }.limit(artifactIds.size)
            .associate { it[Artifacts.id] to it[Artifacts.currentVersion] }
    }

    override suspend fun waitStates(teamId: String, sessionId: String, waitIds: Collection<String>): Map<String, WaitState> = database.query {
        if (waitIds.isEmpty()) return@query emptyMap()
        // By primary key; the session is checked on the rows, so the plan never walks the session's waits.
        Waits.select(Waits.id, Waits.sessionId, Waits.status, Waits.answerAction).where { (Waits.id inList waitIds.toList()) and (Waits.teamId eq teamId) }.limit(waitIds.size)
            .filter { it[Waits.sessionId] == sessionId }.associate { it[Waits.id] to WaitState(it[Waits.status], it[Waits.answerAction]) }
    }

    private fun JdbcTransaction.read(teamId: String, sessionId: String, lock: Boolean): TodoList? {
        val query = SessionTodos.selectAll().where { (SessionTodos.sessionId eq sessionId) and (SessionTodos.teamId eq teamId) }
        val row = (if (lock) query.forUpdate() else query).singleOrNull() ?: return null
        val items = SessionTodoItems.selectAll().where { SessionTodoItems.sessionId eq sessionId }.orderBy(SessionTodoItems.position).limit(TodoRules.MAX_ITEMS).map { item(it) }
        val last = row[SessionTodos.lastDone]?.let { TodoProgress(it, row[SessionTodos.lastItems]!!, row[SessionTodos.lastNotes]!!) }
        return TodoList(
            row[SessionTodos.id], sessionId, teamId, row[SessionTodos.reference], TodoJson.refs(row[SessionTodos.refs]),
            TodoListStatus.parse(row[SessionTodos.status]) ?: error("Unknown todo status."), row[SessionTodos.stall], last, items, row[SessionTodos.createdAt], row[SessionTodos.updatedAt], TodoJson.events(row[SessionTodos.hookEvents]), row[SessionTodos.lastHookAt], row[SessionTodos.reminderSeq],
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

    /** What a screen shows of a list: the reminder bookkeeping (`lastHookAt`, `reminderSeq`) is not part of it, so a quiet turn publishes nothing. */
    private fun visible(list: TodoList) = list.copy(lastHookAt = null, reminderSeq = null, updatedAt = 0)

    /** `cloud.todo` on the feed, last in the transaction of a visible change (see [publishTeamEvent]); it belongs to the session's owner and carries the counter and the session's kind. */
    private fun JdbcTransaction.publish(list: TodoList) {
        val session = CloudSessions.select(CloudSessions.ownerId, CloudSessions.kind).where { CloudSessions.id eq list.sessionId }.single()
        val counted = list.items.filter { it.status != TodoItemStatus.DROPPED }
        publishTeamEvent(list.teamId, TeamEventType.TODO_UPDATED, list.sessionId, session[CloudSessions.ownerId], buildJsonObject {
            put("sessionId", list.sessionId); put("kind", session[CloudSessions.kind]); put("status", list.status.wire)
            put("done", counted.count { it.status == TodoItemStatus.DONE }); put("total", counted.size)
        }, list.updatedAt)
    }

    /** Writes only what changed: the list row, new items, and the items whose content or place moved. Nothing is deleted. */
    private fun JdbcTransaction.write(before: TodoList, after: TodoList) {
        require(after.items.map { it.id }.containsAll(before.items.map { it.id })) { "A change never removes an item." }
        if (before.copy(items = emptyList()) != after.copy(items = emptyList())) {
            SessionTodos.update({ SessionTodos.sessionId eq after.sessionId }) {
                it[status] = after.status.wire; it[stall] = after.stall; it[lastDone] = after.lastStop?.done; it[lastItems] = after.lastStop?.items
                it[lastNotes] = after.lastStop?.notes; it[updatedAt] = after.updatedAt
                it[hookEvents] = after.hookEvents.takeIf { e -> e.isNotEmpty() }?.let(TodoJson::events)
                it[lastHookAt] = after.lastHookAt; it[reminderSeq] = after.reminderSeq
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
