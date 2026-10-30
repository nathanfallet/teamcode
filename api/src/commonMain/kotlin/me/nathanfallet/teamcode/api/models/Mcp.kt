package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** Tool restriction of an MCP variant: `ALL` exposes every upstream tool, `ALLOWLIST` only `tools`. */
@Serializable
data class McpToolPolicyView(val mode: String, val tools: List<String> = emptyList())

/** Aggregated gateway usage of one member on one MCP server. Metadata only. */
@Serializable
data class McpUsageView(
    val memberId: String, val username: String, val server: String,
    val calls: Long, val lists: Long, val errors: Long, val denied: Long, val responseBytes: Long, val totalDurationMillis: Long,
)

/** One journaled gateway operation. Never arguments, results or credentials. */
@Serializable
data class McpCallView(
    val id: Long, val memberId: String, val server: String, val variantId: String? = null, val operation: String, val tool: String? = null,
    val outcome: String, val startedAt: Long, val durationMillis: Long? = null, val responseBytes: Long? = null,
)

@Serializable
data class McpCallPageView(val calls: List<McpCallView>, val nextBeforeId: Long? = null)

/**
 * One MCP server assigned to the caller and the gateway path that serves it. No upstream address or credential.
 *
 * [transport] is `http` (an HTTPS endpoint) or `stdio` (a hosted command run by the server's MCP host); servers older
 * than `mcp.gateway.stdio.v1` leave it out. For a hosted command, [state] is the last state the server saw of its
 * process (`idle`, `starting`, `ready`, `failed` or `backoff`, refreshed about once a minute) and [failure] the code of
 * its last failure not yet followed by a successful start (for example `start_failed`, `crashed`, `memory`). Both are
 * open lists: a client shows a code it does not know as is.
 */
@Serializable
data class McpServerView(
    val name: String, val path: String,
    val transport: String? = null, val state: String? = null, val failure: String? = null,
)
