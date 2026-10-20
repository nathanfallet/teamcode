package me.nathanfallet.teamcode.domain.variants

import java.net.URI
import java.net.URISyntaxException
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** What a variant configures: one environment variable or one MCP server. */
enum class VariantKind { ENV, MCP }

/**
 * Where the secret part of a variant lives. SHARED keeps one encrypted value on the variant (an environment value or a
 * team MCP credential). PER_MEMBER stores an encrypted value on each assignment (an individual value or a delegated MCP
 * credential), so the variant itself never holds one.
 */
enum class VariantBinding { SHARED, PER_MEMBER }

/**
 * How an assignment was made. Higher precedence wins when several sources select the same target for a member.
 * GROUP is reserved for group assignments (between DIRECT and BULK); no adapter produces it yet.
 */
enum class AssignmentSource(val precedence: Int) { DIRECT(300), GROUP(200), BULK(100) }

/** The slot a variant fills: an environment variable name or an MCP server name. A member has at most one per slot. */
data class VariantTarget(val kind: VariantKind, val name: String)

/** A secret that never prints itself. Callers read [reveal] only to hand it to an authorized execution. */
class SecretValue(private val value: String) {
    fun reveal() = value
    override fun toString() = "SecretValue([REDACTED])"
    override fun equals(other: Any?) = other is SecretValue && other.value == value
    override fun hashCode() = value.hashCode()
}

/** Variant metadata. Secret values are not part of this type; [hasSharedSecret] only says whether one is stored. */
data class Variant(
    val id: String, val kind: VariantKind, val target: String, val name: String, val binding: VariantBinding,
    val version: Int, val endpoint: String?, val config: Map<String, String>, val hasSharedSecret: Boolean,
    val createdAt: Long, val updatedAt: Long,
) {
    val slot get() = VariantTarget(kind, target)
}

data class NewVariant(
    val kind: VariantKind, val target: String, val name: String, val binding: VariantBinding,
    val endpoint: String? = null, val config: Map<String, String> = emptyMap(), val sharedSecret: SecretValue? = null,
)

/**
 * Replaces the mutable fields. A null [sharedSecret] keeps the stored one unless [clearSharedSecret] removes it.
 * Kind, target, binding and the MCP endpoint are immutable: stored credentials must never follow a new host.
 */
data class VariantUpdate(
    val expectedVersion: Int, val name: String, val config: Map<String, String> = emptyMap(),
    val sharedSecret: SecretValue? = null, val clearSharedSecret: Boolean = false,
)

data class VariantAssignment(
    val memberId: String, val target: VariantTarget, val variantId: String, val variantName: String,
    val version: Int, val source: AssignmentSource, val hasMemberSecret: Boolean, val assignedAt: Long,
)

/** Metadata view of one member's effective assignments, for administrators. No secret values. */
data class MemberVariantAssignments(
    val memberId: String, val username: String, val disabled: Boolean,
    val assignments: List<VariantAssignment>, val exclusions: List<VariantTarget>,
)

data class BulkAssignment(val assigned: Int, val keptDirect: Int, val excluded: Int)

/** Administrator management of variants, assignments and exclusions. Adapters revalidate the actor in the write transaction. */
interface VariantService {
    suspend fun list(actor: Principal): List<Variant>
    suspend fun create(actor: Principal, variant: NewVariant): Variant
    suspend fun update(actor: Principal, id: String, update: VariantUpdate): Variant
    suspend fun delete(actor: Principal, id: String)
    /** Selects [variantId] for the member's slot, replacing any previous selection. Refused while the member is excluded. */
    suspend fun assign(actor: Principal, memberId: String, variantId: String, memberSecret: SecretValue? = null): VariantAssignment
    suspend fun unassign(actor: Principal, memberId: String, target: VariantTarget)
    /** Assigns a SHARED variant to every enabled member, except excluded members and members with a DIRECT selection. */
    suspend fun bulkAssign(actor: Principal, variantId: String): BulkAssignment
    /** Removes the member's selection for this slot and blocks later direct or bulk assignment until [include]. */
    suspend fun exclude(actor: Principal, memberId: String, target: VariantTarget)
    suspend fun include(actor: Principal, memberId: String, target: VariantTarget)
    /** Every member of the team, or only [memberId], with their effective assignments. */
    suspend fun assignments(actor: Principal, memberId: String? = null): List<MemberVariantAssignments>

