package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

abstract class DirectiveTable(name: String) : Table(name) {
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "${name}_ibfk_1")
    val id = varchar("id", 36)
    val pace = varchar("pace", 20)
    val maxConcurrent = integer("max_concurrent")
    val minInterval = long("min_interval")
    val message = varchar("message", 1000)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val contextKey = char("context_key", 64, "ascii_bin").nullable()
    val recoveryPace = varchar("recovery_pace", 20).nullable()
    val recoveryStartedAt = long("recovery_started_at").nullable()
    val scopeKey = varchar("scope_key", 64, "ascii_bin").default("")
    // Recent peak of parallel requests in this scope, which sizes a progressive SLOW_DOWN.
    val observedConcurrency = integer("observed_concurrency").nullable()
}
object Directives : DirectiveTable("directives") {
    override val primaryKey = PrimaryKey(memberId)
}
object SessionDirectives : DirectiveTable("session_directives") {
    override val primaryKey = PrimaryKey(memberId, scopeKey)
    init { index("directive_expiry", false, expiresAt) }
}
object DirectiveReceipts : Table("directive_receipts") {
    val directiveId = varchar("directive_id", 36)
    val tokenId = varchar("token_id", 36)
    val stage = varchar("stage", 32)
    val receivedAt = long("received_at")
    override val primaryKey = PrimaryKey(directiveId, tokenId, stage)
}
object DirectiveDeliveries : Table("directive_deliveries") {
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "directive_deliveries_ibfk_1")
    val sessionKey = char("session_key", 64, "ascii_bin")
    val stage = varchar("stage", 32)
    val directiveId = varchar("directive_id", 36)
    val claimId = varchar("claim_id", 36)
    val tokenId = varchar("token_id", 36)
    val claimedUntil = long("claimed_until")
    val emittedAt = long("emitted_at").nullable()
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(memberId, sessionKey, stage)
    init { index("delivery_retention", false, updatedAt) }
}
