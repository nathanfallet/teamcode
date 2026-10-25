package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.policy.CheckModelPolicyUseCase
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Admission is serialized per team; the member's slot and the chosen account are reserved atomically. */
class SqlAdmissionStore(private val database: Database) : AdmissionStore, AdmissionQueueStore {
    private val affinities = SqlSessionAffinity()
    private val queue = SqlAdmissionQueue(database)
    override suspend fun enqueue(actor: Principal, input: AdmissionInput) = queue.enqueue(actor, input)
    override suspend fun reserveQueued(actor: Principal, ticket: AdmissionTicket, input: AdmissionInput) =
        reserveNow(actor, input.units, input.model, input.provider, input.excludedAccountIds, input.affinity, ticket, input.context, input.runId, input.workload)
    override suspend fun renew(actor: Principal, ticket: AdmissionTicket, blocked: Boolean) = queue.renew(actor, ticket, blocked)
    override suspend fun renew(actor: Principal, ticket: AdmissionTicket, blocked: Boolean, input: AdmissionInput) = queue.renew(actor, ticket, blocked, input)
    override suspend fun release(actor: Principal, ticket: AdmissionTicket) = queue.release(actor, ticket)
    override suspend fun preferredTarget(actor: Principal, affinity: RequestAffinity): RouteTarget? = database.query {
        actor.requireScope(ServiceScope.INFERENCE); requireCurrent(this, actor)
        affinities.target(this, actor, affinity, nowMillis())
    }
    override suspend fun statistics(): AdmissionStatistics = database.query {
        val now = nowMillis()
        val active = Reservations.selectAll().where { (Reservations.teamId eq "default") and (Reservations.state eq "ACTIVE") and (Reservations.expiresAt greater now) }.count().toInt()
        val completed = GatewayTotals.select(GatewayTotals.completed).where { GatewayTotals.teamId eq "default" }.single()[GatewayTotals.completed]
        val queued = AdmissionWaiters.selectAll().where { (AdmissionWaiters.teamId eq "default") and (AdmissionWaiters.expiresAt greater now) }.count().toInt()
        AdmissionStatistics(active, completed, queued)
    }
    override suspend fun reserve(actor: Principal, units: Long, model: String, provider: String, excludedAccountIds: Set<String>, affinity: RequestAffinity?): Reservation =
        reserveNow(actor, units, model, provider, excludedAccountIds, affinity, null)
    override suspend fun reserve(actor: Principal, input: AdmissionInput): Reservation =
        reserveNow(actor, input.units, input.model, input.provider, input.excludedAccountIds, input.affinity, null, input.context, input.runId, input.workload)
    private suspend fun reserveNow(actor: Principal, units: Long, model: String, provider: String, excludedAccountIds: Set<String>, affinity: RequestAffinity?, ticket: AdmissionTicket?, context: AdmissionContext? = null, runId: String? = null, workload: Workload? = null): Reservation = database.query {
        actor.requireScope(ServiceScope.INFERENCE)
        require(units in 1..100_000_000 && excludedAccountIds.size <= 64)
        lockTeam(actor.teamId)
        val now = nowMillis()
        requireCurrent(this, actor)
        context?.let {
            val modelPolicy = readModelPolicy(actor.teamId)
            CheckModelPolicyUseCase().check(modelPolicy, it.requestedModel, model, it.effort, it.thinkingTokens)
            if (it.translated) {
                CheckModelPolicyUseCase().checkTranslation(modelPolicy)
                CheckModelPolicyUseCase().check(modelPolicy, it.requestedModel, model, it.targetEffort, null)
            }
            if (ResolveRouteUseCase().resolve(readRoutes(actor.teamId), it.requestedModel, it.protocol) != it.targets) {
                throw TeamFailure("route_changed", 409, "The configured route changed while waiting. Submit the request again.", retryable = true)
            }
        }
        val policy = readMemberPolicy(this, actor.member.id)
        val current = readMemberCapacity(this, policy, now, actor.teamId)
        CheckGroupAdmissionUseCase().check(current.groups, units, now)
        runId?.let { requireRunAdmission(actor, it, units, now, workload) }
        val scope = affinity?.sessionKey.orEmpty()
        val table = if (scope.isEmpty()) Directives else SessionDirectives
        val directive = table.selectAll().where { (table.memberId eq actor.member.id) and (table.scopeKey eq scope) and (table.expiresAt greater now) }.singleOrNull()
        if (policy.maxConcurrent > 0 && current.activeRequests >= policy.maxConcurrent) throw TeamFailure("member_concurrency", 429, "You already have ${policy.maxConcurrent} TeamCode requests running, your concurrent limit. This one can start when one of them finishes.", retryAfterSeconds = RetryWindows.SHORT_RETRY_SECONDS)
        if (directive != null && directive[table.minInterval] > 0 && !readPacingSettings(actor.teamId).observationOnly) {
            val session = directive[table.scopeKey].takeIf { it.isNotEmpty() }
            val sessionFilter = session?.let { Reservations.sessionKey eq it } ?: Op.TRUE
            // The day's newest charged admission: a reservation's day is its creation time's, so the day starts the range.
            val last = Reservations.select(Reservations.createdAt).where {
                (Reservations.memberId eq actor.member.id) and (Reservations.createdAt greaterEq now / DAY * DAY) and (Reservations.units greater 0) and sessionFilter
            }.orderBy(Reservations.createdAt to SortOrder.DESC).limit(1).singleOrNull()?.get(Reservations.createdAt) ?: 0L
            if (now - last < directive[table.minInterval]) throw TeamFailure("pacing_delay", 429, "TeamCode is spacing out your requests because the pool's subscription quota is burning fast. Retry in a few seconds.", retryAfterSeconds = RetryWindows.secondsUntil(now, last + directive[table.minInterval]))
            // Pacing only spaces admissions: a directive's concurrency (stored by older replicas) is never a ceiling.
        }
        if (units > current.dailyAllowanceUnits - current.chargedAndReservedUnits) throw RetryWindows.dailyBudget("member_allowance", "Your daily TeamCode allowance", "An administrator can raise it or grant you a temporary budget override.", now, heldByRunningRequests = current.activeRequests > 0)
        val spent = poolSpent(actor.teamId, policy.poolId.orEmpty(), now / DAY)
        if (units > current.poolDailyUnits - spent) throw RetryWindows.dailyBudget("pool_allowance", "The daily budget of your TeamCode pool", "An administrator can raise the pool budget.", now,
            heldByRunningRequests = Reservations.selectAll().where { (Reservations.teamId eq actor.teamId) and (Reservations.state eq "ACTIVE") and
                (Reservations.expiresAt greater now) and ((Reservations.poolId eq policy.poolId.orEmpty()) or (Reservations.sourcePoolId eq policy.poolId.orEmpty())) }.limit(1).any())
        val placement = accountPlacement(actor, policy, AdmissionInput(units, model, provider, excludedAccountIds, affinity), now)
        val chosen = placement.chosen ?: throw DescribePoolUnavailableUseCase().failure(placement.standing ?: PoolStanding(provider), now)
        ticket?.let { consumeQueueTurn(actor, it, policy, provider, now, runId, chosen.poolId.orEmpty()) }
        affinity?.let { affinities.record(this, actor, it, RouteTarget(provider, model), chosen.id, now) }
        val id = UUID.randomUUID().toString()
        Reservations.insert {
            it[Reservations.id] = id; it[teamId] = actor.teamId; it[memberId] = actor.member.id; it[accountId] = chosen.id
            it[dayNumber] = now / DAY; it[Reservations.units] = units; it[state] = "ACTIVE"; it[expiresAt] = now + 15 * 60_000
            it[createdAt] = now; it[poolId] = policy.poolId.orEmpty(); it[sessionKey] = affinity?.sessionKey
            it[Reservations.runId] = runId; it[sourcePoolId] = chosen.poolId.orEmpty()
        }
        current.groups.forEach { group -> ReservationGroups.insert {
            it[reservationId] = id; it[groupId] = group.group.id; it[dayNumber] = now / DAY; it[expiresAt] = now + 15 * 60_000
        } }
        addDailyUnits(actor.teamId, now / DAY, actor.member.id, policy.poolId.orEmpty(), chosen.poolId.orEmpty(), current.groups.map { it.group.id }, units)
        runId?.let { recordRunAdmission(it, units, affinity, provider, model, now) }
        Reservation(id, chosen, units)
    }
    override suspend fun recordProviderRequest(reservation: Reservation, requestId: String) {
        require(ProviderRequestIds.valid(requestId))
        database.query {
            lockTeam(reservation.account.identity.teamId)
            Reservations.update({ (Reservations.id eq reservation.id) and (Reservations.teamId eq reservation.account.identity.teamId) and
                (Reservations.accountId eq reservation.account.id) and (Reservations.state eq "ACTIVE") }) {
                it[providerRequestId] = requestId
            }
        }
    }
    override suspend fun settle(reservation: Reservation, actualUnits: Long?, completed: Boolean) = settle(reservation, actualUnits, completed, null)
    override suspend fun settle(reservation: Reservation, actualUnits: Long?, completed: Boolean, usage: MeteredUsage?) {
        require(actualUnits == null || actualUnits in 0..100_000_000)
        val winner = database.query {
            lockTeam(reservation.account.identity.teamId)
            val before = Reservations.select(Reservations.units, Reservations.runId, Reservations.teamId, Reservations.memberId, Reservations.accountId, Reservations.dayNumber,
                Reservations.poolId, Reservations.sourcePoolId)
                .where { Reservations.id eq reservation.id }.singleOrNull()
            val changed = Reservations.update({ (Reservations.id eq reservation.id) and (Reservations.state eq "ACTIVE") }) {
                it[state] = if (actualUnits == null) "UNCERTAIN" else "SETTLED"; it[units] = actualUnits ?: reservation.units
            }
            if (changed == 1 && before != null) before[Reservations.runId]?.let { run ->
                AgentRuns.update({ AgentRuns.id eq run }) {
                    it[chargedUnits] = chargedUnits + ((actualUnits ?: reservation.units) - before[Reservations.units])
                    if (completed) it[AgentRuns.completed] = AgentRuns.completed + 1
                }
            }
            if (changed == 1 && before != null) {
                val delta = (actualUnits ?: reservation.units) - before[Reservations.units]
                if (delta != 0L) addDailyUnits(before[Reservations.teamId], before[Reservations.dayNumber], before[Reservations.memberId], before[Reservations.poolId],
                    before[Reservations.sourcePoolId], reservationGroupIds(reservation.id), delta)
            }
            if (changed == 1 && completed) GatewayTotals.update({ GatewayTotals.teamId eq reservation.account.identity.teamId }) { it[GatewayTotals.completed] = GatewayTotals.completed + 1 }
            before.takeIf { changed == 1 }
        }
        // Only the settlement that won records tokens, so they are never counted twice. A separate transaction keeps a
        // ledger failure from rolling back the credits; a crash in between loses the tokens, never the charge.
        if (winner != null && usage != null) try {
            database.query {
                lockTeam(winner[Reservations.teamId])
                // The provider meters the account when the request ends.
                recordTokenUsage(winner[Reservations.teamId], winner[Reservations.memberId], winner[Reservations.accountId], winner[Reservations.dayNumber],
                    nowMillis() / 3_600_000, winner[Reservations.runId], usage)
            }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { System.err.println("TeamCode token ledger write failed request=${reservation.id}") }
    }
    override suspend fun tokenUsage(actor: Principal, query: TokenUsageQuery): TokenUsagePage = database.query { readTokenUsage(actor, query) }
    override suspend fun capacity(actor: Principal, memberId: String): MemberCapacity = database.query {
        actor.requireUsageRead(); requireCurrent(this, actor)
        if (memberId != actor.member.id) actor.requireTeamRead()
        ensureMember(actor.teamId, memberId)
        readMemberCapacity(this, readMemberPolicy(this, memberId), nowMillis(), actor.teamId)
    }
    override suspend fun capacities(actor: Principal, memberIds: List<String>): List<MemberCapacity> = database.query {
        actor.requireUsageRead(); requireCurrent(this, actor)
        if (memberIds.any { it != actor.member.id }) actor.requireTeamRead()
        val now = nowMillis()
        val cache = CapacityReadCache()
        memberIds.map { memberId ->
            ensureMember(actor.teamId, memberId)
            readMemberCapacity(this, readMemberPolicy(this, memberId), now, actor.teamId, cache)
        }
    }
    override suspend fun configure(actor: Principal, policy: MemberPolicy): MemberPolicy = database.query {
        actor.requireAdmin()
        if (policy.dailyUnits !in 0..100_000_000_000 || policy.weight !in 0..1000 || policy.maxConcurrent !in 0..MAX_MEMBER_CONCURRENCY || policy.poolId?.let { !Regex("[a-zA-Z0-9_-]{1,36}").matches(it) } == true) throw TeamFailure("invalid_policy", 400, "Invalid daily allowance, concurrency limit or pool identifier.")
        lockTeam(actor.teamId); requireCurrent(this, actor); ensureMember(actor.teamId, policy.memberId)
        if (PoolBudgets.select(PoolBudgets.poolId).where { (PoolBudgets.teamId eq actor.teamId) and (PoolBudgets.poolId eq policy.poolId.orEmpty()) and (PoolBudgets.sharedOverflow eq true) }.any())
            throw TeamFailure("overflow_primary_pool", 409, "A shared overflow reserve cannot be a member's primary pool.")
        val previous = readMemberPolicy(this, policy.memberId)
        if (previous.poolId != policy.poolId) readActiveBudgetOverride(actor.teamId, previous, nowMillis())?.let {
            revokeBudgetOverride(actor, policy.memberId, it.id, nowMillis(), "Member pool changed.", "budget_override.pool_changed")
        }
        MemberPolicies.upsert {
            it[memberId] = policy.memberId; it[dailyUnits] = policy.dailyUnits; it[maxConcurrent] = policy.maxConcurrent; it[poolId] = policy.poolId; it[weight] = policy.weight; it[allowSharedOverflow] = policy.allowSharedOverflow
        }
        audit(actor.teamId, actor.member.id, "policy.updated", policy.memberId)
        policy
    }
    override suspend fun poolBudgets(actor: Principal): List<PoolBudget> = database.query {
        actor.requireTeamRead(); requireCurrent(this, actor)
        PoolBudgets.select(PoolBudgets.poolId, PoolBudgets.dailyUnits, PoolBudgets.sharedOverflow).where { PoolBudgets.teamId eq actor.teamId }.orderBy(PoolBudgets.poolId).map { PoolBudget(it[PoolBudgets.poolId], it[PoolBudgets.dailyUnits], it[PoolBudgets.sharedOverflow]) }
    }
    override suspend fun configurePool(actor: Principal, budget: PoolBudget): PoolBudget = database.query {
        actor.requireAdmin()
        if (budget.dailyUnits !in 1..100_000_000_000 || !Regex("[a-zA-Z0-9_-]{0,36}").matches(budget.poolId)) throw TeamFailure("invalid_pool_budget", 400, "Invalid pool budget or identifier.")
        lockTeam(actor.teamId); requireCurrent(this, actor)
        if (budget.sharedOverflow) {
            if (budget.poolId.isEmpty()) throw TeamFailure("invalid_overflow_pool", 400, "Shared overflow requires a named pool.")
            if ((MemberPolicies innerJoin Members).select(MemberPolicies.memberId).where {
                (Members.teamId eq actor.teamId) and (MemberPolicies.poolId eq budget.poolId)
            }.any()) throw TeamFailure("overflow_primary_pool", 409, "Remove every primary member assignment before sharing this reserve.")
            val existing = sharedOverflowBudgets(actor.teamId)
            if (budget.poolId !in existing && existing.size >= 16) throw TeamFailure("overflow_pool_limit", 409, "At most 16 shared overflow pools are allowed.")
        }
        PoolBudgets.upsert { it[teamId] = actor.teamId; it[poolId] = budget.poolId; it[dailyUnits] = budget.dailyUnits; it[sharedOverflow] = budget.sharedOverflow }
        audit(actor.teamId, actor.member.id, "pool_budget.updated", actor.teamId)
        budget
    }
    override suspend fun quotas(actor: Principal): List<AccountQuota> = database.query {
        actor.requireTeamRead(); requireCurrent(this, actor)
        (QuotaWindows innerJoin Accounts).select(QuotaWindows.columns).where { Accounts.teamId eq actor.teamId }.map(::quotaRecord)
    }
    override suspend fun poolAccounts(actor: Principal): List<PoolAccountStatus> = database.query {
        actor.requireUsageRead(); requireCurrent(this, actor)
        val now = nowMillis()
        // Interactive administrators and `team:read` keys; any other key acts as its owner's member view, like every admin read.
        val admin = actor.canReadTeam
        val visible = if (admin) Op.TRUE else {
            val policy = readMemberPolicy(this, actor.member.id)
            val overflow = if (policy.allowSharedOverflow) sharedOverflowBudgets(actor.teamId).keys else emptySet()
            (Accounts.poolId eq policy.poolId) or (Accounts.poolId inList overflow)
        }
        val rows = Accounts.selectAll().where { (Accounts.teamId eq actor.teamId) and visible }
            .orderBy(Accounts.nextResetAt to SortOrder.ASC, Accounts.id to SortOrder.ASC).limit(POOL_ACCOUNTS_LIMIT)
            .map { accountRecord(it) to it[Accounts.cooldownUntil] }
        val ids = rows.map { it.first.id }
        val windows = if (ids.isEmpty()) emptyList() else QuotaWindows.selectAll().where { QuotaWindows.accountId inList ids }.map(::quotaRecord)
        DescribePoolAccountsUseCase().describe(rows.map { (account, cooldown) -> PoolAccountObservation(account, cooldown, windows.filter { it.accountId == account.id }) }, now, admin,
            readPacingSettings(actor.teamId).quotaPressureThreshold)
    }
    override suspend fun observe(accountId: String, observations: List<AccountQuota>) = observe(accountId, QuotaUpdate(observations, System.currentTimeMillis()))
    override suspend fun observe(accountId: String, update: QuotaUpdate) = SqlQuotaObservations(database).observe(accountId, update)
    override suspend fun isQuotaExhausted(accountId: String, model: String): Boolean = database.query {
        val team = Accounts.select(Accounts.teamId).where { Accounts.id eq accountId }.singleOrNull()?.get(Accounts.teamId) ?: return@query false
        lockTeam(team)
        val now = nowMillis()
        val limit = readQuotaModelLimits(team)[accountId]?.get(model.lowercase())
        val pressureThreshold = readPacingSettings(team).quotaPressureThreshold
        QuotaWindows.selectAll().where { QuotaWindows.accountId eq accountId }.map(::quotaRecord)
            .any { it.isCurrent(now) && it.appliesTo(model, limit) && it.utilization >= pressureThreshold }
    }
    override suspend fun coolDown(accountId: String, untilMillis: Long, reconnect: Boolean) {
        database.query {
            Accounts.update({ Accounts.id eq accountId }) {
                it[cooldownUntil] = Case().When(Accounts.cooldownUntil less untilMillis, longLiteral(untilMillis)).Else(Accounts.cooldownUntil)
                if (reconnect) it[state] = "RECONNECT_REQUIRED"
            }
        }
    }
    suspend fun maintain() {
        database.query {
            val now = nowMillis()
            AdmissionWaiters.deleteWhere(limit = 1000) { expiresAt lessEq now }
            ConversationAffinities.deleteWhere(limit = 1000) { expiresAt lessEq now }
            // A reset that has passed no longer describes the account: recompute it, so unobserved accounts do not stay first.
            Accounts.select(Accounts.id).where { Accounts.nextResetAt less now }.limit(1000).map { it[Accounts.id] }.forEach { refreshNextReset(it, now) }
            QuotaHistory.deleteWhere(limit = 1000) { observedAt less now - 7 * DAY }
            // Unknown charges must remain reviewable, including after an expired process lease.
            Reservations.deleteWhere(limit = 1000) { (dayNumber less now / DAY - 90) and (state inList listOf("SETTLED", "RECONCILED")) }
            // Admission reads only the current day; earlier totals are kept as long as their reservations.
            ReservationDailyUnits.deleteWhere(limit = 1000) { dayNumber less now / DAY - 90 }
            AgentRuns.deleteWhere(limit = 1000) {
                (heartbeatAt less now - 90 * DAY) and (lastRequestAt.isNull() or (lastRequestAt less now - 90 * DAY)) and
                    (endedAt.isNull() or (endedAt less now - 90 * DAY)) and
                    notExists(Reservations.select(Reservations.id).where { Reservations.runId eq AgentRuns.id }) and
                    notExists(AdmissionWaiters.select(AdmissionWaiters.id).where { AdmissionWaiters.runId eq AgentRuns.id })
            }
        }
    }
    private fun ensureMember(team: String, member: String) {
        if (Members.select(Members.id).where { (Members.teamId eq team) and (Members.id eq member) }.empty()) throw TeamFailure("not_found", 404, "Member not found.")
    }
    companion object { private const val DAY = 86_400_000L }
}
internal fun quotaRecord(row: ResultRow) = AccountQuota(row[QuotaWindows.accountId], row[QuotaWindows.windowName], row[QuotaWindows.utilization], row[QuotaWindows.observedAt], row[QuotaWindows.resetsAt])

private const val POOL_ACCOUNTS_LIMIT = 200
