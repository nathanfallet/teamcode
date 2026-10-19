package me.nathanfallet.teamcode.domain.mcp

import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.variants.EffectiveMcpServer

/** Which upstream tools an MCP variant exposes. ALL keeps the whole server; ALLOWLIST exposes only [tools]. */
enum class McpToolMode { ALL, ALLOWLIST }

/**
 * A per-variant tool restriction. Members who need different tools of the same server receive different variants
 * (for example `tracker/read-only` and `tracker/full`), so the existing assignment, bulk and exclusion rules apply.
 */
data class McpToolPolicy(val mode: McpToolMode, val tools: Set<String> = emptySet()) {
    fun allows(tool: String) = mode == McpToolMode.ALL || tool in tools

    companion object {
        val ALL = McpToolPolicy(McpToolMode.ALL)
        const val TOOL_LIMIT = 256
        private val toolName = Regex("[A-Za-z0-9_.-]{1,128}")
        fun isToolName(value: String) = toolName.matches(value)
        fun validate(policy: McpToolPolicy) {
            if (policy.mode == McpToolMode.ALL && policy.tools.isNotEmpty()) throw invalid("A policy exposing every tool takes no tool list.")
            if (policy.mode == McpToolMode.ALLOWLIST && policy.tools.isEmpty()) throw invalid("List at least one tool, or remove the MCP assignment instead.")
            if (policy.tools.size > TOOL_LIMIT || policy.tools.any { !isToolName(it) })
                throw invalid("Use at most $TOOL_LIMIT tool names of letters, digits, '_', '-' or '.' (at most 128 characters).")
        }
        private fun invalid(message: String) = TeamFailure("invalid_tool_policy", 400, message)
    }
}

/** What the gateway journals for one upstream operation. Never arguments or results. */
enum class McpOperation { LIST, CALL }

/** Final state of a journaled operation. ACTIVE rows also hold a concurrency lease until they finish or expire. */
enum class McpOutcome { ACTIVE, OK, TOOL_ERROR, DENIED, UPSTREAM_ERROR, TIMEOUT, TOO_LARGE, CANCELLED }

/**
 * Execution bounds. Concurrency and per-minute rates apply per member and server, and per server across the team;
 * all of them are enforced in shared MySQL so they hold across replicas.
 */
data class McpLimits(
    val callTimeoutMillis: Long = 60_000,
    val listTimeoutMillis: Long = 15_000,
    val maxResponseBytes: Int = 4 * 1024 * 1024,
    val maxRequestBytes: Int = 1024 * 1024,
    val memberConcurrentPerServer: Int = 4,
    val serverConcurrent: Int = 32,
    val memberCallsPerMinute: Int = 120,
    val serverCallsPerMinute: Int = 1_200,
    val maxServers: Int = 32,
    val maxToolsPerServer: Int = 256,
) {
    init {
        require(callTimeoutMillis in 1_000..600_000 && listTimeoutMillis in 1_000..120_000)
        require(maxResponseBytes in 1_024..64 * 1024 * 1024 && maxRequestBytes in 1_024..16 * 1024 * 1024)
        require(memberConcurrentPerServer in 1..256 && serverConcurrent in memberConcurrentPerServer..4_096)
        require(memberCallsPerMinute in 1..100_000 && serverCallsPerMinute in memberCallsPerMinute..1_000_000)
        require(maxServers in 1..256 && maxToolsPerServer in 1..4_096)
    }
    /** A lease outlives the operation's own deadline, so a crashed replica's slot is reclaimed shortly after. */
    fun leaseMillis(operation: McpOperation) = (if (operation == McpOperation.CALL) callTimeoutMillis else listTimeoutMillis) + 30_000
}

/** One upstream tool. [definition] is the tool's JSON object exactly as the upstream described it. */
class UpstreamTool(val name: String, val definition: String)

/** Raw JSON `CallToolResult` object returned by the upstream. [isError] mirrors its `isError` flag. */
class UpstreamCallResult(val result: String, val isError: Boolean, val bytes: Long)

/** Why an upstream operation failed. The message is returned to the calling member only, never journaled or logged. */
class McpUpstreamFailure(val outcome: McpOutcome, message: String) : Exception(message)

/**
 * Talks to one upstream MCP server on behalf of the gateway. Implementations attach the server's credential, refuse
 * private destinations, bound time and size, and never return the credential in a result.
 */
interface McpUpstream {
    suspend fun listTools(server: EffectiveMcpServer, limits: McpLimits): List<UpstreamTool>
    /** [arguments] is the caller's JSON object text, or null for none. */
    suspend fun callTool(server: EffectiveMcpServer, tool: String, arguments: String?, limits: McpLimits): UpstreamCallResult
}

/** The resolved assignment an operation was prepared from; admission refuses it once any part changed. */
data class McpServerRef(val name: String, val variantId: String, val version: Int, val assignedAt: Long)

/** A concurrency slot and journal row for one admitted operation. */
data class McpLease(val id: Long, val teamId: String, val memberId: String, val server: String)

/** Aggregated metadata for one member and server over the requested window. */
data class McpUsage(
    val memberId: String, val username: String, val server: String,
    val calls: Long, val lists: Long, val errors: Long, val denied: Long, val responseBytes: Long, val totalDurationMillis: Long,
)

/** One journaled operation, metadata only. */
data class McpCallRecord(
    val id: Long, val memberId: String, val server: String, val variantId: String?, val operation: McpOperation, val tool: String?,
    val outcome: McpOutcome, val startedAt: Long, val durationMillis: Long?, val responseBytes: Long?,
)
data class McpCallPage(val calls: List<McpCallRecord>, val nextBeforeId: Long?)
