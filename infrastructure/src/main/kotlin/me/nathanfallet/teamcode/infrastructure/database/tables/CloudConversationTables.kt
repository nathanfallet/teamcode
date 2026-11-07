package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** The normalized transcript: one row per event, numbered per session. `data` is bounded and masked JSON. */
object CloudSessionEvents : Table("cloud_session_events") {
    val sessionId = reference("session_id", CloudSessions.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_session_events_session")
    val seq = long("seq")
    val teamId = varchar("team_id", 36)
    /** The execution that produced the event; 0 for events the server writes. */
    val epoch = integer("epoch")
    /** The runner's own counter within [epoch]; null for server events. Unique per epoch, so a retried batch is skipped. */
    val runnerSeq = long("runner_seq").nullable()
    val type = varchar("type", 32)
    val at = long("at")
    val data = text("data")
    /**
     * The `tool.call` id of the subagent that produced the event (its `parentToolUseId`); empty for the main agent and for
     * events written before feeds. Never null, so the main feed is an equality like any other and the index below serves it
     * (the optimizer reads `IS NULL` plus a range through the primary key and filters instead).
     */
    val feed = varchar("feed", 128).default("")
    override val primaryKey = PrimaryKey(sessionId, seq)
    init {
        uniqueIndex("cloud_event_runner_seq", sessionId, epoch, runnerSeq)
        /** One feed's events in order: equality on the feed (or `IS NULL` for the main agent), then the sequence, so a page reads only its own rows. */
        index("cloud_event_feed", false, sessionId, feed, seq)
        index("cloud_event_retention", false, teamId, at)
        /** Events of one type in order: a permission request, and what closed it, without reading the tool events between. */
        index("cloud_event_type", false, sessionId, type, seq)
    }
}

/**
 * The subagents of a session, kept as their events arrive so their start and state never depend on how far back a client
 * read. [id] is the `tool.call` that started the agent; [parentId] the feed it was started from. [state] `running`,
 * `completed` or `failed`; [taskId] is set while the runner reports the agent as a live background task, and keeps a
 * background agent `running` after the call that launched it returned. [resultError] records that the call's result was an error, for when the live task ends.
 */
object CloudSessionSubagents : Table("cloud_session_subagents") {
    val sessionId = reference("session_id", CloudSessions.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_session_subagents_session")
    val id = varchar("id", 128)
    val type = varchar("type", 64).nullable()
    val description = varchar("description", 200)
    val state = varchar("state", 16)
    val startedAt = long("started_at")
    val endedAt = long("ended_at").nullable()
    val steps = integer("steps")
    val parentId = varchar("parent_id", 128).nullable()
    val taskId = varchar("task_id", 64).nullable()
    val resultError = bool("result_error")
    override val primaryKey = PrimaryKey(sessionId, id)
    init {
        /** The newest agents of a session, and the ones still running, in start order. */
        index("cloud_subagent_started", false, sessionId, startedAt)
    }
}

/**
 * Per-session transcript cursors, kept apart from the events so the retention purge never resets them: the last
 * event sequence, and the runner sequence accepted in the latest execution (used to deduplicate retried batches).
 */
object CloudSessionTranscripts : Table("cloud_session_transcripts") {
    val sessionId = reference("session_id", CloudSessions.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_session_transcripts_session")
    val lastSeq = long("last_seq")
    val runnerEpoch = integer("runner_epoch")
    val runnerSeq = long("runner_seq")
    override val primaryKey = PrimaryKey(sessionId)
}

/** Messages and interrupts with their acknowledgement. Attribution is kept on the row; the text is never logged. */
object CloudSessionInputs : Table("cloud_session_inputs") {
    val id = varchar("id", 36)
    val sessionId = reference("session_id", CloudSessions.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_session_inputs_session")
    val teamId = varchar("team_id", 36)
    val kind = varchar("kind", 16)
    val status = varchar("status", 16)
    val authorId = varchar("author_id", 36)
    val credentialId = varchar("credential_id", 36)
    val text = mediumText("text").nullable()
    val wake = bool("wake")
    /** Waiting to wake a session that was pausing when the message arrived. */
    val wakePending = bool("wake_pending").default(false)
    val clientKey = varchar("client_key", 128).nullable()
    val requestDigest = varchar("request_digest", 64).nullable()
    val createdAt = long("created_at")
    val deliveredAt = long("delivered_at").nullable()
    val deliveredEpoch = integer("delivered_epoch").nullable()
    val attempts = integer("attempts").default(0)
    val consumedAt = long("consumed_at").nullable()
    val finishedAt = long("finished_at").nullable()
    val reason = varchar("reason", 64).nullable()
    val eventSeq = long("event_seq").nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("cloud_input_client_key", sessionId, authorId, clientKey)
        index("cloud_input_session_status", false, sessionId, status, createdAt)
        index("cloud_input_status", false, status, createdAt)
        index("cloud_input_wake", false, wakePending)
        index("cloud_input_retention", false, teamId, finishedAt)
    }
}

/** CLI attachment leases. */
object CloudSessionAttachments : Table("cloud_session_attachments") {
    val id = varchar("id", 36)
    val sessionId = reference("session_id", CloudSessions.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_session_attachments_session")
    val teamId = varchar("team_id", 36)
    val memberId = varchar("member_id", 36)
    val credentialId = varchar("credential_id", 36)
    val permissions = varchar("permissions", 64)
    val createdAt = long("created_at")
    val leaseUntil = long("lease_until")
    override val primaryKey = PrimaryKey(id)
    init { index("cloud_attachment_session", false, sessionId, leaseUntil) }
}

/** The team's cloud access policy; a team without a row uses the documented default. */
object CloudAccessPolicies : Table("cloud_access_policies") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_access_policies_team")
    val version = integer("version")
    /** JSON list of `{subject, permissions, scope}`. */
    val rules = text("rules")
    val updatedAt = long("updated_at")
    val updatedBy = varchar("updated_by", 36)
    override val primaryKey = PrimaryKey(teamId)
}
