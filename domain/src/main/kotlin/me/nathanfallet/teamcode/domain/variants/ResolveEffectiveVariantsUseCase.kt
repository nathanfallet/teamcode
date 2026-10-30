package me.nathanfallet.teamcode.domain.variants

/** One environment variable the member's execution receives. */
data class EffectiveEnvironmentVariable(val name: String, val variantId: String, val variantName: String, val version: Int, val assignedAt: Long, val value: SecretValue)

/** How the gateway reaches one effective MCP server. */
sealed interface McpServerTransport {
    /** The variant's HTTPS endpoint. */
    data class Http(val endpoint: String) : McpServerTransport
    /**
     * A hosted command, run by the server's MCP host. [key] names its process there ([StdioProcessKey]); [declaredAt] is
     * the variant's creation time, the date its dependencies are resolved at. [processMemberId] is the member whose
     * process it is: empty for a SHARED variant, whose process the team shares.
     */
    data class Stdio(val command: StdioCommand, val declaredAt: Long, val key: String, val processMemberId: String = "") : McpServerTransport
}

/**
 * One MCP server the member may discover and call through the gateway. [credential] is null for a server without one;
 * for a hosted command it is the [StdioEnvironment] object of its secret variables, and [config] its visible variables.
 */
data class EffectiveMcpServer(
    val name: String, val variantId: String, val variantName: String, val version: Int, val assignedAt: Long,
    val transport: McpServerTransport, val config: Map<String, String>, val credential: SecretValue?,
) {
    val hosted get() = transport is McpServerTransport.Stdio
}

/**
 * The process a hosted command runs in on the MCP host: one per team server for a SHARED variant, one per member for a
 * PER_MEMBER one. The key changes with the variant's version (a rotated shared secret) and with the member's assignment
 * time (a rotated member value), so a rotation always gets a new process and the old one is stopped by the retain sweep.
 */
object StdioProcessKey {
    fun of(teamId: String, variantId: String, version: Int, binding: VariantBinding, memberId: String, assignedAt: Long): String {
        val parts = if (binding == VariantBinding.SHARED) listOf("shared", teamId, variantId, version.toString())
            else listOf("member", teamId, variantId, version.toString(), memberId, assignedAt.toString())
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * [unavailable] lists assigned slots withheld because a stored value could not be decrypted, a delegated credential
 * is missing, or the server is a hosted command this server cannot run (no MCP host, or a definition it does not
 * read); consumers report them without values.
 * A change of value is visible through `version` (variant edits) or `assignedAt` (a new selection or a rotated member value).
 */
data class EffectiveVariants(
    val memberId: String, val environment: List<EffectiveEnvironmentVariable>, val mcpServers: List<EffectiveMcpServer>,
    val unavailable: List<VariantTarget> = emptyList(),
) {
    companion object { fun none(memberId: String) = EffectiveVariants(memberId, emptyList(), emptyList()) }
}

/** A candidate selection for one slot, with its decrypted values. Adapters return every source they know about. */
data class AssignmentCandidate(
    val target: VariantTarget, val source: AssignmentSource, val variant: Variant,
    val sharedSecret: SecretValue?, val memberSecret: SecretValue?, val assignedAt: Long = 0, val unreadable: Boolean = false,
)

/** Current persisted state for one member. [eligible] is false whenever authentication would refuse the member. */
data class MemberVariantState(val eligible: Boolean, val candidates: List<AssignmentCandidate>, val exclusions: Set<VariantTarget>)

/** Persistence port read on every resolution, so a change made on any replica is visible to the next call. */
interface EffectiveVariantSource {
    suspend fun state(teamId: String, memberId: String): MemberVariantState
}

/** Pure precedence rules, kept separate so group assignments can be added without touching the no-assignment rule. */
object VariantResolution {
    /**
     * An exclusion always wins. Otherwise the candidate with the highest source precedence fills the slot; a slot
     * without any candidate stays empty. Shared variants are never applied implicitly.
     */
    fun select(candidates: List<AssignmentCandidate>, exclusions: Set<VariantTarget>): List<AssignmentCandidate> =
        candidates.filter { it.target !in exclusions && it.target == it.variant.slot }
            .groupBy { it.target }
            .map { (_, options) -> options.maxWith(compareBy<AssignmentCandidate> { it.source.precedence }.thenBy { it.variant.id }) }
            .sortedWith(compareBy({ it.target.kind }, { it.target.name }))
}

/**
 * Resolves what a member effectively receives. This is the trusted server-side entry point for the MCP gateway and
 * cloud sessions: it returns decrypted values, so callers must have authenticated the member (or be acting on an
 * authorized session for them) and must never log or return its result to another member.
 */
class ResolveEffectiveVariantsUseCase(
    private val source: EffectiveVariantSource,
    /** Whether the server has an MCP host to run hosted commands (transport stdio); without one they are withheld. */
    private val hostedCommands: Boolean = false,
) {
    suspend operator fun invoke(teamId: String, memberId: String): EffectiveVariants {
        val state = source.state(teamId, memberId)
        if (!state.eligible) return EffectiveVariants.none(memberId)
        val selected = VariantResolution.select(state.candidates, state.exclusions)
        val unavailable = mutableListOf<VariantTarget>()
        val environment = mutableListOf<EffectiveEnvironmentVariable>()
        val servers = mutableListOf<EffectiveMcpServer>()
        for (candidate in selected) {
            val variant = candidate.variant
            val value = candidate.valueFor()
            when {
                // One unreadable row withholds only its own slot; it never fails the member's other assignments.
                candidate.unreadable -> unavailable += candidate.target
                candidate.target.kind == VariantKind.ENV ->
                    if (value == null) unavailable += candidate.target
                    else environment += EffectiveEnvironmentVariable(candidate.target.name, variant.id, variant.name, variant.version, candidate.assignedAt, value)
                variant.binding == VariantBinding.PER_MEMBER && value == null -> unavailable += candidate.target
                variant.transport == McpTransport.HTTP ->
                    if (variant.endpoint == null) unavailable += candidate.target
                    else servers += EffectiveMcpServer(candidate.target.name, variant.id, variant.name, variant.version, candidate.assignedAt,
                        McpServerTransport.Http(variant.endpoint), variant.config, value)
                // Without an MCP host, or with a command or secret this version cannot read, a hosted command is withheld,
                // so no client lists a server the gateway cannot call.
                else -> {
                    val command = variant.command
                    if (!hostedCommands || command == null || (value != null && StdioEnvironment.decodeOrNull(value) == null)) unavailable += candidate.target
                    else servers += EffectiveMcpServer(candidate.target.name, variant.id, variant.name, variant.version, candidate.assignedAt,
                        McpServerTransport.Stdio(command, variant.createdAt, StdioProcessKey.of(teamId, variant.id, variant.version, variant.binding, memberId, candidate.assignedAt),
                            if (variant.binding == VariantBinding.SHARED) "" else memberId),
                        variant.config, value)
                }
            }
        }
        return EffectiveVariants(memberId, environment, servers, unavailable)
    }
    private fun AssignmentCandidate.valueFor() = if (variant.binding == VariantBinding.PER_MEMBER) memberSecret else sharedSecret
}
