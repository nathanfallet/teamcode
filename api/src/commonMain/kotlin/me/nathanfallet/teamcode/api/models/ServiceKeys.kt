package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/**
 * [ServiceKeyView.expiresAt] is `Long.MAX_VALUE` when [ServiceKeyView.neverExpires], so clients that predate
 * non-expiring keys still decode the list. [CreateServiceKeyRequest.validDays] is 1-90, or an explicit `null`
 * for a key without expiry (an administrator creating it, for themselves or another member); an omitted field keeps the 30-day default.
 * [ServiceKeyView.createdBy] and [ServiceKeyView.createdByName] name the member who created the key, null for keys created before it was recorded.
 */
@Serializable
data class ServiceKeyView(val id: String, val memberId: String, val name: String, val scopes: List<String>, val createdAt: Long, val expiresAt: Long, val neverExpires: Boolean = false, val createdBy: String? = null, val createdByName: String? = null)
@Serializable
data class CreateServiceKeyRequest(val name: String, val scopes: List<String>, val validDays: Int? = 30)
@Serializable
class CreatedServiceKeyResponse(val metadata: ServiceKeyView, val accessToken: String) {
    override fun toString() = "CreatedServiceKeyResponse(metadata=$metadata, accessToken=[REDACTED])"
}
