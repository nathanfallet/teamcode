package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.*

/**
 * The caller-wide event feed: one gap-free sequence per team. [seq] is allocated under the team's [TeamEventCursors]
 * row lock, so commit order is sequence order and a reader never skips an event that commits late. [ownerId] is the
 * member the subject belongs to when the event happened; visibility is decided from it alone.
 */
object TeamEvents : Table("team_events") {
    val teamId = varchar("team_id", 36)
    val seq = long("seq")
    val type = varchar("type", 40)
    val subjectId = varchar("subject_id", 36)
    val ownerId = varchar("owner_id", 36)
    val at = long("at")
    val data = text("data")
    override val primaryKey = PrimaryKey(teamId, seq)
    init {
        index("team_event_owner", false, teamId, ownerId, seq)
        index("team_event_retention", false, at)
    }
}

/** The last sequence handed out for a team; its row lock serializes publishers of that team. */
object TeamEventCursors : Table("team_event_cursors") {
    val teamId = varchar("team_id", 36)
    val lastSeq = long("last_seq")
    override val primaryKey = PrimaryKey(teamId)
}