    companion object {
        const val VARIANT_LIMIT = 500L
        const val SECRET_LIMIT = 16_384
        private val envName = Regex("[A-Z_][A-Z0-9_]{0,127}")
        private val mcpName = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val configKey = Regex("[A-Za-z0-9_.-]{1,64}")
        /** Names that would let a variant take over the process, the dynamic loader or the agent's own connection. */
        private val reservedEnvNames = setOf(
            "PATH", "HOME", "USER", "LOGNAME", "SHELL", "PWD", "TMPDIR", "TMP", "TEMP", "IFS", "ENV", "BASH_ENV", "PROMPT_COMMAND",
            "NODE_OPTIONS", "NODE_PATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "PYTHONPATH", "PYTHONSTARTUP",
            "PYTHONHOME", "PERL5OPT", "PERL5LIB", "RUBYOPT", "RUBYLIB", "GIT_SSH", "GIT_SSH_COMMAND", "GIT_EXEC_PATH", "GIT_CONFIG_GLOBAL",
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR", "NODE_EXTRA_CA_CERTS", "REQUESTS_CA_BUNDLE",
        )
        private val reservedEnvPrefixes = listOf("LD_", "DYLD_", "TEAMCODE_", "ANTHROPIC_", "CLAUDE_", "OPENAI_", "CODEX_", "XDG_", "GIT_CONFIG_")

        fun validateTarget(target: VariantTarget) {
            val pattern = if (target.kind == VariantKind.ENV) envName else mcpName
            if (!pattern.matches(target.name)) throw invalid(
                if (target.kind == VariantKind.ENV) "Use an environment variable name of uppercase letters, digits and underscores."
                else "Use an MCP server name of lowercase letters, digits, dots, dashes and underscores (at most 64)."
            )
            if (target.kind == VariantKind.ENV && (target.name in reservedEnvNames || reservedEnvPrefixes.any { target.name.startsWith(it) }))
                throw invalid("${target.name} is reserved for the runtime, its loader or TeamCode's own agent connection.")
        }
        fun validate(variant: NewVariant) {
            validateTarget(VariantTarget(variant.kind, variant.target))
            validateFields(variant.kind, variant.name, variant.endpoint, variant.config, variant.sharedSecret)
            when {
                variant.binding == VariantBinding.PER_MEMBER && variant.sharedSecret != null ->
                    throw invalid("A per-member variant stores its values on each assignment, not on the variant.")
                variant.kind == VariantKind.ENV && variant.binding == VariantBinding.SHARED && variant.sharedSecret == null ->
                    throw invalid("A shared environment variant needs a value.")
            }
        }
        fun validate(current: Variant, update: VariantUpdate) {
            validateFields(current.kind, update.name, current.endpoint, update.config, update.sharedSecret)
            if (current.binding == VariantBinding.PER_MEMBER && (update.sharedSecret != null || update.clearSharedSecret))
                throw invalid("A per-member variant stores its values on each assignment, not on the variant.")
            if (update.clearSharedSecret && update.sharedSecret != null) throw invalid("Either replace or clear the shared secret, not both.")
            if (current.kind == VariantKind.ENV && update.clearSharedSecret) throw invalid("A shared environment variant needs a value.")
        }
        fun validateMemberSecret(binding: VariantBinding, secret: SecretValue?) {
            if (binding == VariantBinding.PER_MEMBER && secret == null) throw invalid("A per-member variant needs this member's value or credential.")
            if (binding == VariantBinding.SHARED && secret != null) throw invalid("A shared variant does not accept a member value.")
            secret?.let(::validateSecret)
        }
        private fun validateFields(kind: VariantKind, name: String, endpoint: String?, config: Map<String, String>, secret: SecretValue?) {
            if (name.isBlank() || name.length > 120 || name.any(Char::isISOControl)) throw invalid("Use a variant name of 1–120 characters without control characters.")
            secret?.let(::validateSecret)
            if (kind == VariantKind.ENV) {
                if (endpoint != null || config.isNotEmpty()) throw invalid("Environment variants have a value only, no endpoint or configuration.")
                return
            }
            validateEndpoint(endpoint ?: throw invalid("An MCP variant needs an HTTPS endpoint."))
            if (config.size > 32 || config.any { (key, value) -> !configKey.matches(key) || value.length > 1024 || value.any(Char::isISOControl) })
                throw invalid("Use at most 32 configuration entries with simple keys and values of at most 1024 characters.")
        }
        private fun validateSecret(secret: SecretValue) {
            val value = secret.reveal()
            if (value.isEmpty() || value.length > SECRET_LIMIT) throw invalid("Secret values must contain 1–$SECRET_LIMIT characters.")
        }
        private fun validateEndpoint(endpoint: String) {
            val uri = try { URI(endpoint) } catch (_: URISyntaxException) { throw invalid("Use a valid HTTPS MCP endpoint.") }
            if (endpoint.length > 2048 || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null)
                throw invalid("Use an HTTPS MCP endpoint without credentials or a fragment; put credentials in the encrypted secret.")
        }
        private fun invalid(message: String) = TeamFailure("invalid_variant", 400, message)
    }
}
