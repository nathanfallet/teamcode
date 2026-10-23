package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Shared quota state; lock the team just as admission and forecasting do. */
internal class SqlQuotaObservations(private val database: Database) {
    suspend fun observe(accountId: String, update: QuotaUpdate) = database.query {
        val account = Accounts.select(Accounts.teamId, Accounts.provider).where { Accounts.id eq accountId }.singleOrNull()
            ?: return@query
        lockTeam(account[Accounts.teamId])
        val now = nowMillis()
        if (update.observedAtMillis !in 0..now) return@query
        val codex = account[Accounts.provider] == "codex"
        val previousSnapshot = QuotaSnapshots.selectAll().where { QuotaSnapshots.accountId eq accountId }
            .singleOrNull()?.get(QuotaSnapshots.modelWindowsObservedAt)
        val valid = update.windows.filter { it.accountId == accountId && it.window.length in 1..80 && it.utilization.isFinite() && it.utilization in 0.0..1.0 && it.observedAtMillis in 0..now && (it.resetsAtMillis?.let { reset -> reset > it.observedAtMillis } != false) }
        val replace = update.replaceModelWindows && valid.size == update.windows.size &&
            (previousSnapshot == null || update.observedAtMillis > previousSnapshot)
        if (replace) {
            val present = valid.map { it.window }.toSet()
            // Retain any inference observation newer than the start of this probe.
            val removed = QuotaWindows.select(QuotaWindows.windowName).where {
                (QuotaWindows.accountId eq accountId) and (QuotaWindows.windowName like "model:%") and
                    (QuotaWindows.observedAt lessEq update.observedAtMillis)
            }.map { it[QuotaWindows.windowName] }.filterNot { it in present }
            if (removed.isNotEmpty()) QuotaWindows.deleteWhere { (QuotaWindows.accountId eq accountId) and (windowName inList removed) }
            QuotaSnapshots.upsert {
                it[QuotaSnapshots.accountId] = accountId
                it[modelWindowsObservedAt] = update.observedAtMillis
            }
        }
        val starts = valid.groupBy { it.window }.mapValues { (_, readings) -> readings.minOf { it.observedAtMillis } }
        val previous = QuotaWindows.selectAll().where { QuotaWindows.accountId eq accountId }
            .associate { it[QuotaWindows.windowName] to it[QuotaWindows.observedAt] }.toMutableMap()
        valid.filterNot { it.window.startsWith("model:") && previousSnapshot != null && it.observedAtMillis <= previousSnapshot }.forEach { q ->
            if ((previous[q.window] ?: Long.MIN_VALUE) <= q.observedAtMillis) {
                QuotaWindows.upsert(onUpdateExclude = listOf(QuotaWindows.windowStartedAt)) {
                    it[QuotaWindows.accountId] = accountId
                    it[windowName] = q.window
                    it[utilization] = q.utilization
                    it[observedAt] = q.observedAtMillis
                    it[resetsAt] = q.resetsAtMillis
                    it[windowStartedAt] = starts.getValue(q.window)
                }
                previous[q.window] = q.observedAtMillis
            }
            // One sample per minute/window bounds history independently of inference throughput.
            val minute = q.observedAtMillis / 60_000
            val previousSample = QuotaHistory.select(QuotaHistory.observedAt).where {
                (QuotaHistory.accountId eq accountId) and (QuotaHistory.windowName eq q.window) and (QuotaHistory.sampleMinute eq minute)
            }.singleOrNull()?.get(QuotaHistory.observedAt)
            if (previousSample == null || previousSample <= q.observedAtMillis) QuotaHistory.upsert {
                it[QuotaHistory.accountId] = accountId
                it[windowName] = q.window
                it[utilization] = q.utilization
                it[observedAt] = q.observedAtMillis
                it[resetsAt] = q.resetsAtMillis
                it[sampleMinute] = minute
            }
        }
        refreshNextReset(accountId, now)
        val binding = update.modelLimit?.takeIf { codex && validModel(it.model) && validLimit(it.limit) }
        if (binding != null) {
            val model = binding.model.lowercase()
            val previousBinding = QuotaModelLimits.select(QuotaModelLimits.observedAt).where {
                (QuotaModelLimits.accountId eq accountId) and (QuotaModelLimits.modelName eq model)
            }.singleOrNull()?.get(QuotaModelLimits.observedAt)
            if (previousBinding == null || previousBinding <= update.observedAtMillis) QuotaModelLimits.upsert {
                it[QuotaModelLimits.accountId] = accountId
                it[modelName] = model
                it[limitSlug] = binding.limit.lowercase()
                it[observedAt] = update.observedAtMillis
            }
            val excess = QuotaModelLimits.select(QuotaModelLimits.modelName).where { QuotaModelLimits.accountId eq accountId }
                .orderBy(QuotaModelLimits.observedAt to SortOrder.DESC, QuotaModelLimits.modelName to SortOrder.ASC)
                .limit(128).offset(32).map { it[QuotaModelLimits.modelName] }
            if (excess.isNotEmpty()) QuotaModelLimits.deleteWhere { (QuotaModelLimits.accountId eq accountId) and (modelName inList excess) }
        }
    }
    /** Keeps `accounts.next_reset_at` equal to the earliest still-future reset of a shared window, in the observation's transaction. */
    private fun refreshNextReset(accountId: String, now: Long) {
        val earliest = QuotaWindows.select(QuotaWindows.resetsAt).where {
            (QuotaWindows.accountId eq accountId) and (QuotaWindows.windowName notLike "model:%") and (QuotaWindows.resetsAt greater now)
        }.mapNotNull { it[QuotaWindows.resetsAt] }.minOrNull() ?: NO_KNOWN_RESET
        Accounts.update({ (Accounts.id eq accountId) and (Accounts.nextResetAt neq earliest) }) { it[nextResetAt] = earliest }
    }
    private fun validModel(value: String) = Regex("[a-zA-Z0-9][a-zA-Z0-9._/:-]{0,127}").matches(value)
    private fun validLimit(value: String) = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}").matches(value)
}

internal fun readQuotaModelLimits(team: String): Map<String, Map<String, String>> =
    (QuotaModelLimits innerJoin Accounts).select(QuotaModelLimits.accountId, QuotaModelLimits.modelName, QuotaModelLimits.limitSlug)
        .where { (Accounts.teamId eq team) and (Accounts.provider eq "codex") }
        .groupBy { it[QuotaModelLimits.accountId] }
        .mapValues { (_, rows) -> rows.associate { it[QuotaModelLimits.modelName] to it[QuotaModelLimits.limitSlug] } }
