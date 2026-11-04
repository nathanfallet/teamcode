package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.*

/**
 * A session's todo (docs/todos.md): one row per session, which is also the lock every change takes. [refs] is JSON. The
 * three `last_*` counters are the progress snapshot of the last blocked stop. The row ends with its session (cascade).
 */
object SessionTodos : Table("session_todos") {
    val sessionId = reference("session_id", CloudSessions.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "session_todos_session")
    val id = varchar("id", 36)
    val teamId = varchar("team_id", 36)
    val reference = text("reference")
    val refs = text("refs")
    val status = varchar("status", 16)
    val stall = integer("stall")
    val lastDone = integer("last_done").nullable()
    val lastItems = integer("last_items").nullable()
    val lastNotes = integer("last_notes").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(sessionId)
    init {
        // The marker of a PR names a list by the first 8 characters of its id.
        uniqueIndex("session_todo_id", id)
    }
}

/**
 * One item of a todo. [doneWhen], [dependsOn], [proof] and [notes] are JSON; [position] orders the list. Items are never deleted
 * except with their list (cascade): an abandoned item stays, as `dropped`.
 */
object SessionTodoItems : Table("session_todo_items") {
    val sessionId = reference("session_id", SessionTodos.sessionId, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "session_todo_items_todo")
    val id = varchar("id", 40)
    val position = integer("position")
    val name = varchar("name", 80)
    val description = text("description")
    val doneWhen = mediumText("done_when")
    val dependsOn = text("depends_on")
    val origin = varchar("origin", 16)
    val originReason = text("origin_reason").nullable()
    val status = varchar("status", 16)
    val proof = text("proof").nullable()
    val notes = mediumText("notes")
    val dropReason = text("drop_reason").nullable()
    val dropWaitId = varchar("drop_wait_id", 36).nullable()
    /** JSON: what the item is bound to (a PR, a release, an artifact, a wait). Null before the first link. */
    val links = mediumText("links").nullable()
    override val primaryKey = PrimaryKey(sessionId, id)
    init {
        // The list is read whole, in order: equality on the session, then the sort.
        index("session_todo_item_position", false, sessionId, position)
    }
}
