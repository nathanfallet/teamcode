package me.nathanfallet.teamcode.infrastructure.database

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import java.security.MessageDigest
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.policy.CheckModelPolicyUseCase
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.infrastructure.routing.routeFingerprint
import java.util.UUID
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*

class SqlDirectiveService(private val database: Database) : DirectiveService {
    private val delivery = SqlDirectiveDelivery(database)
    private val history = ForecastHistoryUseCase()
    private val pacing = RoutePacingUseCase()
    private val hysteresis = PacingHysteresisUseCase()
    private val observedConcurrency = ObservedConcurrencyUseCase()
    suspend fun update() {
        database.query {
            val c = this
            c.lockTeam("default")
            val now = c.nowMillis()
            val accounts = Accounts.selectAll().where {
                (Accounts.teamId eq "default") and (Accounts.disabled eq false) and (Accounts.state inList listOf("READY", "ACCESS_ONLY")) and (Accounts.cooldownUntil lessEq now)
            }.map(::accountRecord)
            val modelLimits = readQuotaModelLimits("default")
            val samples = QuotaHistory.join(QuotaWindows, JoinType.INNER, additionalConstraint = {
                (QuotaWindows.accountId eq QuotaHistory.accountId) and (QuotaWindows.windowName eq QuotaHistory.windowName) and (QuotaHistory.observedAt greaterEq QuotaWindows.windowStartedAt)
            }).join(Accounts, JoinType.INNER, additionalConstraint = { Accounts.id eq QuotaHistory.accountId })
                .select(QuotaHistory.columns).where { (Accounts.teamId eq "default") and (QuotaHistory.observedAt greaterEq now - 3_600_000) }
                .orderBy(QuotaHistory.observedAt to SortOrder.DESC).limit(10000).map {
                    AccountQuota(it[QuotaHistory.accountId], it[QuotaHistory.windowName], it[QuotaHistory.utilization], it[QuotaHistory.observedAt], it[QuotaHistory.resetsAt])
                }.groupBy { it.accountId to it.window }.mapValues { history.evaluate(it.value, now) }
            val members = (Members leftJoin MemberPolicies).select(Members.id, MemberPolicies.maxConcurrent, MemberPolicies.poolId)
                .where { (Members.teamId eq "default") and (Members.disabled eq false) }.map {
                    Triple(it[Members.id], it.getOrNull(MemberPolicies.maxConcurrent) ?: UNLIMITED_CONCURRENCY, it[MemberPolicies.poolId])
                }
            val routes = readRoutes("default")
            val modelPolicy = readModelPolicy("default")
            val contexts = ConversationAffinities.selectAll().where {
                (ConversationAffinities.teamId eq "default") and (ConversationAffinities.lastSeenAt greater now - SqlSessionAffinity.ACTIVE_TTL) and (ConversationAffinities.expiresAt greater now)
            }.map { row -> with(ConversationAffinities) {
                row[memberId] to Context(row[sessionKey], row[requestedModel], RouteTarget(row[provider], row[resolvedModel]), row[routeKey], row[wireProtocol], row[requestedEffort], row[thinkingTokens], row[accountId])
            } }.groupBy({ it.first }, { it.second })
            // One grouped read per pass sizes every session's observed parallelism.
            val activeCount = Reservations.id.count()
            val activeBySession = Reservations.select(Reservations.memberId, Reservations.sessionKey, activeCount).where {
                (Reservations.teamId eq "default") and (Reservations.state eq "ACTIVE") and (Reservations.expiresAt greater now) and Reservations.sessionKey.isNotNull()
            }.groupBy(Reservations.memberId, Reservations.sessionKey).associate { (it[Reservations.memberId] to it[Reservations.sessionKey].orEmpty()) to it[activeCount].toInt() }
            val overflowBudgets = sharedOverflowBudgets("default")
            val overflowSpends = poolSpends("default", overflowBudgets.keys, now / 86_400_000L)
            val overflowPools = overflowBudgets.filter { (pool, budget) -> (overflowSpends[pool] ?: 0L) < budget }.keys
            members.forEach { (member, maximum, pool) ->
                val memberContexts = contexts[member].orEmpty()
                val scopes = mapOf("" to memberContexts) + memberContexts.groupBy { it.sessionKey }
                val policy = readMemberPolicy(c, member)
                val accessible = accounts.filter { it.poolId == pool || (policy.allowSharedOverflow && it.poolId in overflowPools) }
                val accountPacing = accessible.map { account -> AccountPacing(account, samples.filterKeys { it.first == account.id }.values.filterNotNull(), modelLimits[account.id].orEmpty()) }
                val capacity = readMemberCapacity(c, policy, now, "default")
                val poolSpent = poolSpent("default", pool.orEmpty(), now / 86_400_000)
                val allowanceAvailable = capacity.dailyAllowanceUnits > capacity.chargedAndReservedUnits && capacity.poolDailyUnits > poolSpent && capacity.groups.all { it.budgetAvailable }
                val groupMaximum = CheckGroupAdmissionUseCase().maximum(capacity.groups, maximum)
                val runPolicies = readRunPacingPolicies("default", member, now)
                scopes.forEach { (scopeKey, scoped) ->
                    val runLimits = CheckRunPacingUseCase().limits(runPolicies.scopes[scopeKey].orEmpty(), groupMaximum)
                    val scopedAllowanceAvailable = allowanceAvailable && runLimits.allowanceAvailable
                    val table = if (scopeKey.isEmpty()) Directives else SessionDirectives
                    val active = scoped.distinct()
                    val valid = active.all { context ->
                        val protocol = WireProtocol.entries.firstOrNull { it.name == context.protocol }
                        if (protocol == null) false else {
                            val targets = ResolveRouteUseCase().resolve(routes, context.requested, protocol)
                            val permitted = try {
                                val nativeProvider = if (protocol == WireProtocol.MESSAGES) "anthropic" else "codex"
                                if (context.target.provider != nativeProvider) CheckModelPolicyUseCase().checkTranslation(modelPolicy)
                                CheckModelPolicyUseCase().check(modelPolicy, context.requested, context.target.model!!, context.effort, context.thinkingTokens)
                                true
                            } catch (_: TeamFailure) { false }
                            permitted && context.target in targets && context.routeKey == routeFingerprint(protocol, context.requested, targets)
                        }
                    }
                    val targets = if (valid) active.map { it.target }.toSet() else emptySet()
                    val previous = table.selectAll().where { (table.memberId eq member) and (table.scopeKey eq scopeKey) }.singleOrNull()?.let { row ->
                        val old = directiveRecord(row, table)
                        val recovery = row[table.recoveryPace]?.let { PacingRecovery(Pace.valueOf(it), row[table.recoveryStartedAt] ?: 0L, row[table.recoveryMinInterval] ?: 0L) }
                        Stored(old, row[table.contextKey], PacingMemory(old.advice, old.expiresAtMillis, recovery), row[table.observedConcurrency])
                    }
                    // The peak follows the scope's demand, not its capacity context, so a changed key keeps it.
                    val running = if (scopeKey.isEmpty()) capacity.activeRequests else activeBySession[member to scopeKey] ?: 0
                    val current = previous?.takeIf { it.directive.expiresAtMillis > now }
                    val observed = observedConcurrency.next(current?.observed, current?.directive?.advice, running)
                    val advice = if (runPolicies.truncated && scopedAllowanceAvailable) PacingAdvice(Pace.UNKNOWN, runLimits.maxConcurrent, 0,
                        "Recent launch/session metadata is incomplete. Do not increase parallelism from this signal; server-side limits remain authoritative.")
                        else pacing.advise(targets, accountPacing, runLimits.maxConcurrent, now, scopedAllowanceAvailable, pool, policy.allowSharedOverflow, active.groupBy { it.target }.mapValues { (_, contexts) -> contexts.map { it.accountId }.toSet() }, observed)
                    // A different route, policy, pool, priority or eligible account set must not
                    // inherit optimistic advice (or an old throttle) from another capacity context.
                    val scope = listOf(runLimits.maxConcurrent.toString(), pool.orEmpty(), policy.allowSharedOverflow.toString(), modelPolicy.toString(), valid.toString(), scopedAllowanceAvailable.toString(),
                        runLimits.contextKey, runPolicies.truncated.toString(), capacity.budgetOverride?.id.orEmpty(),
                        capacity.groups.map { "${it.group}:${it.paused}" }.sorted().joinToString("\n"),
                        active.map { it.toString() }.sorted().joinToString("\n"),
                        accessible.map { "${it.id}:${it.priority}" }.sorted().joinToString("\n")).joinToString("\n")
                    val key = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
                    val decision = hysteresis.decide(previous?.takeIf { it.key == key }?.memory, advice, now)
                    val chosen = decision.advice
                    val old = previous?.directive
                    val same = old?.advice == chosen && previous.key == key
                    val id = if (same) old.id else UUID.randomUUID().toString()
                    val created = if (same) old.createdAtMillis else now
                    val recovery = decision.recovery
                    table.upsert {
                        it[memberId] = member; it[table.scopeKey] = scopeKey; it[table.id] = id; it[pace] = chosen.pace.name; it[maxConcurrent] = chosen.maxConcurrent
                        it[minInterval] = chosen.minIntervalMillis; it[message] = chosen.message; it[createdAt] = created; it[expiresAt] = now + 180_000
                        it[contextKey] = key; it[recoveryPace] = recovery?.pace?.name; it[recoveryStartedAt] = recovery?.startedAtMillis
                        it[table.recoveryMinInterval] = recovery?.minIntervalMillis
                        it[table.observedConcurrency] = observed
                    }
                }
            }
            SessionDirectives.deleteWhere(limit = 1000) { expiresAt lessEq now }
            DirectiveDeliveries.deleteWhere(limit = 1000) {
                (updatedAt less now - 7 * 86_400_000) or notExists(SessionDirectives.select(SessionDirectives.memberId).where {
                    (SessionDirectives.memberId eq DirectiveDeliveries.memberId) and (SessionDirectives.scopeKey eq DirectiveDeliveries.sessionKey) and (SessionDirectives.expiresAt greater now)
                })
            }
            DirectiveReceipts.deleteWhere(limit = 1000) { receivedAt less now - 7 * 86_400_000 }
        }
    }
    override suspend fun preview(actor: Principal): AgentDirective? = delivery.preview(actor)
    override suspend fun current(actor: Principal, sessionId: String?): AgentDirective? = delivery.current(actor, sessionId)
    override suspend fun claim(actor: Principal, sessionId: String, stage: String): DirectiveDelivery? = delivery.claim(actor, sessionId, stage)
    override suspend fun acknowledge(actor: Principal, id: String, stage: String, sessionId: String?, claimId: String?) = delivery.acknowledge(actor, id, stage, sessionId, claimId)
    private data class Stored(val directive: AgentDirective, val key: String?, val memory: PacingMemory, val observed: Int?)
    private data class Context(val sessionKey: String, val requested: String, val target: RouteTarget, val routeKey: String, val protocol: String?, val effort: String?, val thinkingTokens: Long?, val accountId: String)

}

internal fun directiveRecord(row: ResultRow, table: DirectiveTable) = with(table) {
    AgentDirective(row[id], row[memberId], PacingAdvice(Pace.valueOf(row[pace]), row[maxConcurrent], row[minInterval], row[message]), row[createdAt], row[expiresAt])
}
