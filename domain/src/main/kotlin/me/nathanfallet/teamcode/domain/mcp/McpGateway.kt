package me.nathanfallet.teamcode.domain.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

/** One tool as the gateway exposes it: `<server>__<tool>`, with the upstream definition. */
data class GatewayTool(val name: String, val server: String, val tool: String, val definition: String)

/** Result of an authorized call. Rate limits and upstream failures are tool errors, so the agent can report them. */
sealed interface McpCallOutcome {
    class Completed(val result: UpstreamCallResult) : McpCallOutcome
    class Failed(val code: String, val message: String) : McpCallOutcome
}

/** A tool the member cannot call: unknown, not assigned, restricted or revoked. Reported identically for all four. */
class McpToolDenied : Exception("Unknown tool.")

/** Gateway names: the server name, two underscores, the upstream tool name. Server names may contain underscores. */
object McpToolNames {
    const val SEPARATOR = "__"
    const val MAX_LENGTH = 128
    fun expose(server: String, tool: String) = server + SEPARATOR + tool
    /** The longest matching server prefix wins, so listing and calling always agree on the same pair. */
    fun resolve(name: String, servers: Collection<String>): Pair<String, String>? =
        servers.filter { name.length > it.length + SEPARATOR.length && name.startsWith(it + SEPARATOR) }
            .maxByOrNull { it.length }?.let { it to name.substring(it.length + SEPARATOR.length) }
}

/**
 * The authenticated MCP gateway: discovery and invocation through the member's effective MCP variants. Hiding a tool
 * is never the boundary: every call resolves the member's variants again and is admitted against current MySQL state.
 */
class McpGateway(
    private val resolve: ResolveEffectiveVariantsUseCase,
    private val store: McpAccessStore,
    private val upstream: McpUpstream,
    val limits: McpLimits = McpLimits(),
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Tools of every assigned server the member can currently reach. A failing or saturated server is omitted. */
    suspend fun listTools(actor: Principal): List<GatewayTool> {
        actor.requireScope(ServiceScope.MCP)
        val servers = servers(actor)
        if (servers.isEmpty()) return emptyList()
        val policies = store.policies(actor.teamId, servers.map { it.variantId })
        val names = servers.map { it.name }
        val permits = Semaphore(limits.listParallelism)
        return coroutineScope {
            servers.map { server ->
                async {
                    permits.withPermit {
                        val policy = policies[server.variantId] ?: McpToolPolicy.ALL
                        val tools = try {
                            run(actor, server, McpOperation.LIST, null) { upstream.listTools(server, limits).let { it to 0L } }
                        } catch (failure: TeamFailure) {
                            if (failure.status != 429 && failure.code != "mcp_tool_denied") throw failure
                            null
                        } catch (_: McpUpstreamFailure) { null }
                        tools.orEmpty().asSequence()
                            .filter { McpToolPolicy.isToolName(it.name) && policy.allows(it.name) }
                            .map { GatewayTool(McpToolNames.expose(server.name, it.name), server.name, it.name, it.definition) }
                            .filter { it.name.length <= McpToolNames.MAX_LENGTH && McpToolNames.resolve(it.name, names) == (server.name to it.tool) }
                            .distinctBy { it.name }.take(limits.maxToolsPerServer).toList()
                    }
                }
            }.awaitAll().flatten().sortedBy { it.name }
        }
    }

    /**
     * Calls one exposed tool. [arguments] is the caller's JSON object text.
     * @throws McpToolDenied when the member cannot call this tool now, whatever the reason.
     */
    suspend fun callTool(actor: Principal, name: String, arguments: String?): McpCallOutcome {
        actor.requireScope(ServiceScope.MCP)
        val servers = servers(actor)
        val (serverName, tool) = McpToolNames.resolve(name, servers.map { it.name })?.takeIf { McpToolPolicy.isToolName(it.second) }
            ?: return deny(actor, "", name.takeIf { it.length <= McpToolNames.MAX_LENGTH && McpToolPolicy.isToolName(it) })
        val server = servers.single { it.name == serverName }
        val policy = store.policies(actor.teamId, listOf(server.variantId))[server.variantId] ?: McpToolPolicy.ALL
        if (!policy.allows(tool)) return deny(actor, serverName, tool)
        return try {
            McpCallOutcome.Completed(run(actor, server, McpOperation.CALL, tool) { upstream.callTool(server, tool, arguments, limits).let { it to it.bytes } })
        } catch (failure: TeamFailure) {
            if (failure.code == "mcp_tool_denied") deny(actor, serverName, tool) // Revoked between resolution and admission.
            if (failure.status != 429) throw failure
            McpCallOutcome.Failed(failure.code, failure.message ?: "The MCP gateway limit was reached.")
        } catch (failure: McpUpstreamFailure) {
            McpCallOutcome.Failed("mcp_upstream", failure.message ?: "The MCP server failed.")
        }
    }

    private suspend fun servers(actor: Principal): List<EffectiveMcpServer> =
        resolve(actor.teamId, actor.member.id).mcpServers.sortedBy { it.name }.take(limits.maxServers)

    private suspend fun deny(actor: Principal, server: String, tool: String?): Nothing {
        store.denied(actor, server, tool, limits)
        throw McpToolDenied()
    }

    /** Admits, runs and always settles the lease, even when the caller disconnects. */
    private suspend fun <T> run(actor: Principal, server: EffectiveMcpServer, operation: McpOperation, tool: String?, block: suspend () -> Pair<T, Long>): T {
        val lease = store.admit(actor, McpServerRef(server.name, server.variantId, server.version, server.assignedAt), operation, tool, limits)
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
