package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.routing.TokenUsage
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * The automatic home pool size (ADR 0002, step 2). The scheduled [refresh] estimates every priced team's home pool and
 * stores the smoothed value next to the manual budget; [readMemberCapacity] uses it only while the setting is on and the
 * value is fresh. [report] recomputes the same estimate on demand for the dashboard and the API.
 */
class SqlPoolSizing(private val database: Database) : PoolSizingService {
    override suspend fun report(actor: Principal): PoolSizeReport = database.query {
        actor.requireTeamRead(); requireCurrent(this, actor)
        val now = nowMillis()
        val inputs = readPoolSizingInputs(actor.teamId, now)
        val settings = readPacingSettings(actor.teamId)
        val budget = readHomeBudget(actor.teamId)
        PoolSizeReport(settings.autoPoolSize, budget.manual, budget.automatic, budget.computedAt,
            AutomaticPoolSize.effectiveBudget("", budget.manual, budget.automatic, budget.computedAt, settings.autoPoolSize, now),
            AutomaticPoolSize.usesAutomatic("", budget.automatic, budget.computedAt, settings.autoPoolSize, now),
            inputs.estimate, inputs.ratio.creditsPerMicro, inputs.ratio.unpricedModels, inputs.capacities)
    }

    /** Teams without any price cannot be estimated; their automatic value, if any, ages out and the manual budget applies. */
    override suspend fun refresh() {
        val teams = database.query { ApiPricesTable.select(ApiPricesTable.teamId).withDistinct().limit(100).map { it[ApiPricesTable.teamId] } }
        for (team in teams) {
            val estimate = database.query { readPoolSizingInputs(team, nowMillis()).estimate }
            // Without a figure the stored value is left to age: after an hour the manual budget applies again.
            val target = estimate.dailyUnits ?: continue
            database.query {
                lockTeam(team)
                val now = nowMillis()
                val previous = readHomeBudget(team)
                val next = AutomaticPoolSize.smooth(previous.automatic, previous.computedAt, target, now).coerceIn(0, MAX_POOL_DAILY_UNITS)
                val updated = PoolBudgets.update({ (PoolBudgets.teamId eq team) and (PoolBudgets.poolId eq "") }) {
                    it[autoDailyUnits] = next; it[autoComputedAt] = now
                }
                // The implicit home budget becomes an explicit row with the same manual value.
                if (updated == 0) PoolBudgets.insert {
                    it[teamId] = team; it[poolId] = ""; it[dailyUnits] = DEFAULT_POOL_DAILY_UNITS; it[autoDailyUnits] = next; it[autoComputedAt] = now
                }
            }
        }
    }
}

internal data class HomeBudget(val manual: Long, val automatic: Long?, val computedAt: Long?)

internal fun readHomeBudget(teamId: String): HomeBudget = PoolBudgets.select(PoolBudgets.dailyUnits, PoolBudgets.autoDailyUnits, PoolBudgets.autoComputedAt)
    .where { (PoolBudgets.teamId eq teamId) and (PoolBudgets.poolId eq "") }.singleOrNull()
    ?.let { HomeBudget(it[PoolBudgets.dailyUnits], it[PoolBudgets.autoDailyUnits], it[PoolBudgets.autoComputedAt]) } ?: HomeBudget(DEFAULT_POOL_DAILY_UNITS, null, null)

internal data class PoolSizingInputs(val estimate: PoolSizeEstimate, val ratio: CreditRatio, val capacities: Map<String, List<WindowCapacity>>)

/**
 * Everything the estimate reads, inside the caller's transaction: the home pool's accounts and current windows, seven
 * days of their `quota_history` (shared windows only) and of their hourly tokens, and seven days of the team's tokens
 * for the credit ratio. Every read is bounded by an index prefix and the retention of the history.
 */
