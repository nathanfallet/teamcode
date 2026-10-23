package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.*

/**
 * Files sent to sessions: metadata only, the bytes are in the team's bucket under `session-files/`. [contentType],
 * [sha256] and [completedAt] are null while the upload runs; [inputId] is the message that carries the file;
 * [deletingSince] marks a file whose bytes the sweep removes (acknowledged, expired or abandoned) before the row.
 */
object CloudSessionFiles : Table("cloud_session_files") {
    val id = varchar("id", 36)
    val teamId = varchar("team_id", 36)
    val sessionId = varchar("session_id", 36)
    val authorId = varchar("author_id", 36)
    val credentialId = varchar("credential_id", 36)
    val name = varchar("name", 255)
    val contentType = varchar("content_type", 64).nullable()
    val size = long("size")
    val sha256 = varchar("sha256", 64).nullable()
    /** The `Idempotency-Key` of the upload, unique per session and author. */
    val clientKey = varchar("client_key", 128).nullable()
    val requestDigest = varchar("request_digest", 64).nullable()
    val inputId = varchar("input_id", 36).nullable()
    val createdAt = long("created_at")
    val completedAt = long("completed_at").nullable()
    val expiresAt = long("expires_at")
    val consumedAt = long("consumed_at").nullable()
    val deletingSince = long("deleting_since").nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("cloud_session_files_key", sessionId, authorId, clientKey)
        index("cloud_session_files_session", false, sessionId, deletingSince)
        index("cloud_session_files_input", false, inputId)
        index("cloud_session_files_sweep", false, deletingSince, expiresAt)
    }
}

/** What each member uploaded per UTC day ([day]: days since the epoch), for the daily limit. */
object CloudFileUsage : Table("cloud_file_usage") {
    val teamId = varchar("team_id", 36)
    val memberId = varchar("member_id", 36)
    val day = long("day")
    val bytes = long("bytes")
    val files = integer("files")
    override val primaryKey = PrimaryKey(teamId, memberId, day)
    init { index("cloud_file_usage_day", false, day) }
}
