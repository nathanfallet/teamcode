package me.nathanfallet.teamcode.domain.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.ServiceScope
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.team.requireScope
import me.nathanfallet.teamcode.domain.variants.EffectiveMcpServer
import me.nathanfallet.teamcode.domain.variants.ResolveEffectiveVariantsUseCase

/**
 * Shared persistence for gateway authorization, limits and the metadata journal. Every method reads current MySQL
 * state, so a change made on any replica applies to the next operation.
 */
interface McpAccessStore {
    /** Trusted read of the current tool policies of these variants. A variant without a stored policy exposes all tools. */
    suspend fun policies(teamId: String, variantIds: Collection<String>): Map<String, McpToolPolicy>
    /**
     * Admits one operation under the team lock, the same lock variant writes take: revalidates the credential, checks
     * that the member is still assigned exactly the resolved [server] (same variant, version and assignment time, so a
     * rotated credential is never used afterwards) and not excluded, that the variant's current policy
     * allows [tool] (calls only), then the concurrency and rate limits. Writes the ACTIVE journal row that holds the slot.
     * @throws TeamFailure `mcp_tool_denied` (403) after a revocation, or `mcp_*_concurrency` / `mcp_*_rate` (429).
     */
    suspend fun admit(actor: Principal, server: McpServerRef, operation: McpOperation, tool: String?, limits: McpLimits): McpLease
    /** Releases the slot and records the outcome. Idempotent: only an ACTIVE row changes. */
    suspend fun finish(lease: McpLease, outcome: McpOutcome, durationMillis: Long, responseBytes: Long)
    /** Journals a refused call within the member's rate; past it, the refusal is not stored, which bounds the table. */
    suspend fun denied(actor: Principal, server: String, tool: String?, limits: McpLimits)
    suspend fun toolPolicy(actor: Principal, variantId: String): McpToolPolicy
    suspend fun setToolPolicy(actor: Principal, variantId: String, policy: McpToolPolicy): McpToolPolicy
    /** Per member and server totals over the last [days] days. Interactive administrators see every member, others only themselves. */
    suspend fun usage(actor: Principal, days: Int): List<McpUsage>
    /** Newest journal rows first, administrators only. */
    suspend fun calls(actor: Principal, beforeId: Long?): McpCallPage
    /** Removes journal rows older than the retention period. */
    suspend fun maintain()

    companion object {
        const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val PAGE_SIZE = 50
        const val MAX_USAGE_DAYS = 30
        fun validateDays(days: Int) {
            if (days !in 1..MAX_USAGE_DAYS) throw TeamFailure("invalid_window", 400, "Use a window of 1 to $MAX_USAGE_DAYS days.")
        }
    }
}

/** One upstream tool as the gateway exposes it on its server's endpoint: the upstream name and definition, unchanged. */
data class GatewayTool(val name: String, val definition: String)

/** Result of an authorized call. Rate limits and upstream failures are tool errors, so the agent can report them. */
sealed interface McpCallOutcome {
    class Completed(val result: UpstreamCallResult) : McpCallOutcome
    class Failed(val code: String, val message: String) : McpCallOutcome
}

/** A tool the member cannot call: unknown, not assigned, restricted or revoked. Reported identically for all four. */
class McpToolDenied : Exception("Unknown tool.")

/** A server endpoint the member cannot use: unknown, never assigned or revoked. Reported identically for all three. */
class McpServerDenied : Exception("Unknown MCP server.")

/**
 * The authenticated MCP gateway. Each assigned upstream server has its own endpoint (`/mcp/<server>`), so tools keep
 * their upstream names and sources are never merged into one MCP server. Hiding a tool is never the boundary: every
 * operation resolves the member's variants again and is admitted against current MySQL state.
 */