internal fun readPoolSizingInputs(teamId: String, now: Long): PoolSizingInputs {
    val prices = ApiPriceTable(readApiPrices(teamId))
    val threshold = readPacingSettings(teamId).quotaPressureThreshold
    val rows = Accounts.selectAll().where { (Accounts.teamId eq teamId) and (Accounts.poolId.isNull() or (Accounts.poolId eq "")) }
        .orderBy(Accounts.priority to SortOrder.DESC, Accounts.id to SortOrder.ASC).limit(POOL_SIZING_ACCOUNTS)
        .map { accountRecord(it) to it[Accounts.cooldownUntil] }
    val ids = rows.map { it.first.id }
    val windows = if (ids.isEmpty()) emptyList() else QuotaWindows.selectAll().where { QuotaWindows.accountId inList ids }.map(::quotaRecord)
    val observations = rows.map { (account, cooldown) -> PoolAccountObservation(account, cooldown, windows.filter { it.accountId == account.id }) }
    val since = now - CAPACITY_HISTORY_MILLIS
    val estimator = EstimateWindowCapacityUseCase()
    val capacities = ids.associateWith { id ->
        val costs = hourCosts(prices, id, since / HOUR)
        SHARED_POOL_WINDOWS.map { window ->
            val history = QuotaHistory.select(QuotaHistory.utilization, QuotaHistory.observedAt, QuotaHistory.resetsAt)
                .where { (QuotaHistory.accountId eq id) and (QuotaHistory.windowName eq window) and (QuotaHistory.observedAt greaterEq since) }
                .orderBy(QuotaHistory.observedAt to SortOrder.ASC).limit(HISTORY_ROWS)
                .map { QuotaSample(it[QuotaHistory.utilization], it[QuotaHistory.observedAt], it[QuotaHistory.resetsAt]) }
            estimator.estimate(window, history, costs, now)
        }
    }
    val today = now / DAY_MILLIS
    val t = TokenUsageTable
    val input = t.inputTokens.sum(); val cacheWrite = t.cacheCreationTokens.sum(); val cacheRead = t.cacheReadTokens.sum(); val output = t.outputTokens.sum()
    val usage = t.select(t.dayNumber, t.provider, t.model, input, cacheWrite, cacheRead, output)
        .where { (t.teamId eq teamId) and (t.dayNumber greaterEq today - 6) and (t.dayNumber lessEq today) }
        .groupBy(t.dayNumber, t.provider, t.model).limit(RATIO_ROWS).map {
            PricedTokens(it[t.provider], it[t.model], it[t.dayNumber], TokenUsage(it[input] ?: 0, it[output] ?: 0, it[cacheRead] ?: 0, it[cacheWrite] ?: 0))
        }
    val ratio = CreditRatio.of(prices, usage)
    return PoolSizingInputs(SizeSharedPoolUseCase().size(observations, capacities, ratio.creditsPerMicro, now, threshold), ratio, capacities)
}

/** One account's hours since [fromHour], each priced at its own day's prices; an hour with an unpriced model is marked. */
private fun hourCosts(prices: ApiPriceTable, accountId: String, fromHour: Long): List<HourCost> {
    val a = AccountTokenUsageTable
    return a.selectAll().where { (a.accountId eq accountId) and (a.hourNumber greaterEq fromHour) }.limit(HOUR_ROWS).toList()
        .groupBy { it[a.hourNumber] }.map { (hour, models) ->
            var micros = 0L
            var unpriced = false
            for (row in models) {
                val tokens = TokenUsage(row[a.inputTokens], row[a.outputTokens], row[a.cacheReadTokens], row[a.cacheCreationTokens])
                val cost = prices.microsOf(row[a.provider], row[a.model], hour / 24, tokens)
                if (cost == null) { if (tokens.credits > 0) unpriced = true } else micros += cost
            }
            HourCost(hour, micros, unpriced)
        }
}

internal const val DEFAULT_POOL_DAILY_UNITS = 1_000_000L
private const val MAX_POOL_DAILY_UNITS = 100_000_000_000L
private val SHARED_POOL_WINDOWS = listOf("five_hour", "seven_day")
private const val POOL_SIZING_ACCOUNTS = 64
/** One reading per minute for seven days, with margin. */
private const val HISTORY_ROWS = 12_000
/** Seven days of hours, a few models each. */
private const val HOUR_ROWS = 5_000
private const val RATIO_ROWS = 5_000
private const val HOUR = 3_600_000L
private const val DAY_MILLIS = 86_400_000L
