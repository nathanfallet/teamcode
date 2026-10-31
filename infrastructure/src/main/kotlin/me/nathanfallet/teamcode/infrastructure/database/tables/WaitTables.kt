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
    /** Waits that resolve by themselves: the instant of a `time` wait, or the timeout of an `event` wait. Null for a person's. */
    val dueAt = long("due_at").nullable()
    val eventSource = varchar("event_source", 16).nullable()
    val onTimeout = varchar("on_timeout", 16).nullable()
    val resumeMessage = text("resume_message").nullable()
    /** SHA-256 (hex) of an event wait's secret: the secret itself is shown once and never kept. */
    val eventTokenHash = varchar("event_token_hash", 64).nullable()
    /** Set when the server resolved the wait and still owes its session the wake message; cleared once queued. */
    val wakePending = bool("wake_pending").default(false)
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
        // The same with the resolver, which every list now filters on (human by default): equality columns first, so a member's
        // timers are read without walking their people's waits, and the default list reads only its own.
        index("wait_assignee_resolver", false, assigneeId, teamId, status, resolver, createdAt, id)
        // The due scan: open waits whose instant has come, oldest first. Only waits for a time or an event have a due_at, so the
        // range alone selects them; the planner reads exactly the due entries.
        index("wait_due", false, status, dueAt)
        // The wake messages still owed after a resolution: a handful of rows, none in the normal case.
        index("wait_wake", false, wakePending, resolvedAt)
    }
}

/**
 * The secrets an answered wait gave its session (docs/waits.md#secrets), one row per variable name and session: a later answer
 * with the same name replaces it. [value] is sealed with the server's credential cipher and bound to the team, session and name,
 * so a copied row does not decrypt. The row ends with its session or its wait (cascade), at [expiresAt] (purged by the worker) or,
 * for a one-time secret, when it is served. [ownerId] is who owned the session at the answer: a session handed over to someone
 * else does not serve it to the new owner.
 */
object WaitSecretTable : Table("wait_secrets") {
    val sessionId = reference("session_id", CloudSessions.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "wait_secrets_session")
    val envName = varchar("env_name", 80)
    val waitId = reference("wait_id", Waits.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "wait_secrets_wait")
    val teamId = varchar("team_id", 36)
    val fieldId = varchar("field_id", 64)
    val ownerId = varchar("owner_id", 36)
    val oneTime = bool("one_time")
    val value = text("value")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(sessionId, envName)
    init {
        // The runner's read is a prefix of the primary key (session_id): a session holds a handful of rows, and the optimizer
        // keeps the primary key even when a (session_id, expires_at) index exists, so that index was measured and dropped.
        // The purge: the secrets whose lifetime ended, oldest first.
        index("wait_secret_expiry", false, expiresAt)
    }
}
