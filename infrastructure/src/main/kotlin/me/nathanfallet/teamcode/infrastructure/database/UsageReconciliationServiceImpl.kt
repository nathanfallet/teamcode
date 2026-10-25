package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Verified corrections share the admission/settlement lock and preserve original billing attribution. */
class UsageReconciliationServiceImpl(private val database: Database) : UsageReconciliationService {
    private val rules = CheckUsageReconciliationUseCase()
    override suspend fun pending(actor: Principal, memberId: String?, beforeId: String?): PendingUsagePage = database.query {
        authorize(this, actor); memberId?.let(::identifier)
        val scope = (Reservations.teamId eq actor.teamId) and (memberId?.let { Reservations.memberId eq it } ?: Op.TRUE)
        val cursor = beforeId?.let { identifier(it); Reservations.select(Reservations.id, Reservations.createdAt).where { scope and (Reservations.id eq it) }.singleOrNull()
            ?: throw invalidCursor() }
        val before = cursor?.let { (Reservations.createdAt less it[Reservations.createdAt]) or
            ((Reservations.createdAt eq it[Reservations.createdAt]) and (Reservations.id less it[Reservations.id])) } ?: Op.TRUE
        val now = nowMillis()
        // Separate indexed state ranges avoid an unbounded in-memory list or a single OR filesort.
        val rows = listOf("UNCERTAIN", "ACTIVE").flatMap { state -> pendingUsageQuery().where {
            scope and before and (Reservations.state eq state) and
                (if (state == "ACTIVE") Reservations.expiresAt lessEq now else Op.TRUE)
        }.orderBy(Reservations.createdAt to SortOrder.DESC, Reservations.id to SortOrder.DESC).limit(PAGE_SIZE + 1).map(::pendingUsageRecord) }
            .sortedWith(compareByDescending<PendingUsage> { it.createdAtMillis }.thenByDescending { it.id })
        PendingUsagePage(rows.take(PAGE_SIZE), rows.takeIf { it.size > PAGE_SIZE }?.get(PAGE_SIZE - 1)?.id)
    }

    override suspend fun history(actor: Principal, memberId: String?, beforeId: String?): ReconciledUsagePage = database.query {
        authorize(this, actor); memberId?.let(::identifier)
        val table = UsageReconciliations
        val scope = (table.teamId eq actor.teamId) and (memberId?.let { table.memberId eq it } ?: Op.TRUE)
        val cursor = beforeId?.let { identifier(it); table.select(table.id, table.reconciledAt).where { scope and (table.id eq it) }.singleOrNull()
            ?: throw invalidCursor() }
        val rows = table.selectAll().where { scope and (cursor?.let {
            (table.reconciledAt less it[table.reconciledAt]) or ((table.reconciledAt eq it[table.reconciledAt]) and (table.id less it[table.id]))
        } ?: Op.TRUE) }.orderBy(table.reconciledAt to SortOrder.DESC, table.id to SortOrder.DESC).limit(PAGE_SIZE + 1).toList().let(::reconciledUsageRecords)
        ReconciledUsagePage(rows.take(PAGE_SIZE), rows.takeIf { it.size > PAGE_SIZE }?.get(PAGE_SIZE - 1)?.id)
    }

    override suspend fun get(actor: Principal, id: String): UsageReview = database.query {
        authorize(this, actor); identifier(id)
        reconciliation(actor.teamId, id)?.let { return@query UsageReview(reconciliation = it) }
        val row = pendingUsageQuery().where { (Reservations.id eq id) and (Reservations.teamId eq actor.teamId) }.singleOrNull() ?: throw missing()
        if (!rules.eligible(row[Reservations.state], row[Reservations.expiresAt], nowMillis())) throw notPending()
        UsageReview(pending = pendingUsageRecord(row))
    }

