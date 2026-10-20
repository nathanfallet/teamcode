package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** Only request-specific provider evidence can replace an unknown charge; quota percentages cannot. */
enum class UsageEvidence { PROVIDER_RECORD, PROVIDER_CONFIRMATION }
data class UsageReconciliationChange(val expectedUnits: Long, val actualUnits: Long, val source: UsageEvidence, val reference: String, val reason: String, val expectedProviderRequestId: String? = null)
data class PendingUsage(
    val id: String, val memberId: String, val username: String, val accountId: String, val accountName: String, val provider: String,
    val providerRequestId: String?, val poolId: String, val runId: String?, val dayNumber: Long, val units: Long,
    val state: String, val createdAtMillis: Long, val leaseExpiresAtMillis: Long, val sourcePoolId: String = poolId,
)
/** Durable correction metadata survives ordinary detailed-ledger retention. No prompt or response content. */
data class ReconciledUsage(
    val id: String, val memberId: String, val accountId: String, val poolId: String, val runId: String?, val dayNumber: Long,
    val providerRequestId: String?, val previousState: String, val previousUnits: Long, val actualUnits: Long,
    val source: UsageEvidence, val reference: String, val reason: String, val actorId: String, val reconciledAtMillis: Long, val groupIds: List<String> = emptyList(), val sourcePoolId: String = poolId,
)
data class PendingUsagePage(val requests: List<PendingUsage>, val nextBeforeId: String?)
data class ReconciledUsagePage(val reconciliations: List<ReconciledUsage>, val nextBeforeId: String?)
data class UsageReview(val pending: PendingUsage? = null, val reconciliation: ReconciledUsage? = null)

interface UsageReconciliationService {
    suspend fun pending(actor: Principal, memberId: String? = null, beforeId: String? = null): PendingUsagePage
    suspend fun history(actor: Principal, memberId: String? = null, beforeId: String? = null): ReconciledUsagePage
    suspend fun get(actor: Principal, id: String): UsageReview
    suspend fun reconcile(actor: Principal, id: String, change: UsageReconciliationChange): ReconciledUsage
}

/** Finality and optimistic charge checks prevent a manual correction from racing live or settled work. */
class CheckUsageReconciliationUseCase {
    fun eligible(state: String, expiresAt: Long, now: Long) = state == "UNCERTAIN" || (state == "ACTIVE" && expiresAt <= now)
    fun validate(change: UsageReconciliationChange): UsageReconciliationChange {
        if (change.expectedUnits !in 0..100_000_000L || change.actualUnits !in 0..100_000_000L)
            throw TeamFailure("invalid_reconciliation", 400, "Use verified total request credits between 0 and 100000000.")
        if (change.expectedProviderRequestId?.let { !ProviderRequestIds.valid(it) } == true)
            throw TeamFailure("invalid_reconciliation", 400, "Use the request identifier shown in the review.")
        return change.copy(reference = text(change.reference), reason = text(change.reason))
    }
    fun check(state: String, units: Long, expiresAt: Long, now: Long, requestId: String?, change: UsageReconciliationChange) {
        if (!eligible(state, expiresAt, now)) throw TeamFailure("usage_not_pending", 409, "This request is active or already has a final charge.")
        if (units != change.expectedUnits || requestId != change.expectedProviderRequestId)
            throw TeamFailure("usage_changed", 409, "The charge or request identity changed. Refresh before reconciling it.")
    }
    private fun text(value: String) = value.trim().also {
        if (it.length !in 1..240 || it.any { c -> c < ' ' || c == '\u007f' })
            throw TeamFailure("invalid_reconciliation", 400, "Provide single-line evidence and reason fields of 1–240 characters, without secrets or content.")
    }
}

/** Correlation metadata is accepted only from fixed provider response headers, never request bodies. */
object ProviderRequestIds {
    fun valid(value: String) = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}").matches(value)
}
