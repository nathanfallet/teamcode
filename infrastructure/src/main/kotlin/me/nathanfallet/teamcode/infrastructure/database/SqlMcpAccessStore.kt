package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.domain.mcp.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.variants.VariantKind
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Gateway authorization, limits and journal in shared MySQL. Admission takes the team lock that variant writes also
 * take, so an operation admitted after a revocation commits is refused, on every replica.
 */
class SqlMcpAccessStore(private val database: Database) : McpAccessStore {
    override suspend fun policies(teamId: String, variantIds: Collection<String>): Map<String, McpToolPolicy> =
        if (variantIds.isEmpty()) emptyMap() else database.query {
            McpToolPolicies.selectAll().where { (McpToolPolicies.teamId eq teamId) and (McpToolPolicies.variantId inList variantIds.distinct()) }
                .associate { it[McpToolPolicies.variantId] to policy(it) }
        }

    override suspend fun admit(actor: Principal, server: McpServerRef, operation: McpOperation, tool: String?, limits: McpLimits): McpLease = database.query {
        val variantId = server.variantId
        actor.requireScope(ServiceScope.MCP)
        lockTeam(actor.teamId)
        val now = nowMillis()
        requireCurrent(this, actor, now)
        // The resolved endpoint and credential must still be current: a rotated shared secret bumps the variant
        // version, a new selection or delegated credential changes assignedAt.
        val assigned = !(VariantAssignments innerJoin Variants).select(VariantAssignments.memberId).where {
            (VariantAssignments.memberId eq actor.member.id) and (VariantAssignments.teamId eq actor.teamId) and
                (VariantAssignments.kind eq VariantKind.MCP.name) and (VariantAssignments.target eq server.name) and (VariantAssignments.variantId eq variantId) and
                (VariantAssignments.assignedAt eq server.assignedAt) and (Variants.version eq server.version)
        }.empty()
        val excluded = !VariantExclusions.select(VariantExclusions.memberId).where {
            (VariantExclusions.memberId eq actor.member.id) and (VariantExclusions.kind eq VariantKind.MCP.name) and (VariantExclusions.target eq server.name)
        }.empty()
        if (!assigned || excluded) throw denied()
        if (operation == McpOperation.CALL) {
            val policy = McpToolPolicies.selectAll().where { (McpToolPolicies.variantId eq variantId) and (McpToolPolicies.teamId eq actor.teamId) }
                .singleOrNull()?.let(::policy) ?: McpToolPolicy.ALL
            if (tool == null || !policy.allows(tool)) throw denied()
        }
        val window = now - maxOf(limits.leaseMillis(McpOperation.CALL), limits.leaseMillis(McpOperation.LIST))
        val member = load((McpCalls.memberId eq actor.member.id) and (McpCalls.server eq server.name), window, now)
        if (member.active >= limits.memberConcurrentPerServer) throw TeamFailure("mcp_member_concurrency", 429, "You already have the maximum number of running calls on this MCP server.")
        if (member.recent >= limits.memberCallsPerMinute) throw TeamFailure("mcp_member_rate", 429, "You reached the per-minute call limit for this MCP server. Retry shortly.")
        val team = load((McpCalls.teamId eq actor.teamId) and (McpCalls.server eq server.name), window, now)
        if (team.active >= limits.serverConcurrent) throw TeamFailure("mcp_server_concurrency", 429, "This MCP server is busy for the team. Retry shortly.")
        if (team.recent >= limits.serverCallsPerMinute) throw TeamFailure("mcp_server_rate", 429, "The team reached the per-minute call limit for this MCP server. Retry shortly.")
        val id = McpCalls.insert {
            it[teamId] = actor.teamId; it[memberId] = actor.member.id; it[McpCalls.server] = server.name; it[McpCalls.variantId] = variantId
            it[McpCalls.operation] = operation.name; it[McpCalls.tool] = tool; it[outcome] = McpOutcome.ACTIVE.name
            it[startedAt] = now; it[leaseExpiresAt] = now + limits.leaseMillis(operation)
        }[McpCalls.id]
        McpLease(id, actor.teamId, actor.member.id, server.name)
    }

