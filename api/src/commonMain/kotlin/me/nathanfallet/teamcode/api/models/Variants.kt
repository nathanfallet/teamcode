package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** Variant metadata. Secret values are write-only: responses only say whether a shared secret is stored. */
@Serializable
data class VariantView(
    val id: String, val kind: String, val target: String, val name: String, val binding: String, val version: Int,
    val endpoint: String? = null, val config: Map<String, String> = emptyMap(), val hasSharedSecret: Boolean,
    val createdAt: Long, val updatedAt: Long,
)

/** `kind` is ENV or MCP; `binding` is SHARED or PER_MEMBER. `secret` is the shared value or MCP credential. */
@Serializable
class CreateVariantRequest(
    val kind: String, val target: String, val name: String, val binding: String = "SHARED",
    val endpoint: String? = null, val config: Map<String, String> = emptyMap(), val secret: String? = null,
) {
    override fun toString() = "CreateVariantRequest(kind=$kind, target=$target, name=$name, binding=$binding, secret=[REDACTED])"
}

/** Replaces name and configuration. A null `secret` keeps the stored one unless `clearSecret`. The endpoint is immutable. */
@Serializable
class UpdateVariantRequest(
    val expectedVersion: Int, val name: String, val config: Map<String, String> = emptyMap(),
    val secret: String? = null, val clearSecret: Boolean = false,
) {
    override fun toString() = "UpdateVariantRequest(expectedVersion=$expectedVersion, name=$name, secret=[REDACTED])"
}

/** `memberSecret` is required for a PER_MEMBER variant and refused for a SHARED one. */
@Serializable
class AssignVariantRequest(val variantId: String, val memberSecret: String? = null) {
    override fun toString() = "AssignVariantRequest(variantId=$variantId, memberSecret=[REDACTED])"
}

@Serializable
data class VariantTargetView(val kind: String, val target: String)

@Serializable
data class VariantAssignmentView(
    val kind: String, val target: String, val variantId: String, val variantName: String,
    val version: Int, val source: String, val hasMemberSecret: Boolean, val assignedAt: Long,
)

@Serializable
data class MemberVariantAssignmentsView(
    val memberId: String, val username: String, val disabled: Boolean,
    val assignments: List<VariantAssignmentView>, val exclusions: List<VariantTargetView>,
)

@Serializable
data class BulkAssignmentResponse(val assigned: Int, val keptDirect: Int, val excluded: Int)
