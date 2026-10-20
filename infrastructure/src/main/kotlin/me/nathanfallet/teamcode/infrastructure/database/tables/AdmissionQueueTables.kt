package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object AdmissionWaiters : Table("admission_waiters") {
    val id = varchar("id", 36)
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE)
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE)
    val poolId = varchar("pool_id", 36)
    val turnPoolId = varchar("turn_pool_id", 36).nullable()
    val provider = varchar("provider", 32)
    val workload = varchar("workload", 16)
    val runId = reference("run_id", AgentRuns.id, onDelete = ReferenceOption.NO_ACTION).nullable()
    val createdAt = long("created_at")
    val readyAt = long("ready_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(id)
    init {
        index("waiter_team_expiry", false, teamId, expiresAt)
        index("waiter_member_expiry", false, memberId, expiresAt)
        index("waiter_scope", false, teamId, poolId, provider, expiresAt)
        index("waiter_turn_scope", false, teamId, turnPoolId, provider, expiresAt)
        index("waiter_run", false, runId, expiresAt)
    }
}
object AdmissionTurns : Table("admission_turns") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE)
    val poolId = varchar("pool_id", 36)
    val provider = varchar("provider", 32)
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE)
    val turn = long("turn")
    override val primaryKey = PrimaryKey(teamId, poolId, provider, memberId)
    init { index("turn_scope", false, teamId, poolId, provider, turn) }
}
