package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** Grant history is retained; audit subjects link to these immutable identities. */
object BudgetOverrides : Table("budget_overrides") {
    val id = varchar("id", 36)
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION)
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.NO_ACTION)
    val poolId = varchar("pool_id", 36).nullable()
    val dailyUnits = long("daily_units")
    val createdBy = varchar("created_by", 36)
    val reason = varchar("reason", 240)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val replacesId = varchar("replaces_id", 36).nullable()
    val revokedAt = long("revoked_at").nullable()
    val revokedBy = varchar("revoked_by", 36).nullable()
    val revocationReason = varchar("revocation_reason", 240).nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        index("override_member_active", false, teamId, memberId, revokedAt, expiresAt)
        index("override_member_page", false, teamId, memberId, createdAt, id)
    }
}
