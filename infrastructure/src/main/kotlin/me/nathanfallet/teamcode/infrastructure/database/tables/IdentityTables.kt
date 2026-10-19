package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object Teams : Table("teams") {
    val id = varchar("id", 36)
    val name = varchar("name", 120)
    override val primaryKey = PrimaryKey(id)
}
object Members : Table("members") {
    val id = varchar("id", 36)
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "members_ibfk_1")
    val username = varchar("username", 64)
    val passwordHash = varchar("password_hash", 255)
    val role = varchar("role", 16)
    val disabled = bool("disabled").default(false)
    val mustChangePassword = bool("must_change_password").default(true)
    val failedLogins = integer("failed_logins").default(0)
    val lockedUntil = long("locked_until").default(0)
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex("member_username", teamId, username) }
}
object Tokens : Table("tokens") {
    val id = varchar("id", 36)
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "tokens_ibfk_1").index("token_member")
    val tokenHash = char("token_hash", 64).uniqueIndex()
    val device = varchar("device", 120)
    val serviceScopes = text("service_scopes").nullable()
    val deviceId = reference("device_id", Devices.id, onDelete = ReferenceOption.CASCADE).nullable().index("token_device")
    val createdAt = long("created_at").nullable()
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(id)
}
object AuditEvents : Table("audit_events") {
    val id = long("id").autoIncrement()
    val teamId = varchar("team_id", 36)
    val actorId = varchar("actor_id", 36)
    val action = varchar("action", 64)
    val subjectId = varchar("subject_id", 36)
    val occurredAt = long("occurred_at")
    /** Cloud actions only: the member a delegated action applied to, and the initiating credential's id (never its token). */
    val effectiveMemberId = varchar("effective_member_id", 36).nullable()
    val credentialId = varchar("credential_id", 36).nullable()
    /** Cloud transfers only: the other member concerned (the former owner when a session changed owner). */
    val relatedMemberId = varchar("related_member_id", 36).nullable()
    override val primaryKey = PrimaryKey(id)
    init { index("audit_team_time", false, teamId, occurredAt); index("audit_team_id", false, teamId, id) }
}
object LoginBudget : Table("login_budget") {
    val id = integer("id")
    val windowNumber = long("window_number")
    val attempts = integer("attempts")
    override val primaryKey = PrimaryKey(id)
}
