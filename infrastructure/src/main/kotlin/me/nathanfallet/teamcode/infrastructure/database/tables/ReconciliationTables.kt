package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.ReferenceOption

/** Immutable snapshots intentionally outlive detailed reservations and their ordinary retention. */
object UsageReconciliations : Table("usage_reconciliations") {
    val id = varchar("id", 36)
    val teamId = varchar("team_id", 36)
    val memberId = varchar("member_id", 36)
    val accountId = varchar("account_id", 36)
    val poolId = varchar("pool_id", 36)
    val sourcePoolId = varchar("source_pool_id", 36).nullable()
    val runId = varchar("run_id", 36).nullable()
    val dayNumber = long("day_number")
    val providerRequestId = varchar("provider_request_id", 128).nullable()
    val previousState = varchar("previous_state", 16)
    val previousUnits = long("previous_units")
    val actualUnits = long("actual_units")
    val evidenceSource = varchar("evidence_source", 32)
    val reference = varchar("evidence_reference", 240)
    val reason = varchar("reason", 240)
    val actorId = varchar("actor_id", 36)
    val reconciledAt = long("reconciled_at")
    override val primaryKey = PrimaryKey(id)
    init {
        index("reconciliation_history", false, teamId, reconciledAt, id)
        index("reconciliation_member_history", false, teamId, memberId, reconciledAt, id)
    }
}

/** Group identifiers are audit snapshots; current membership and original detail cleanup cannot erase them. */
object ReconciledUsageGroups : Table("reconciled_usage_groups") {
    val reconciliationId = reference("reconciliation_id", UsageReconciliations.id, onDelete = ReferenceOption.CASCADE)
    val groupId = varchar("group_id", 36)
    override val primaryKey = PrimaryKey(reconciliationId, groupId)
}
