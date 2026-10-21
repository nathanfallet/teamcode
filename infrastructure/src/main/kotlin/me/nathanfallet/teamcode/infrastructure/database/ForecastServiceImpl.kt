package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Bounded Exposed reads; current windows fence retired/reintroduced model-cap histories. */
class ForecastServiceImpl(private val database: Database) : ForecastService {
    override suspend fun load(actor: Principal, accountId: String?): ForecastSnapshot {
        actor.requireAdmin()
        if (accountId != null && !Regex("[a-zA-Z0-9-]{1,36}").matches(accountId)) throw TeamFailure("invalid_account", 400, "Choose a valid TeamCode account identifier.")
        return database.query {
            lockTeam(actor.teamId); requireCurrent(this, actor)
            val now = nowMillis()
            val columns = listOf(Accounts.id, Accounts.provider, Accounts.displayName, Accounts.state, Accounts.disabled, Accounts.poolId, Accounts.email)
            val listed = Accounts.select(columns).where { Accounts.teamId eq actor.teamId }.orderBy(Accounts.id).limit(65).map(::forecastAccount)
            val accounts = listed.take(64)
            val selected = if (accountId == null) accounts.firstOrNull() else Accounts.select(columns)
                .where { (Accounts.teamId eq actor.teamId) and (Accounts.id eq accountId) }.singleOrNull()?.let(::forecastAccount)
                ?: throw TeamFailure("not_found", 404, "Account not found.")
            val threshold = readPacingSettings(actor.teamId).quotaPressureThreshold
            if (selected == null) return@query ForecastSnapshot(now, accounts, null, emptyList(), false, threshold)
            val currentRows = QuotaWindows.selectAll().where { QuotaWindows.accountId eq selected.id }.orderBy(QuotaWindows.windowName).limit(129).toList()
            val current = currentRows.take(128).map(::quotaRecord)
            val starts = currentRows.take(128).associate { it[QuotaWindows.windowName] to it[QuotaWindows.windowStartedAt] }
            val rows = if (starts.isEmpty()) emptyList() else forecastHistoryQuery(selected.id, now, starts).toList()
            val history = rows.take(8192).map {
                AccountQuota(it[QuotaHistory.accountId], it[QuotaHistory.windowName], it[QuotaHistory.utilization], it[QuotaHistory.observedAt], it[QuotaHistory.resetsAt])
            }.groupBy { it.window }
            val visibleAccounts = if (selected in accounts) accounts else accounts + selected
            ForecastSnapshot(now, visibleAccounts, selected,
                current.map { BuildForecastSeriesUseCase(threshold)(it, history[it.window].orEmpty(), now) },
                listed.size > 64 || currentRows.size > 128 || rows.size > 8192, threshold)
        }
    }
}

private fun forecastAccount(row: ResultRow) = ForecastAccount(row[Accounts.id], row[Accounts.provider], row[Accounts.displayName], row[Accounts.state], row[Accounts.disabled], row[Accounts.poolId], row[Accounts.email])

/** Shared with the local execution-plan fixture so it analyzes the production query itself. */
internal fun forecastHistoryQuery(accountId: String, now: Long, windowStarts: Map<String, Long>): Query {
    require(windowStarts.isNotEmpty() && windowStarts.size <= 128)
    // Literal per-window bounds use the existing (account, window, observed_at) index.
    // The caller holds the team lock, so retirement cannot change these bounds mid-read.
    val ranges = windowStarts.map { (window, start) ->
        (QuotaHistory.windowName eq window) and (QuotaHistory.observedAt greaterEq maxOf(now - 3_600_000, start))
    }.compoundOr()
    return QuotaHistory.selectAll().where {
        (QuotaHistory.accountId eq accountId) and (QuotaHistory.observedAt lessEq now) and ranges
    }.orderBy(QuotaHistory.observedAt to SortOrder.DESC, QuotaHistory.id to SortOrder.DESC).limit(8193)
}
