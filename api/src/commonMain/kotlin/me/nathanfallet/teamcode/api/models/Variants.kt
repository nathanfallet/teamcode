package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Variant metadata. Secret values are write-only: responses only say whether a shared secret is stored. For an MCP
 * variant, `transport` is `http` (reached at `endpoint`) or `stdio` (a hosted command: `launcher`, `package` and `args`,
 * with its visible environment variables in `config`). Environment variants have no transport.
 */
@Serializable
data class VariantView(
    val id: String, val kind: String, val target: String, val name: String, val binding: String, val version: Int,
    val endpoint: String? = null, val config: Map<String, String> = emptyMap(), val hasSharedSecret: Boolean,
    val createdAt: Long, val updatedAt: Long,
    val transport: String? = null, val launcher: String? = null, @SerialName("package") val pkg: String? = null, val args: List<String> = emptyList(),
)

/**
 * `kind` is ENV or MCP; `binding` is SHARED or PER_MEMBER. `secret` is the shared value or MCP credential.
 * A hosted MCP command (`transport` `stdio`) names its `launcher` (`npm` or `pypi`), its `package` at one exact version
 * and its `args`; `config` then holds visible environment variables and `secretVariables` the encrypted ones, which an
 * argument can use as `${NAME}`. Transport, command and visible variables cannot change later.
 */
@Serializable
class CreateVariantRequest(
    val kind: String, val target: String, val name: String, val binding: String = "SHARED",
    val endpoint: String? = null, val config: Map<String, String> = emptyMap(), val secret: String? = null,
    val transport: String = "http", val launcher: String? = null, @SerialName("package") val pkg: String? = null,
    val args: List<String> = emptyList(), val secretVariables: Map<String, String>? = null,
) {
    override fun toString() = "CreateVariantRequest(kind=$kind, target=$target, name=$name, binding=$binding, transport=$transport, secret=[REDACTED])"
}

/**
 * Replaces name and configuration. A null `secret` keeps the stored one unless `clearSecret`. The endpoint is immutable,
 * and so are a hosted command and its visible variables (send `config` unchanged). `secretVariables` replaces all of a
 * hosted command's secret variables at once.
 */
@Serializable
class UpdateVariantRequest(
    val expectedVersion: Int, val name: String, val config: Map<String, String> = emptyMap(),
    val secret: String? = null, val clearSecret: Boolean = false, val secretVariables: Map<String, String>? = null,
) {
    override fun toString() = "UpdateVariantRequest(expectedVersion=$expectedVersion, name=$name, secret=[REDACTED])"
}

/**
 * `memberSecret` is required for a PER_MEMBER variant and refused for a SHARED one. For a hosted command, send the
 * member's secret variables in `memberSecretVariables` instead.
 */
@Serializable
class AssignVariantRequest(val variantId: String, val memberSecret: String? = null, val memberSecretVariables: Map<String, String>? = null) {
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

/**
 * The environment variables a member is assigned through `ENV` variants, with their values: the caller's own
 * (`GET /api/v1/environment`) or a cloud session owner's for its runner. [unavailable] names assigned variables whose
 * value cannot be read right now. Holds secrets: never log, persist or echo it.
 */
@Serializable
class EnvironmentVariablesView(val variables: Map<String, String> = emptyMap(), val unavailable: List<String> = emptyList()) {
    override fun toString() = "EnvironmentVariablesView(names=${variables.keys.sorted()}, unavailable=$unavailable, values=[REDACTED])"
}
