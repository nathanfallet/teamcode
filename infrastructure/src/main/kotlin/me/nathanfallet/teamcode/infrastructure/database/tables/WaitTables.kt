package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.*

/**
 * One row per stored wait (docs/waits.md). [fieldDefs], [actions] and [refs] are JSON; [answerValues] is the JSON object of
 * the answer. A wait ends with its session (the rows cascade). [idempotencyKey] is unique per asker: a retried creation
 * returns the first wait. A session's open permission request is not a row here: the session store holds it.
 */
object Waits : Table("waits") {
    val id = varchar("id", 36)
    val teamId = varchar("team_id", 36)
    val sessionId = reference("session_id", CloudSessions.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "waits_session")
    val kind = varchar("kind", 16)
    val resolver = varchar("resolver", 8)
    val status = varchar("status", 16)
    val assigneeId = reference("assignee_id", Members.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "waits_assignee")
    val createdById = varchar("created_by_id", 36).nullable()
    val title = varchar("title", 200)
    val body = mediumText("body").nullable()
    val refs = text("refs")
    val fieldDefs = mediumText("field_defs")
    val actions = text("actions")
    val answerAction = varchar("answer_action", 64).nullable()
    val answerValues = mediumText("answer_values").nullable()
    val answerAuthorId = varchar("answer_author_id", 36).nullable()
    val idempotencyKey = varchar("idempotency_key", 128).nullable()
    val createdAt = long("created_at")
    val resolvedAt = long("resolved_at").nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        // A member's waits by status, newest first: equality columns, then the sort. It leads with the assignee so the
        // foreign key uses it: an index of its own would be preferred by the planner, and has no order.
        index("wait_assignee", false, assigneeId, teamId, status, createdAt, id)
        // The administrators' view of the whole team.
        index("wait_team", false, teamId, status, createdAt, id)
        // The same without a status filter ("all"): the sort needs no extra step.
        index("wait_assignee_all", false, assigneeId, teamId, createdAt, id)
        index("wait_team_all", false, teamId, createdAt, id)
        // The cap of open waits per session.
        index("wait_session", false, sessionId, status)
        uniqueIndex("wait_idempotency", createdById, idempotencyKey)
    }
}
