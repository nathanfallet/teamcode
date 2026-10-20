package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** No process IDs, arguments, working directories, raw native session IDs or transcript content. */
object AgentRuns : Table("agent_runs") {
    val id = varchar("id", 36)
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE)
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE)
    val tokenId = varchar("token_id", 36)
    val deviceId = varchar("device_id", 36).nullable()
    val agent = varchar("agent", 16)
    val workload = varchar("workload", 16)
    val startedAt = long("started_at")
    val heartbeatAt = long("heartbeat_at")
    val lastRequestAt = long("last_request_at").nullable()
    val endedAt = long("ended_at").nullable()
    val exitCode = integer("exit_code").nullable()
    val budgetUnits = long("budget_units").default(0)
    val maxConcurrent = integer("max_concurrent").default(0)
    val paused = bool("paused").default(false)
    val chargedUnits = long("charged_units").default(0)
    val attempts = long("attempts").default(0)
    val completed = long("completed").default(0)
    val contextsTruncated = bool("contexts_truncated").default(false)
    override val primaryKey = PrimaryKey(id)
    init {
        index("run_member_page", false, teamId, memberId, startedAt, id)
        index("run_team_page", false, teamId, startedAt, id)
        index("run_retention", false, heartbeatAt, endedAt)
        index("run_member_request", false, teamId, memberId, lastRequestAt)
    }
}

/** Native session and conversation hashes retain TeamClaude's distinction for subagent fan-out. */
object RunConversations : Table("run_conversations") {
    val runId = reference("run_id", AgentRuns.id, onDelete = ReferenceOption.CASCADE)
    val conversationKey = char("conversation_key", 64, "ascii_bin")
    val sessionKey = char("session_key", 64, "ascii_bin")
    val requestedModel = varchar("requested_model", 120)
    val provider = varchar("provider", 32)
    val model = varchar("model", 128)
    val lastSeenAt = long("last_seen_at")
    override val primaryKey = PrimaryKey(runId, conversationKey)
    init { index("run_context_page", false, runId, lastSeenAt) }
    init { index("run_native_sessions", false, runId, sessionKey) }
}