    override suspend fun finish(lease: McpLease, outcome: McpOutcome, durationMillis: Long, responseBytes: Long) {
        require(outcome != McpOutcome.ACTIVE)
        database.query {
            McpCalls.update({ (McpCalls.id eq lease.id) and (McpCalls.teamId eq lease.teamId) and (McpCalls.outcome eq McpOutcome.ACTIVE.name) }) {
                it[McpCalls.outcome] = outcome.name; it[McpCalls.durationMillis] = durationMillis.coerceAtLeast(0)
                it[McpCalls.responseBytes] = responseBytes.coerceAtLeast(0)
            }
        }
    }

    /** No team lock: a flood of refused calls must not queue behind admissions and administrator writes. The bound is approximate under races. */
    override suspend fun denied(actor: Principal, server: String, tool: String?, limits: McpLimits) = database.query {
        val now = nowMillis()
        requireCurrent(this, actor, now)
        val recent = McpCalls.select(McpCalls.id).where {
            (McpCalls.memberId eq actor.member.id) and (McpCalls.server eq server) and (McpCalls.startedAt greater now - RATE_WINDOW_MILLIS)
        }.count()
        if (recent < limits.memberCallsPerMinute) McpCalls.insert {
            it[teamId] = actor.teamId; it[memberId] = actor.member.id; it[McpCalls.server] = server
            it[operation] = McpOperation.CALL.name; it[McpCalls.tool] = tool; it[outcome] = McpOutcome.DENIED.name
            it[startedAt] = now; it[leaseExpiresAt] = now; it[durationMillis] = 0; it[responseBytes] = 0
        }
        Unit
    }

    override suspend fun toolPolicy(actor: Principal, variantId: String): McpToolPolicy = database.query {
        actor.requireAdmin(); requireCurrent(this, actor)
        requireMcpVariant(actor.teamId, variantId, lock = false)
        McpToolPolicies.selectAll().where { (McpToolPolicies.variantId eq variantId) and (McpToolPolicies.teamId eq actor.teamId) }
            .singleOrNull()?.let(::policy) ?: McpToolPolicy.ALL
    }

    override suspend fun setToolPolicy(actor: Principal, variantId: String, policy: McpToolPolicy): McpToolPolicy {
        McpToolPolicy.validate(policy)
        return database.query {
            actor.requireAdmin(); lockTeam(actor.teamId); requireCurrent(this, actor)
            requireMcpVariant(actor.teamId, variantId, lock = true)
            McpToolPolicies.deleteWhere { (McpToolPolicies.variantId eq variantId) and (teamId eq actor.teamId) }
            if (policy.mode == McpToolMode.ALLOWLIST) McpToolPolicies.insert {
                it[McpToolPolicies.variantId] = variantId; it[teamId] = actor.teamId; it[mode] = policy.mode.name
                it[tools] = Json.encodeToString(policy.tools.sorted()); it[updatedAt] = nowMillis()
            }
            audit(actor.teamId, actor.member.id, "mcp.tool_policy_updated", variantId)
            policy
        }
    }

    override suspend fun usage(actor: Principal, days: Int): List<McpUsage> = database.query {
        McpAccessStore.validateDays(days)
        actor.requireScope(ServiceScope.USAGE_READ)
        val now = nowMillis()
        requireCurrent(this, actor, now)
        val sinceMillis = now - days * 24L * 60 * 60 * 1000
        val everyone = actor.serviceScopes == null && actor.member.role.isAdministrator
        val count = McpCalls.id.count()
        val bytes = McpCalls.responseBytes.sum()
        val duration = McpCalls.durationMillis.sum()
        val rows = McpCalls.select(McpCalls.memberId, McpCalls.server, McpCalls.operation, McpCalls.outcome, count, bytes, duration).where {
            (McpCalls.teamId eq actor.teamId) and (McpCalls.startedAt greaterEq sinceMillis) and (if (everyone) Op.TRUE else McpCalls.memberId eq actor.member.id)
        }.groupBy(McpCalls.memberId, McpCalls.server, McpCalls.operation, McpCalls.outcome).toList()
        val names = Members.select(Members.id, Members.username).where { (Members.teamId eq actor.teamId) and (Members.id inList rows.map { it[McpCalls.memberId] }.distinct()) }
            .associate { it[Members.id] to it[Members.username] }
        rows.groupBy { it[McpCalls.memberId] to it[McpCalls.server] }.map { (key, group) ->
            fun total(filter: (ResultRow) -> Boolean) = group.filter(filter).sumOf { it[count] }
            McpUsage(key.first, names[key.first].orEmpty(), key.second,
                calls = total { it[McpCalls.operation] == McpOperation.CALL.name && it[McpCalls.outcome] != McpOutcome.DENIED.name },
                lists = total { it[McpCalls.operation] == McpOperation.LIST.name },
                errors = total { it[McpCalls.outcome] in ERROR_OUTCOMES },
                denied = total { it[McpCalls.outcome] == McpOutcome.DENIED.name },
                responseBytes = group.sumOf { it[bytes] ?: 0L }, totalDurationMillis = group.sumOf { it[duration] ?: 0L })
        }.sortedWith(compareBy({ it.username }, { it.server }))
    }

