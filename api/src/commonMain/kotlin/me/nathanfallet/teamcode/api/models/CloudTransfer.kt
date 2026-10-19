package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** Wire names of transfer states (`cloud.transfer.v1`). Clients must tolerate unknown values. */
object CloudTransferStates {
    val all = listOf("offered", "accepted", "completed", "declined", "cancelled", "expired", "failed")
}

/** `POST /api/v1/cloud/sessions/{id}/transfer`, with an `Idempotency-Key`. */
@Serializable
data class CreateCloudTransferRequest(
    val toMemberId: String,
    /** Resume the session for the recipient once the owner changed. */
    val resume: Boolean = false,
    /** Pause at once instead of letting the running turn finish; the workspace is then crash-consistent. */
    val force: Boolean = false,
)
@Serializable
data class AcceptCloudTransferRequest(
    /** Resume once the session is the recipient's; null keeps what the offer asked for. */
    val resume: Boolean? = null,
)

@Serializable
data class CloudTransferView(
    val id: String,
    val sessionId: String,
    val fromMemberId: String,
    val toMemberId: String,
    val state: String,
    val initiatedBy: CloudAttributionView,
    val decidedBy: CloudAttributionView? = null,
    val resume: Boolean,
    val force: Boolean,
    val pendingInputs: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Offers: the recipient must accept before this time. */
    val expiresAt: Long? = null,
    val decidedAt: Long? = null,
    val completedAt: Long? = null,
    /** The last epoch run for the former owner; later executions run for the recipient. */
    val atEpoch: Int? = null,
    /** The `transfer` transcript event recording the change of owner. */
    val eventSeq: Long? = null,
    val failure: CloudProblemView? = null,
)
@Serializable
data class CloudTransferPageView(val transfers: List<CloudTransferView>, val nextBefore: String? = null)
