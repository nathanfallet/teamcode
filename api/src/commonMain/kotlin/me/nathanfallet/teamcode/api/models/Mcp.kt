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

/** One MCP server assigned to the caller and the gateway path that serves it. No upstream address or credential. */
@Serializable
data class McpServerView(val name: String, val path: String)