    override suspend fun calls(actor: Principal, beforeId: Long?): McpCallPage = database.query {
        actor.requireAdmin(); requireCurrent(this, actor)
        val rows = McpCalls.selectAll().where { (McpCalls.teamId eq actor.teamId) and (beforeId?.let { McpCalls.id less it } ?: Op.TRUE) }
            .orderBy(McpCalls.id to SortOrder.DESC).limit(McpAccessStore.PAGE_SIZE + 1).map { row ->
                McpCallRecord(row[McpCalls.id], row[McpCalls.memberId], row[McpCalls.server], row[McpCalls.variantId], McpOperation.valueOf(row[McpCalls.operation]),
                    row[McpCalls.tool], McpOutcome.valueOf(row[McpCalls.outcome]), row[McpCalls.startedAt], row[McpCalls.durationMillis], row[McpCalls.responseBytes])
            }
        val page = rows.take(McpAccessStore.PAGE_SIZE)
        McpCallPage(page, if (rows.size > McpAccessStore.PAGE_SIZE) page.last().id else null)
    }

    override suspend fun maintain() = database.query {
        val cutoff = nowMillis() - McpAccessStore.RETENTION_MILLIS
        McpCalls.deleteWhere(limit = 10_000) { startedAt less cutoff }
        Unit
    }

    private class Load(val active: Long, val recent: Long)
    /** Two indexed counts; [scope] matches an index prefix ending before `started_at`. */
    private fun load(scope: Op<Boolean>, leaseWindow: Long, now: Long) = Load(
        McpCalls.select(McpCalls.id).where { scope and (McpCalls.startedAt greater leaseWindow) and (McpCalls.outcome eq McpOutcome.ACTIVE.name) and (McpCalls.leaseExpiresAt greater now) }.count(),
        McpCalls.select(McpCalls.id).where { scope and (McpCalls.startedAt greater now - RATE_WINDOW_MILLIS) }.count(),
    )
    private fun requireMcpVariant(team: String, id: String, lock: Boolean) {
        val query = Variants.select(Variants.kind).where { (Variants.id eq id) and (Variants.teamId eq team) }
        if (lock) query.forUpdate()
        val kind = query.singleOrNull()?.get(Variants.kind) ?: throw TeamFailure("not_found", 404, "Variant not found.")
        if (kind != VariantKind.MCP.name) throw TeamFailure("invalid_tool_policy", 400, "Tool policies apply to MCP variants only.")
    }
    private fun policy(row: ResultRow) = McpToolPolicy(McpToolMode.valueOf(row[McpToolPolicies.mode]), Json.decodeFromString<List<String>>(row[McpToolPolicies.tools]).toSet())
    private fun denied() = TeamFailure("mcp_tool_denied", 403, "Unknown tool.")

    private companion object {
        const val RATE_WINDOW_MILLIS = 60_000L
        val ERROR_OUTCOMES = setOf(McpOutcome.TOOL_ERROR, McpOutcome.UPSTREAM_ERROR, McpOutcome.TIMEOUT, McpOutcome.TOO_LARGE).map { it.name }.toSet()
    }
}