class McpGateway(
    private val resolve: ResolveEffectiveVariantsUseCase,
    private val store: McpAccessStore,
    private val upstream: McpUpstream,
    val limits: McpLimits = McpLimits(),
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Whether hosted commands (transport stdio) are served: the server has an MCP host. */
    val hostsCommands get() = upstream.hostsCommands

    /**
     * Names of the MCP servers currently assigned to the member, each reachable on its own endpoint. Clients read this
     * right before listing each server's tools, so the processes of hosted servers are started now, in the background.
     */
    suspend fun servers(actor: Principal): List<String> {
        actor.requireScope(ServiceScope.MCP)
        val servers = servers(actor.teamId, actor.member.id)
        upstream.warm(servers.filter { it.hosted })
        return servers.map { it.name }
    }

    /**
     * Checks that [server] is currently assigned to the member, for `initialize` and `ping`.
     * @throws McpServerDenied otherwise.
     */
    suspend fun requireServer(actor: Principal, server: String) {
        actor.requireScope(ServiceScope.MCP)
        server(actor, server)
    }

    /**
     * Tools of one assigned server, filtered by the variant's tool policy. A saturated or failing upstream lists none.
     * @throws McpServerDenied when [server] is not assigned to the member.
     */
    suspend fun listTools(actor: Principal, server: String): List<GatewayTool> {
        actor.requireScope(ServiceScope.MCP)
        val target = server(actor, server)
        val policy = store.policies(actor.teamId, listOf(target.variantId))[target.variantId] ?: McpToolPolicy.ALL
        val tools = try {
            run(actor, target, McpOperation.LIST, null) { upstream.listTools(target, limits).let { it to 0L } }
        } catch (failure: TeamFailure) {
            if (failure.code == "mcp_tool_denied") throw McpServerDenied() // Revoked between resolution and admission.
            if (failure.status != 429) throw failure
            return emptyList()
        } catch (_: McpUpstreamFailure) { return emptyList() }
        return tools.asSequence()
            .filter { McpToolPolicy.isToolName(it.name) && policy.allows(it.name) }
            .map { GatewayTool(it.name, it.definition) }
            .distinctBy { it.name }.take(limits.maxToolsPerServer).sortedBy { it.name }.toList()
    }

    /**
     * Calls [tool] on one assigned server. [arguments] is the caller's JSON object text.
     * @throws McpServerDenied when [server] is not assigned to the member.
     * @throws McpToolDenied when the member cannot call this tool now, whatever the reason.
     */
    suspend fun callTool(actor: Principal, server: String, tool: String, arguments: String?): McpCallOutcome {
        actor.requireScope(ServiceScope.MCP)
        val target = server(actor, server)
        if (!McpToolPolicy.isToolName(tool)) return deny(actor, server, null)
        val policy = store.policies(actor.teamId, listOf(target.variantId))[target.variantId] ?: McpToolPolicy.ALL
        if (!policy.allows(tool)) return deny(actor, server, tool)
        return try {
            McpCallOutcome.Completed(run(actor, target, McpOperation.CALL, tool) { upstream.callTool(target, tool, arguments, limits).let { it to it.bytes } })
        } catch (failure: TeamFailure) {
            if (failure.code == "mcp_tool_denied") deny(actor, server, tool) // Revoked between resolution and admission.
            if (failure.status != 429) throw failure
            McpCallOutcome.Failed(failure.code, failure.message ?: "The MCP gateway limit was reached.")
        } catch (failure: McpUpstreamFailure) {
            McpCallOutcome.Failed("mcp_upstream", failure.message ?: "The MCP server failed.")
        }
    }

    private suspend fun server(actor: Principal, name: String): EffectiveMcpServer {
        val server = servers(actor.teamId, actor.member.id).singleOrNull { it.name == name }
        if (server == null) {
            store.denied(actor, name.takeIf { SERVER_NAME.matches(it) }.orEmpty(), null, limits)
            throw McpServerDenied()
        }
        return server
    }

    private suspend fun servers(teamId: String, memberId: String): List<EffectiveMcpServer> =
        resolve(teamId, memberId).mcpServers.sortedBy { it.name }.take(limits.maxServers)

    private suspend fun deny(actor: Principal, server: String, tool: String?): Nothing {
        store.denied(actor, server, tool, limits)
        throw McpToolDenied()
    }

    companion object {
        /** MCP variant targets, as validated by the variant service; also the last segment of `/mcp/<server>`. */
        val SERVER_NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    }

    /** Admits, runs and always settles the lease, even when the caller disconnects. */
    private suspend fun <T> run(actor: Principal, server: EffectiveMcpServer, operation: McpOperation, tool: String?, block: suspend () -> Pair<T, Long>): T {
        val lease = store.admit(actor, McpServerRef(server.name, server.variantId, server.version, server.assignedAt, server.hosted), operation, tool, limits)
        val started = monotonicMillis()
        var outcome = McpOutcome.CANCELLED
        var bytes = 0L
        try {
            val (value, size) = block()
            bytes = size
            outcome = if (value is UpstreamCallResult && value.isError) McpOutcome.TOOL_ERROR else McpOutcome.OK
            return value
        } catch (failure: McpUpstreamFailure) {
            outcome = failure.outcome
            throw failure
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            outcome = McpOutcome.UPSTREAM_ERROR
            throw McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "The MCP server could not be reached.").also { it.addSuppressed(error) }
        } finally {
            // An unrecorded finish leaves the slot to expire with its lease; it must not mask the call's own result.
            withContext(NonCancellable) {
                try { store.finish(lease, outcome, monotonicMillis() - started, bytes) } catch (_: Exception) {}
            }
        }
    }
}
