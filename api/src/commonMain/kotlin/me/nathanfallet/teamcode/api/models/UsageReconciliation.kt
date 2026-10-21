package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

@Serializable
data class UsageReconciliationRequest(val expectedUnits: Long, val actualUnits: Long, val source: String, val reference: String, val reason: String, val expectedProviderRequestId: String? = null)
@Serializable
data class PendingUsageView(
    val id: String, val memberId: String, val username: String, val accountId: String, val accountName: String, val provider: String,
    val providerRequestId: String?, val poolId: String, val runId: String?, val dayNumber: Long, val units: Long,
    val state: String, val createdAtMillis: Long, val leaseExpiresAtMillis: Long, val sourcePoolId: String = poolId, val accountEmail: String? = null,
)
@Serializable
data class ReconciledUsageView(
    val id: String, val memberId: String, val accountId: String, val poolId: String, val runId: String?, val dayNumber: Long,
    val providerRequestId: String?, val previousState: String, val previousUnits: Long, val actualUnits: Long,
    val source: String, val reference: String, val reason: String, val actorId: String, val reconciledAtMillis: Long, val groupIds: List<String> = emptyList(), val sourcePoolId: String = poolId,
)
@Serializable
data class PendingUsagePageView(val requests: List<PendingUsageView>, val nextBeforeId: String? = null)
@Serializable
data class ReconciledUsagePageView(val reconciliations: List<ReconciledUsageView>, val nextBeforeId: String? = null)
@Serializable
data class UsageReviewView(val pending: PendingUsageView? = null, val reconciliation: ReconciledUsageView? = null)
