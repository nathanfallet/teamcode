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
    /** Every process the host knows, with its state, counters and scrubbed stderr. @throws Exception when the host cannot be read. */
    suspend fun status(): HostStatusSnapshot
    /** The administrator's "Restart": stops the process of [key] and lifts its backoff. False when the host did not know it. */
    suspend fun stop(key: String): Boolean
}

/** The host answered but refused a control request ([code]: `unauthorized`, `bad_version`...): a deployment mismatch, not an outage. */
class McpHostRefused(val code: String) : Exception("The MCP host refused the request ($code).")

/** One current assignment of a hosted command: enough to compute its process key, nothing secret. [target] is the server name. */
data class HostedMcpAssignment(
    val teamId: String, val variantId: String, val version: Int, val binding: VariantBinding, val memberId: String, val assignedAt: Long,
    val target: String = "",
) {
    val key get() = StdioProcessKey.of(teamId, variantId, version, binding, memberId, assignedAt)
    /** The member whose process this is: empty for a SHARED variant, whose process the team shares. */
    val processMemberId get() = if (binding == VariantBinding.SHARED) "" else memberId
}

/** Every team's current hosted-command assignments, in a few bounded, indexed reads. */
interface HostedMcpAssignmentSource {
    /**
     * The assignments to hosted commands (transport stdio) that resolution would serve (an enabled member without a
     * temporary password; a PER_MEMBER one with the member's value), or null when there are more than [limit].
     */
    suspend fun hostedMcpAssignments(limit: Int): List<HostedMcpAssignment>?
    /** The same for one team, at most [limit], each with the member's username. */
    suspend fun teamHostedMcpAssignments(teamId: String, limit: Int): List<Pair<HostedMcpAssignment, String>>
}

/**
 * The retain and state sweep, run by one replica a minute. It tells the host which processes are still assigned, so the
 * processes of a deleted variant, a rotated secret, a removed assignment, an exclusion or a disabled member stop within
 * a minute (the gateway refuses their calls at once anyway; this only frees the process and the secrets it holds). Then
 * it reads the host's status and records each assignment's state, publishing `mcp.stdio.failed` and
 * `mcp.stdio.recovered` on transitions ([HostedProcessStateRules]).
 */
class RetainHostedMcpProcessesUseCase(
    private val source: HostedMcpAssignmentSource,
    private val host: McpProcessHost,
    private val states: HostedProcessStateStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend operator fun invoke(): HostedMcpSweep {
        // A truncated list would stop processes that are still assigned: rather keep everything until it fits.
        val assignments = source.hostedMcpAssignments(MAX_ASSIGNMENTS) ?: return HostedMcpSweep.TooMany
        val teams = assignments.mapTo(HashSet()) { it.teamId }
        val stopped = try { host.retain(assignments.map { it.key }.toSet()) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) { return unavailable(teams, error) }
        if (states == null) return HostedMcpSweep.Done(stopped, 0)
        // Teams whose last hosted assignment went still hold rows: they are read too, so those rows are removed.
        val stateTeams = states.teamsWithStates(MAX_ASSIGNMENTS) ?: return HostedMcpSweep.Done(stopped, 0)
        teams += stateTeams
        if (teams.isEmpty()) return HostedMcpSweep.Done(stopped, 0)
        val status = try { host.status() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) { return unavailable(assignments.mapTo(HashSet()) { it.teamId }, error) }
        // At most one row per assignment and one per shared process: a longer list is not complete, so nothing is removed.
        val records = states.records(teams, MAX_ASSIGNMENTS * 2) ?: return HostedMcpSweep.Done(stopped, 0)
        val stored = records.associateBy { it.variantId to it.memberId }
        val now = clock()
        val current = assignments.distinctBy { it.variantId to it.processMemberId }
        val changes = current.mapNotNull { HostedProcessStateRules.change(it, status.byKey[it.key], stored[it.variantId to it.processMemberId], now) }
        val live = current.mapTo(HashSet()) { it.variantId to it.processMemberId }
        val removals = records.filter { (it.variantId to it.memberId) !in live }
        return HostedMcpSweep.Done(stopped, if (changes.isEmpty() && removals.isEmpty()) 0 else states.apply(changes, removals))
    }

    private fun unavailable(teams: Set<String>, error: Exception) =
        HostedMcpSweep.HostUnavailable(teams, if (error is McpHostRefused) HostedMcpSweep.REFUSED else HostedMcpSweep.UNREACHABLE)

    companion object {
        /** About 650 KiB of keys, within the host's 2 MiB request bound. */
        const val MAX_ASSIGNMENTS = 10_000
        const val INTERVAL_MILLIS = 60_000L
        /** At most one `mcp.unreachable` per team in this interval. */
        const val UNREACHABLE_INTERVAL_MILLIS = 600_000L
    }
}
