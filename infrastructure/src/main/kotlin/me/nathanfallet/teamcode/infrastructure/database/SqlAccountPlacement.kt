package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.accounts.AccountRecord
import me.nathanfallet.teamcode.domain.accounts.AccountState
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Both logical home charges and physical lending count, once each within a pool's limit. */
internal fun poolSpends(team: String, pools: Set<String>, day: Long): Map<String, Long> {
    if (pools.isEmpty()) return emptyMap()
    val units = Reservations.units.sum()
    val logical = Reservations.select(Reservations.poolId, units).where {
        (Reservations.teamId eq team) and (Reservations.dayNumber eq day) and (Reservations.poolId inList pools)
    }.groupBy(Reservations.poolId).associate { it[Reservations.poolId] to (it[units] ?: 0L) }
    val borrowed = Reservations.select(Reservations.sourcePoolId, units).where {
        (Reservations.teamId eq team) and (Reservations.dayNumber eq day) and (Reservations.sourcePoolId inList pools) and
            (Reservations.sourcePoolId neq Reservations.poolId)
    }.groupBy(Reservations.sourcePoolId).associate { it[Reservations.sourcePoolId] to (it[units] ?: 0L) }
    return pools.associateWith { (logical[it] ?: 0L) + (borrowed[it] ?: 0L) }
}

/** A shared reserve is never promised to a primary member, including one currently disabled. */
internal fun sharedOverflowBudgets(team: String): Map<String, Long> = PoolBudgets.selectAll().where {
    (PoolBudgets.teamId eq team) and (PoolBudgets.sharedOverflow eq true) and (PoolBudgets.poolId neq "") and
        notExists((MemberPolicies innerJoin Members).select(MemberPolicies.memberId).where {
            (Members.teamId eq team) and (MemberPolicies.poolId eq PoolBudgets.poolId)
        })
}.limit(16).associate { it[PoolBudgets.poolId] to it[PoolBudgets.dailyUnits] }

/** Selection and queue hints read the same SQL eligibility under the team's admission lock. */
internal fun JdbcTransaction.accountPlacement(actor: Principal, policy: MemberPolicy, input: AdmissionInput, now: Long): Placement {
    val budgets = if (policy.allowSharedOverflow) sharedOverflowBudgets(actor.teamId) else emptyMap()
    val spends = poolSpends(actor.teamId, budgets.keys, now / 86_400_000L)
    val overflow = budgets.filter { (pool, budget) -> input.units <= budget - (spends[pool] ?: 0L) }.keys
    // Every account of the authorized pools, usable or not: the refusal explains the unusable ones.
    val pool = Accounts.selectAll().where {
        (Accounts.teamId eq actor.teamId) and (Accounts.provider eq input.provider) and
            ((Accounts.poolId eq policy.poolId) or (Accounts.poolId inList overflow))
    }.map { accountRecord(it) to it[Accounts.cooldownUntil] }
    val candidates = pool.filter { (account, cooldown) -> !account.disabled && account.state.canServe && cooldown <= now }.map { it.first }
    val usage = (QuotaWindows innerJoin Accounts).select(QuotaWindows.columns).where {
        (Accounts.teamId eq actor.teamId) and (QuotaWindows.observedAt lessEq now) and
            ((QuotaWindows.resetsAt greater now) or (QuotaWindows.resetsAt.isNull() and (QuotaWindows.observedAt greaterEq now - MAX_QUOTA_OBSERVATION_AGE_MILLIS)))
    }.map(::quotaRecord)
    val modelLimits = readQuotaModelLimits(actor.teamId)
    val count = Reservations.id.count()
    val active = Reservations.select(Reservations.accountId, count).where {
        (Reservations.teamId eq actor.teamId) and (Reservations.state eq "ACTIVE") and (Reservations.expiresAt greater now)
    }.groupBy(Reservations.accountId).associate { it[Reservations.accountId] to it[count].toInt() }
    val affinities = SqlSessionAffinity()
    val pins = input.affinity?.let { affinities.accounts(this, actor, it, input.provider, input.model, now) }.orEmpty()
    val loads = if (input.affinity != null) affinities.loads(this, actor.teamId, now) else emptyMap()
    // Utilization from which an account is excluded: the team setting, shared with the refusal explanation below.
    val pressureThreshold = readPacingSettings(actor.teamId).quotaPressureThreshold
    fun blocking(account: AccountRecord) = usage.filter {
        it.accountId == account.id && it.appliesTo(input.model, modelLimits[account.id]?.get(input.model.lowercase())) && it.utilization >= pressureThreshold
    }
    val available = candidates.filter { it.id !in input.excludedAccountIds }.mapNotNull { account ->
        val utilization = usage.filter { it.accountId == account.id && it.appliesTo(input.model, modelLimits[account.id]?.get(input.model.lowercase())) }
            .maxOfOrNull { it.utilization } ?: 0.0
        if (utilization >= pressureThreshold) null else AccountPlacement(account, active[account.id] ?: 0, loads[account.id] ?: 0, utilization)
    }
    val selector = SelectPoolAccountUseCase()
    val chosen = selector.select(available.filter { it.activeRequests < 2 }, pins, policy.poolId)
    val waiting = chosen ?: selector.select(available, pins, policy.poolId)
    val standing = if (chosen != null) null else {
        var result = PoolStanding(input.provider)
        val next = mutableListOf<Long>()
        for ((account, cooldown) in pool) {
            val windows = blocking(account)
            result = when {
                account.disabled -> result.copy(disabled = result.disabled + 1)
                account.state == AccountState.PENDING_HANDOVER -> result.copy(pendingHandover = result.pendingHandover + 1)
                !account.state.canServe -> result.copy(reconnectRequired = result.reconnectRequired + 1)
                cooldown > now -> { next += cooldown; result.copy(coolingDown = result.coolingDown + 1) }
                windows.isNotEmpty() -> {
                    // An unknown reset stops excluding the account once its observation is too old.
                    next += windows.maxOf { it.resetsAtMillis ?: (it.observedAtMillis + MAX_QUOTA_OBSERVATION_AGE_MILLIS) }
                    result.copy(quotaExhausted = result.quotaExhausted + 1)
                }
                // Already refused for this request by the provider, without a recorded cooldown.
                account.id in input.excludedAccountIds -> result.copy(coolingDown = result.coolingDown + 1)
                else -> result.copy(busy = result.busy + 1)
            }
        }
        result.copy(nextAvailableAt = next.filter { it > now }.minOrNull())
    }
    return Placement(chosen, available.isNotEmpty(), if (waiting == null) policy.poolId.orEmpty() else waiting.poolId.orEmpty(), standing)
}

/** [standing] explains an empty placement; it is computed only then. */
internal data class Placement(val chosen: AccountRecord?, val hasAvailable: Boolean, val turnPool: String, val standing: PoolStanding? = null)
