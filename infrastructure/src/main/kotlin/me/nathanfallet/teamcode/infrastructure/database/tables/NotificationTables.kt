package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.*

/** How far each team's journal was turned into notifications. A team without a row starts at its journal's head. */
object NotificationCursors : Table("notification_cursors") {
    val teamId = varchar("team_id", 36)
    val fanoutSeq = long("fanout_seq")
    override val primaryKey = PrimaryKey(teamId)
}

/** One notification for one member on one channel. The text is fixed at fan-out, kept a day, never logged. */
object NotificationDeliveries : Table("notification_deliveries") {
    val id = varchar("id", 36)
    val teamId = varchar("team_id", 36)
    val eventSeq = long("event_seq")
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "notification_deliveries_member")
    val channel = varchar("channel", 16)
    val kind = varchar("kind", 24)
    val title = varchar("title", 120)
    val body = varchar("body", 200)
    val sessionId = varchar("session_id", 36).nullable()
    val waitId = varchar("wait_id", 36).nullable()
    val collapseKey = varchar("collapse_key", 80)
    val state = varchar("state", 16)
    val attempts = integer("attempts")
    val nextAttemptAt = long("next_attempt_at")
    val leaseUntil = long("lease_until")
    val lastError = varchar("last_error", 40).nullable()
    val createdAt = long("created_at")
    val deliveredAt = long("delivered_at").nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        // One notification per event, member and channel, even if a page is fanned out twice.
        uniqueIndex("notification_delivery_event", teamId, eventSeq, memberId, channel)
        // The worker's claim: pending, due, not leased, all checked in the index.
        index("notification_delivery_due", false, state, nextAttemptAt, leaseUntil)
        index("notification_delivery_retention", false, createdAt)
    }
}

/** A member's switch for a kind on a channel; without a row it is on. */
object MemberNotificationSettings : Table("member_notification_settings") {
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "member_notification_settings_member")
    val channel = varchar("channel", 16)
    val kind = varchar("kind", 24)
    val enabled = bool("enabled")
    override val primaryKey = PrimaryKey(memberId, channel, kind)
}
