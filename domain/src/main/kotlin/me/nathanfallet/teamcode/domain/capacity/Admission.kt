package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.accounts.AccountRecord
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.routing.*

/** [maxConcurrent] zero (the default) means no per-member limit; group and run limits still apply. */
data class MemberPolicy(val memberId: String, val dailyUnits: Long = 0, val maxConcurrent: Int = UNLIMITED_CONCURRENCY, val poolId: String? = null, val weight: Int = 1, val allowSharedOverflow: Boolean = false)
data class MemberCapacity(val policy: MemberPolicy, val chargedAndReservedUnits: Long, val activeRequests: Int, val resetsAtMillis: Long, val dailyAllowanceUnits: Long = policy.dailyUnits, val poolDailyUnits: Long = 1_000_000, val poolTotalWeight: Long = 1, val queuedRequests: Int = 0, val groups: List<GroupAllowance> = emptyList(), val budgetOverride: BudgetOverrideSummary? = null, val baseDailyAllowanceUnits: Long = dailyAllowanceUnits)
/** A concurrency limit of zero is disabled. */
const val UNLIMITED_CONCURRENCY = 0
const val MAX_MEMBER_CONCURRENCY = 32
/** The tightest enabled limit, or [UNLIMITED_CONCURRENCY] when none of them applies. */
fun concurrencyCeiling(limits: Iterable<Int>): Int = limits.filter { it > 0 }.minOrNull() ?: UNLIMITED_CONCURRENCY
data class PoolBudget(val poolId: String, val dailyUnits: Long, val sharedOverflow: Boolean = false)
data class Reservation(val id: String, val account: AccountRecord, val units: Long)
const val MAX_QUOTA_OBSERVATION_AGE_MILLIS = 300_000L
data class AccountQuota(val accountId: String, val window: String, val utilization: Double, val observedAtMillis: Long, val resetsAtMillis: Long?)
/** Unknown reset times cannot keep an account blocked indefinitely after observations stop. */
fun AccountQuota.isCurrent(now: Long): Boolean = observedAtMillis in 0..now &&
    (resetsAtMillis?.let { it > now } ?: (now - observedAtMillis <= MAX_QUOTA_OBSERVATION_AGE_MILLIS))
fun AccountQuota.appliesTo(model: String, meteredLimit: String? = null): Boolean {
    if (!window.startsWith("model:")) return true
    val family = window.removePrefix("model:").substringBefore(':').lowercase()
    return if (meteredLimit != null) family == meteredLimit.lowercase() else model.lowercase().contains(family)
}
/** A response only reports changes; an authoritative usage probe can also retire model windows. */
data class QuotaUpdate(
    val windows: List<AccountQuota>,
    val observedAtMillis: Long,
    val replaceModelWindows: Boolean = false,
    val modelLimit: ModelLimitObservation? = null,
)
data class ModelLimitObservation(val model: String, val limit: String)
data class AdmissionStatistics(val active: Int, val completed: Long, val queued: Int = 0)
interface AdmissionStore {
    suspend fun preferredTarget(actor: Principal, affinity: RequestAffinity): RouteTarget?
    suspend fun statistics(): AdmissionStatistics
    suspend fun reserve(actor: Principal, units: Long, model: String, provider: String = "anthropic", excludedAccountIds: Set<String> = emptySet(), affinity: RequestAffinity? = null): Reservation
    /** Older adapters cannot silently ignore a launch-specific budget or identity. */
    suspend fun reserve(actor: Principal, input: AdmissionInput): Reservation {
        if (input.runId != null) throw me.nathanfallet.teamcode.domain.team.TeamFailure("run_tracking_unavailable", 503, "Run-aware admission is unavailable.")
        return reserve(actor, input.units, input.model, input.provider, input.excludedAccountIds, input.affinity)
    }
    /** Missing usage conservatively retains the full reservation, including on disconnect/restart. */
    suspend fun settle(reservation: Reservation, actualUnits: Long?, completed: Boolean = false)
    /** Best available provider correlation, recorded before streaming; never a usage estimate. */
    suspend fun recordProviderRequest(reservation: Reservation, requestId: String) = Unit
    suspend fun capacity(actor: Principal, memberId: String = actor.member.id): MemberCapacity
    suspend fun configure(actor: Principal, policy: MemberPolicy): MemberPolicy
    suspend fun poolBudgets(actor: Principal): List<PoolBudget>
    suspend fun configurePool(actor: Principal, budget: PoolBudget): PoolBudget
    suspend fun quotas(actor: Principal): List<AccountQuota>
    suspend fun observe(accountId: String, observations: List<AccountQuota>)
    suspend fun observe(accountId: String, update: QuotaUpdate) = observe(accountId, update.windows)
    /** Internal upstream rejection classification, using the same durable model scope as admission. */
    suspend fun isQuotaExhausted(accountId: String, model: String): Boolean = false
    suspend fun coolDown(accountId: String, untilMillis: Long, reconnect: Boolean = false)
}
