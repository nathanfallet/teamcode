package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** The same team lock serializes grants, revocation, pool changes and inference admission. */
class BudgetOverrideServiceImpl(private val database: Database) : BudgetOverrideService {
    override suspend fun history(actor: Principal, memberId: String, beforeId: String?): BudgetOverridePage = database.query {
        authorize(this, actor, memberId)
        val scope = (BudgetOverrides.teamId eq actor.teamId) and (BudgetOverrides.memberId eq memberId)
        val cursor = beforeId?.let {
            identifier(it)
            BudgetOverrides.select(BudgetOverrides.createdAt, BudgetOverrides.id).where { scope and (BudgetOverrides.id eq it) }.singleOrNull()
                ?: throw TeamFailure("invalid_override_cursor", 400, "Refresh the override history before continuing.")
        }
        val rows = BudgetOverrides.selectAll().where { scope and (cursor?.let {
            (BudgetOverrides.createdAt less it[BudgetOverrides.createdAt]) or
                ((BudgetOverrides.createdAt eq it[BudgetOverrides.createdAt]) and (BudgetOverrides.id less it[BudgetOverrides.id]))
        } ?: Op.TRUE) }.orderBy(BudgetOverrides.createdAt to SortOrder.DESC, BudgetOverrides.id to SortOrder.DESC).limit(PAGE_SIZE + 1).toList()
        val active = readActiveBudgetOverride(actor.teamId, readMemberPolicy(this, memberId), nowMillis())
        BudgetOverridePage(rows.take(PAGE_SIZE).map(::budgetOverrideRecord), active?.id,
            rows.takeIf { it.size > PAGE_SIZE }?.get(PAGE_SIZE - 1)?.get(BudgetOverrides.id))
    }

    override suspend fun grant(actor: Principal, memberId: String, change: BudgetOverrideChange): BudgetOverride = database.query {
        authorize(this, actor, memberId)
        change.replacesId?.let(::identifier)
        val now = nowMillis()
        val expires = CheckBudgetOverrideUseCase().expiresAt(change, now)
        val reason = CheckBudgetOverrideUseCase().reason(change.reason)
        val policy = readMemberPolicy(this, memberId)
        val current = readActiveBudgetOverride(actor.teamId, policy, now)
        if (current?.id != change.replacesId) throw TeamFailure("budget_override_changed", 409, "The active override changed or expired. Refresh before replacing it.")
        if (BudgetOverrides.select(BudgetOverrides.id).where { (BudgetOverrides.teamId eq actor.teamId) and (BudgetOverrides.memberId eq memberId) }.count() >= MAX_MEMBER_HISTORY ||
            BudgetOverrides.select(BudgetOverrides.id).where { BudgetOverrides.teamId eq actor.teamId }.count() >= MAX_TEAM_HISTORY)
            throw TeamFailure("budget_override_limit", 409, "The retained budget override history limit has been reached.")
        val id = UUID.randomUUID().toString()
        current?.let { revokeBudgetOverride(actor, memberId, it.id, now, "Replaced by override $id.", "budget_override.superseded") }
        BudgetOverrides.insert {
            it[BudgetOverrides.id] = id; it[teamId] = actor.teamId; it[BudgetOverrides.memberId] = memberId
            it[poolId] = policy.poolId; it[dailyUnits] = change.dailyUnits; it[createdBy] = actor.member.id
            it[BudgetOverrides.reason] = reason; it[createdAt] = now; it[expiresAt] = expires; it[replacesId] = current?.id
        }
        audit(actor.teamId, actor.member.id, "budget_override.granted", "$memberId/$id")
        budgetOverrideRecord(BudgetOverrides.selectAll().where { BudgetOverrides.id eq id }.single())
    }

    override suspend fun revoke(actor: Principal, memberId: String, id: String, reason: String): BudgetOverride = database.query {
        authorize(this, actor, memberId); identifier(id)
        val sanitized = CheckBudgetOverrideUseCase().reason(reason)
        val row = BudgetOverrides.selectAll().where {
            (BudgetOverrides.teamId eq actor.teamId) and (BudgetOverrides.memberId eq memberId) and (BudgetOverrides.id eq id)
        }.singleOrNull() ?: throw TeamFailure("not_found", 404, "Budget override not found.")
        if (row[BudgetOverrides.revokedAt] != null) return@query budgetOverrideRecord(row)
        val now = nowMillis()
        if (row[BudgetOverrides.expiresAt] <= now) throw TeamFailure("budget_override_expired", 409, "This override already expired.")
        revokeBudgetOverride(actor, memberId, id, now, sanitized, "budget_override.revoked")
        budgetOverrideRecord(BudgetOverrides.selectAll().where { BudgetOverrides.id eq id }.single())
    }

    private fun authorize(transaction: JdbcTransaction, actor: Principal, memberId: String) {
        actor.requireAdmin(); identifier(memberId); transaction.lockTeam(actor.teamId); requireCurrent(transaction, actor)
        if (Members.select(Members.id).where { (Members.teamId eq actor.teamId) and (Members.id eq memberId) }.empty())
            throw TeamFailure("not_found", 404, "Member not found.")
    }
    private fun identifier(id: String) { if (!Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(id)) throw TeamFailure("invalid_identifier", 400, "Use a valid member or override identifier.") }
    companion object { const val PAGE_SIZE = 50; const val MAX_MEMBER_HISTORY = 5000; const val MAX_TEAM_HISTORY = 100000 }
}

/** Called inside admission/controller transactions; expiry uses the database clock without a worker. */
internal fun readActiveBudgetOverride(team: String, policy: MemberPolicy, now: Long): BudgetOverrideSummary? = BudgetOverrides.selectAll().where {
    (BudgetOverrides.teamId eq team) and (BudgetOverrides.memberId eq policy.memberId) and (BudgetOverrides.poolId eq policy.poolId) and
        BudgetOverrides.revokedAt.isNull() and (BudgetOverrides.createdAt lessEq now) and (BudgetOverrides.expiresAt greater now)
}.singleOrNull()?.let { budgetOverrideRecord(it).takeIf { grant -> grant.isActive(now, policy.poolId) }?.summary() }

internal fun JdbcTransaction.revokeBudgetOverride(actor: Principal, member: String, id: String, now: Long, reason: String, event: String) {
    val changed = BudgetOverrides.update({ (BudgetOverrides.id eq id) and (BudgetOverrides.teamId eq actor.teamId) and
        (BudgetOverrides.memberId eq member) and BudgetOverrides.revokedAt.isNull() }) {
        it[revokedAt] = now; it[revokedBy] = actor.member.id; it[revocationReason] = reason
    }
    if (changed == 1) audit(actor.teamId, actor.member.id, event, "$member/$id")
}

internal fun budgetOverrideRecord(row: ResultRow) = with(BudgetOverrides) {
    BudgetOverride(row[id], row[memberId], row[poolId], row[dailyUnits], row[createdBy], row[reason], row[createdAt], row[expiresAt],
        row[replacesId], row[revokedAt], row[revokedBy], row[revocationReason])
}
