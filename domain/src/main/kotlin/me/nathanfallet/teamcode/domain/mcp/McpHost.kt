package me.nathanfallet.teamcode.domain.mcp

import me.nathanfallet.teamcode.domain.variants.StdioLauncher
import me.nathanfallet.teamcode.domain.variants.StdioProcessKey
import me.nathanfallet.teamcode.domain.variants.VariantBinding

/**
 * The server's MCP stdio host (docs/mcp.md), outside the gateway's own operations: what it can launch, and which
 * processes it may keep. It holds no state the server depends on: a lost process is started again by the next operation.
 */
interface McpProcessHost {
    /** The launchers the host offers, for declaring a hosted command. @throws me.nathanfallet.teamcode.domain.team.TeamFailure when the host cannot be reached. */
    suspend fun launchers(): Set<StdioLauncher>
    /** Stops every process whose key is not in [keys]. Returns how many were stopped. */
    suspend fun retain(keys: Collection<String>): Int
}

/** One current assignment of a hosted command: enough to compute its process key, nothing secret. */
data class HostedMcpAssignment(
    val teamId: String, val variantId: String, val version: Int, val binding: VariantBinding, val memberId: String, val assignedAt: Long,
) {
    val key get() = StdioProcessKey.of(teamId, variantId, version, binding, memberId, assignedAt)
}

/** Every team's current hosted-command assignments, in a few bounded, indexed reads. */
interface HostedMcpAssignmentSource {
    /** The assignments of enabled members to hosted commands (transport stdio), or null when there are more than [limit]. */
    suspend fun hostedMcpAssignments(limit: Int): List<HostedMcpAssignment>?
}

/**
 * The retain sweep: tells the host which processes are still assigned, so the processes of a deleted variant, a
 * rotated secret, a removed assignment, an exclusion or a disabled member stop within a minute. The gateway refuses
 * their calls at once anyway; this only frees the process and the secrets it holds. Run by one replica at a time.
 */
class RetainHostedMcpProcessesUseCase(private val source: HostedMcpAssignmentSource, private val host: McpProcessHost) {
    /** Returns how many processes stopped, or null when the assignment list was too long to be trusted as complete. */
    suspend operator fun invoke(): Int? {
        // A truncated list would stop processes that are still assigned: rather keep everything until it fits.
        val assignments = source.hostedMcpAssignments(MAX_ASSIGNMENTS) ?: return null
        return host.retain(assignments.map { it.key }.toSet())
    }

    companion object {
        /** About 650 KiB of keys, within the host's 2 MiB request bound. */
        const val MAX_ASSIGNMENTS = 10_000
        const val INTERVAL_MILLIS = 60_000L
    }
}
