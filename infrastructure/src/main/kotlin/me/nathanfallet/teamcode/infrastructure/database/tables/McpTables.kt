package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** Tool restriction of one MCP variant. A variant without a row exposes every upstream tool. */
object McpToolPolicies : Table("mcp_tool_policies") {
    val variantId = reference("variant_id", Variants.id, onDelete = ReferenceOption.CASCADE)
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE).index("mcp_tool_policy_team")
    val mode = varchar("mode", 16)
    val tools = text("tools")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(variantId)
}

/**
 * Metadata journal of gateway operations, also used as the shared concurrency and rate ledger: an ACTIVE row holds a
 * slot until it finishes or its lease expires. Never arguments, results or credentials.
 */
object McpCalls : Table("mcp_calls") {
    val id = long("id").autoIncrement()
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE)
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE)
    val server = varchar("server", 64)
    /** Kept without a foreign key so history survives a deleted variant. */
    val variantId = varchar("variant_id", 36).nullable()
    val operation = varchar("operation", 8)
    val tool = varchar("tool", 128).nullable()
    val outcome = varchar("outcome", 16)
    val startedAt = long("started_at")
    val leaseExpiresAt = long("lease_expires_at")
    val durationMillis = long("duration_ms").nullable()
    val responseBytes = long("response_bytes").nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        index("mcp_call_member", false, memberId, server, startedAt)
        index("mcp_call_server", false, teamId, server, startedAt)
        index("mcp_call_team", false, teamId, id)
        index("mcp_call_started", false, startedAt)
    }
}

/**
 * The last state the sweep recorded for the process of each hosted MCP assignment (docs/mcp.md): one row per variant
 * and member, with an empty [memberId] for a SHARED variant's team process. Written only when the state changes, by
 * compare-and-set, so each transition publishes its team event once. No secret, no output.
 */
object McpStdioStates : Table("mcp_stdio_states") {
    val variantId = reference("variant_id", Variants.id, onDelete = ReferenceOption.CASCADE)
    val memberId = varchar("member_id", 36).default("")
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE).index("mcp_stdio_state_team")
    val processKey = varchar("process_key", 64)
    val state = varchar("state", 16)
    val failureCode = varchar("failure_code", 32).nullable()
    val changedAt = long("changed_at")
    override val primaryKey = PrimaryKey(variantId, memberId, name = "pk_mcp_stdio_states")
}