    override suspend fun reconcile(actor: Principal, id: String, change: UsageReconciliationChange): ReconciledUsage = database.query {
        authorize(this, actor); identifier(id)
        val input = rules.validate(change)
        reconciliation(actor.teamId, id)?.let { old ->
            if (old.previousUnits != input.expectedUnits || old.actualUnits != input.actualUnits || old.source != input.source ||
                old.reference != input.reference || old.reason != input.reason || old.providerRequestId != input.expectedProviderRequestId)
                throw TeamFailure("usage_already_reconciled", 409, "Another correction already resolved this request. Inspect its history.")
            return@query old
        }
        val row = Reservations.selectAll().where { (Reservations.teamId eq actor.teamId) and (Reservations.id eq id) }.singleOrNull() ?: throw missing()
        val now = nowMillis()
        rules.check(row[Reservations.state], row[Reservations.units], row[Reservations.expiresAt], now, row[Reservations.providerRequestId], input)
        val groupIds = ReservationGroups.select(ReservationGroups.groupId).where { ReservationGroups.reservationId eq id }
            .orderBy(ReservationGroups.groupId).limit(17).map { it[ReservationGroups.groupId] }
        if (groupIds.size > 16) throw TeamFailure("reconciliation_metadata_limit", 503, "The original group associations need operator review.")
        val delta = input.actualUnits - row[Reservations.units]
        Reservations.update({ (Reservations.id eq id) and (Reservations.teamId eq actor.teamId) }) {
            it[state] = "RECONCILED"; it[units] = input.actualUnits
        }
        addDailyUnits(actor.teamId, row[Reservations.dayNumber], row[Reservations.memberId], row[Reservations.poolId], row[Reservations.sourcePoolId], groupIds, delta)
        row[Reservations.runId]?.let { run -> AgentRuns.update({ (AgentRuns.id eq run) and (AgentRuns.teamId eq actor.teamId) }) {
            it[chargedUnits] = chargedUnits + delta
        } }
        UsageReconciliations.insert {
            it[UsageReconciliations.id] = id; it[teamId] = actor.teamId; it[memberId] = row[Reservations.memberId]; it[accountId] = row[Reservations.accountId]
            it[poolId] = row[Reservations.poolId]; it[sourcePoolId] = row[Reservations.sourcePoolId]; it[runId] = row[Reservations.runId]; it[dayNumber] = row[Reservations.dayNumber]
            it[providerRequestId] = row[Reservations.providerRequestId]; it[previousState] = row[Reservations.state]
            it[previousUnits] = row[Reservations.units]; it[actualUnits] = input.actualUnits; it[evidenceSource] = input.source.name
            it[reference] = input.reference; it[reason] = input.reason; it[actorId] = actor.member.id; it[reconciledAt] = now
        }
        ReconciledUsageGroups.batchInsert(groupIds) { group -> this[ReconciledUsageGroups.reconciliationId] = id; this[ReconciledUsageGroups.groupId] = group }
        audit(actor.teamId, actor.member.id, "usage.reconciled", id)
        requireNotNull(reconciliation(actor.teamId, id))
    }

    private fun authorize(transaction: JdbcTransaction, actor: Principal) {
        actor.requireAdmin(); transaction.lockTeam(actor.teamId); requireCurrent(transaction, actor)
    }
    private fun reconciliation(team: String, id: String) = UsageReconciliations.selectAll().where {
        (UsageReconciliations.teamId eq team) and (UsageReconciliations.id eq id)
    }.singleOrNull()?.let { reconciledUsageRecords(listOf(it)).single() }
    private fun identifier(value: String) {
        if (!Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value))
            throw TeamFailure("invalid_identifier", 400, "Use a valid request or member identifier.")
    }
    private fun invalidCursor() = TeamFailure("invalid_usage_cursor", 400, "The cursor is no longer accessible. Refresh the list.")
    private fun missing() = TeamFailure("not_found", 404, "Usage review not found.")
    private fun notPending() = TeamFailure("usage_not_pending", 409, "This request is active or already has a final charge.")
    companion object { const val PAGE_SIZE = 50 }
}

private fun pendingUsageQuery() = Reservations.join(Members, JoinType.INNER, Reservations.memberId, Members.id)
    .join(Accounts, JoinType.INNER, Reservations.accountId, Accounts.id)
    .select(Reservations.columns + listOf(Members.username, Accounts.displayName, Accounts.email, Accounts.provider))
private fun pendingUsageRecord(row: ResultRow) = with(Reservations) {
    PendingUsage(row[id], row[memberId], row[Members.username], row[accountId], row[Accounts.displayName], row[Accounts.provider], row[providerRequestId],
        row[poolId], row[runId], row[dayNumber], row[units], row[state], row[createdAt], row[expiresAt], row[sourcePoolId] ?: row[poolId], row[Accounts.email])
}
private fun reconciledUsageRecords(rows: List<ResultRow>): List<ReconciledUsage> {
    if (rows.isEmpty()) return emptyList()
    val groups = ReconciledUsageGroups.selectAll().where { ReconciledUsageGroups.reconciliationId inList rows.map { it[UsageReconciliations.id] } }
        .orderBy(ReconciledUsageGroups.groupId).groupBy({ it[ReconciledUsageGroups.reconciliationId] }, { it[ReconciledUsageGroups.groupId] })
    return rows.map { reconciledUsageRecord(it, groups[it[UsageReconciliations.id]].orEmpty()) }
}
private fun reconciledUsageRecord(row: ResultRow, groups: List<String>) = with(UsageReconciliations) {
    ReconciledUsage(row[id], row[memberId], row[accountId], row[poolId], row[runId], row[dayNumber], row[providerRequestId], row[previousState], row[previousUnits],
        row[actualUnits], UsageEvidence.valueOf(row[evidenceSource]), row[reference], row[reason], row[actorId], row[reconciledAt], groups, row[sourcePoolId] ?: row[poolId])
}
