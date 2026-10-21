package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object Accounts : Table("accounts") {
    val id = varchar("id", 36)
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "accounts_ibfk_1")
    val provider = varchar("provider", 32)
    val providerAccountId = varchar("provider_account_id", 128)
    val organizationId = varchar("organization_id", 128)
    val displayName = varchar("display_name", 120)
    val email = varchar("email", 320).nullable()
    val credentialVersion = long("credential_version")
    val credentialEnvelope = mediumText("credential_envelope")
    val disabled = bool("disabled").default(false)
    val poolId = varchar("pool_id", 36).nullable()
    val priority = integer("priority").default(0)
    val state = varchar("state", 32)
    val cooldownUntil = long("cooldown_until").default(0)
    val refreshPending = bool("refresh_pending").default(false)
    val refreshStartedAt = long("refresh_started_at").default(0)
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex("provider_identity", teamId, provider, providerAccountId, organizationId) }
}
object EnrollmentOperations : Table("enrollment_operations") {
    val teamId = varchar("team_id", 36)
    val operationId = varchar("operation_id", 64)
    val requestHash = char("request_hash", 64)
    val accountId = reference("account_id", Accounts.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "enrollment_operations_ibfk_1")
    val created = bool("created")
    override val primaryKey = PrimaryKey(teamId, operationId)
}
object ScheduledWork : Table("scheduled_work") {
    val name = varchar("name", 100)
    val owner = varchar("owner", 36).nullable()
    val availableAt = long("available_at").default(0)
    val lastStartedAt = long("last_started_at").default(0)
    override val primaryKey = PrimaryKey(name)
}
