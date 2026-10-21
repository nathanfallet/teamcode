package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Read inside the caller's transaction; admission and advice use identical weighted shares. */
internal fun readMemberPolicy(c: JdbcTransaction, member: String): MemberPolicy = MemberPolicies.selectAll().where { MemberPolicies.memberId eq member }
    .singleOrNull()?.let { MemberPolicy(member, it[MemberPolicies.dailyUnits], it[MemberPolicies.maxConcurrent], it[MemberPolicies.poolId], it[MemberPolicies.weight], it[MemberPolicies.allowSharedOverflow]) } ?: MemberPolicy(member)
internal fun readMemberCapacity(c: JdbcTransaction, policy: MemberPolicy, now: Long, teamId: String): MemberCapacity {
    val units = Reservations.units.sum()
    val charged = Reservations.select(units).where { (Reservations.memberId eq policy.memberId) and (Reservations.dayNumber eq now / DAY) }.single()[units] ?: 0L
    val active = Reservations.selectAll().where { (Reservations.memberId eq policy.memberId) and (Reservations.state eq "ACTIVE") and (Reservations.expiresAt greater now) }.count().toInt()
    // In flight now: an upper estimate settled to the real cost when the request ends. An expired lease stays charged, not reserved.
    val reserved = Reservations.select(units).where { (Reservations.memberId eq policy.memberId) and (Reservations.dayNumber eq now / DAY) and (Reservations.state eq "ACTIVE") and (Reservations.expiresAt greater now) }.single()[units] ?: 0L
    val weight = Coalesce(MemberPolicies.weight, intLiteral(1)).sum()
    val totalWeight = (Members leftJoin MemberPolicies).select(weight).where {
        (Members.teamId eq teamId) and (Members.disabled eq false) and (MemberPolicies.poolId eq policy.poolId)
    }.single()[weight]?.toLong() ?: 0L
    val budget = PoolBudgets.select(PoolBudgets.dailyUnits).where { (PoolBudgets.teamId eq teamId) and (PoolBudgets.poolId eq policy.poolId.orEmpty()) }
        .singleOrNull()?.get(PoolBudgets.dailyUnits) ?: 1_000_000L
    val share = if (totalWeight == 0L) 0 else budget * policy.weight / totalWeight
    val allowance = if (policy.dailyUnits == 0L) share else minOf(share, policy.dailyUnits)
    val temporary = readActiveBudgetOverride(teamId, policy, now)
    val effective = temporary?.let { minOf(it.dailyUnits, budget) } ?: allowance
    val queued = AdmissionWaiters.selectAll().where { (AdmissionWaiters.memberId eq policy.memberId) and (AdmissionWaiters.expiresAt greater now) }.count().toInt()
    return MemberCapacity(policy, charged, active, (now / DAY + 1) * DAY, effective, budget, totalWeight, queued, readGroupAllowances(teamId, policy.memberId, now), temporary, allowance, reserved)
}
internal fun poolSpent(team: String, pool: String, day: Long): Long = poolSpends(team, setOf(pool), day)[pool] ?: 0L
private const val DAY = 86_400_000L
