package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.team.Principal
import kotlin.math.roundToLong

/** One stored utilization reading of a window (`quota_history`). */
data class QuotaSample(val utilization: Double, val observedAtMillis: Long, val resetsAtMillis: Long?)

/** The API-equivalent of one account's tokens settled in one UTC hour; [unpriced] when a model of that hour has no price. */
data class HourCost(val hourNumber: Long, val micros: Long, val unpriced: Boolean = false)

/**
 * The learned full capacity of one shared window of one account, in API-equivalent micro-dollars: what the whole
 * window (0 to 100%) is worth at API prices. Null below [MIN_CAPACITY_SAMPLES] samples; [samples] says how many exist.
 */
data class WindowCapacity(val window: String, val fullWindowMicros: Long?, val samples: Int)

const val MIN_CAPACITY_SAMPLES = 3
/** A utilization rise smaller than this is mostly rounding (providers report whole points). */
const val MIN_CAPACITY_DELTA = .05
/** The retention of `quota_history`: a longer look-back needs a retention change first. */
const val CAPACITY_HISTORY_MILLIS = 7 * 86_400_000L
private const val HOUR_MILLIS = 3_600_000L

/**
 * One sample = the API-equivalent settled between two readings of the same window period (same known reset), at least
 * [MIN_CAPACITY_DELTA] apart, ÷ their utilization rise. Readings are walked in time order: a rise of five points closes
 * a pair and becomes the next anchor, a decrease restarts the anchor. Hours are priced in proportion to the part of the
 * hour the pair covers (usage is bucketed by settlement hour, so a sample is only accurate to the hour). A pair that
 * covers an unpriced model, or no traffic at all (the subscription was used outside TeamCode), is no sample. The
 * estimate is the median of the samples of the last seven days.
 */
class EstimateWindowCapacityUseCase {
    fun estimate(window: String, history: List<QuotaSample>, hours: List<HourCost>, now: Long): WindowCapacity {
        val costs = hours.associateBy { it.hourNumber }
        val samples = mutableListOf<Double>()
        history.filter { it.resetsAtMillis != null && it.observedAtMillis in (now - CAPACITY_HISTORY_MILLIS)..now && it.utilization.isFinite() && it.utilization in 0.0..1.0 }
            .groupBy { it.resetsAtMillis }.values.forEach { period ->
                var anchor: QuotaSample? = null
                for (reading in period.sortedBy { it.observedAtMillis }) {
                    val start = anchor
                    if (start == null || reading.utilization < start.utilization) { anchor = reading; continue }
                    val rise = reading.utilization - start.utilization
                    if (rise + 1e-9 < MIN_CAPACITY_DELTA) continue
                    cost(costs, start.observedAtMillis, reading.observedAtMillis)?.takeIf { it > 0 }?.let { samples += it / rise }
                    anchor = reading
                }
            }
        val median = if (samples.size < MIN_CAPACITY_SAMPLES) null else samples.sorted().let { sorted ->
            if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }
        return WindowCapacity(window, median?.roundToLong(), samples.size)
    }

    private fun cost(costs: Map<Long, HourCost>, from: Long, to: Long): Double? {
        var total = 0.0
        for (hour in from / HOUR_MILLIS..(to - 1) / HOUR_MILLIS) {
            val overlap = minOf(to, (hour + 1) * HOUR_MILLIS) - maxOf(from, hour * HOUR_MILLIS)
            if (overlap <= 0) continue
            val cost = costs[hour] ?: continue
            if (cost.unpriced) return null
            total += cost.micros.toDouble() * overlap / HOUR_MILLIS
        }
        return total
    }
}

enum class PoolContribution {
    /** Counted: [AccountPoolShare.dailyMicros] is its daily capacity, possibly zero (weekly window at the threshold). */
    COUNTED,
    /** Disabled, pending handover, `RECONNECT_REQUIRED` or cooling down: not an account that can serve. */
    UNAVAILABLE,
    /** No fresh five-hour or seven-day observation: nothing says what is left. */
    NO_FRESH_OBSERVATION,
    /** An observed shared window has fewer than [MIN_CAPACITY_SAMPLES] samples, so its capacity is unknown. */
    NO_ESTIMATE,
}

/** [binding] is the window that gives the smaller daily figure. */
data class AccountPoolShare(val accountId: String, val status: PoolContribution, val dailyMicros: Long, val binding: String? = null)

/**
 * The home pool's daily capacity: [dailyMicros] in API-equivalent micro-dollars, [dailyUnits] in credits (null with
 * the [reason] when there is no automatic value, and then the manual budget applies).
 */
data class PoolSizeEstimate(val dailyMicros: Long, val dailyUnits: Long?, val accounts: List<AccountPoolShare>, val reason: String?) {
    companion object {
        const val NO_ACCOUNTS = "no_accounts"
        const val NO_ESTIMATE = "no_estimate"
        const val NO_PRICED_USAGE = "no_priced_usage"
    }
}

/**
 * Sizes the home pool from the remaining capacity of its accounts, read from [AccountHeadroomUseCase] (the computation
 * pacing uses): same eligibility, same threshold, same resets. Only shared windows count, as without a model.
 *
 * Per account, with C5 and C7 the learned full capacities of its five-hour and seven-day windows:
 * - five-hour term: C5 × threshold × 24 / 5, the steady rate of a day of five-hour windows (an account at the five-hour
 *   threshold still counts it: the window refills several times a day);
 * - seven-day term: the remaining points × C7 spread over the days left before the reset (seven when it is unknown); when the reset
 *   comes within the day, plus the share of a fresh window (threshold × C7 / 7 per day) for the hours after it.
 * The account counts the smaller term. A window the provider does not report does not bind; a window observed without an
 * estimate leaves the account without a figure. Credits = micro-dollars × [creditsPerMicro].
 */
class SizeSharedPoolUseCase(private val headroom: AccountHeadroomUseCase = AccountHeadroomUseCase()) {
    fun size(
        accounts: List<PoolAccountObservation>, capacities: Map<String, List<WindowCapacity>>, creditsPerMicro: Double?,
        now: Long, pressureThreshold: Double,
    ): PoolSizeEstimate {
        if (accounts.isEmpty()) return PoolSizeEstimate(0, null, emptyList(), PoolSizeEstimate.NO_ACCOUNTS)
        val serving = (headroom.usable(accounts, now, pressureThreshold) + headroom.exhausted(accounts, now, pressureThreshold)).associateBy { it.account.id }
        val shares = accounts.map { observed ->
            val id = observed.account.id
            val current = serving[id] ?: return@map AccountPoolShare(id, PoolContribution.UNAVAILABLE, 0)
            share(id, current, capacities[id].orEmpty().associateBy { it.window }, now, pressureThreshold)
        }
        val counted = shares.filter { it.status == PoolContribution.COUNTED }
        val micros = counted.sumOf { it.dailyMicros }
        val reason = when {
            counted.isEmpty() -> PoolSizeEstimate.NO_ESTIMATE
            creditsPerMicro == null -> PoolSizeEstimate.NO_PRICED_USAGE
            else -> null
        }
        return PoolSizeEstimate(micros, if (reason == null) (micros * creditsPerMicro!!).roundToLong() else null, shares, reason)
    }

    private fun share(id: String, current: AccountHeadroom, capacities: Map<String, WindowCapacity>, now: Long, threshold: Double): AccountPoolShare {
        val observed = SHARED.mapNotNull { current.window(it) }
        if (observed.isEmpty()) return AccountPoolShare(id, PoolContribution.NO_FRESH_OBSERVATION, 0)
        val terms = observed.map { window ->
            val full = capacities[window.window]?.fullWindowMicros ?: return AccountPoolShare(id, PoolContribution.NO_ESTIMATE, 0)
            window.window to when (window.window) {
                FIVE_HOUR -> full * threshold * 24 / 5
                else -> weekly(window, full.toDouble(), now, threshold)
            }
        }
        val (binding, daily) = terms.minBy { it.second }
        return AccountPoolShare(id, PoolContribution.COUNTED, daily.roundToLong(), binding)
    }

    private fun weekly(window: WindowHeadroom, full: Double, now: Long, threshold: Double): Double {
        // An unknown reset may be up to a week away: spread what is left over the whole week.
        val untilReset = (window.resetsAtMillis ?: return window.remainingShare * full / 7) - now
        if (untilReset >= DAY) return window.remainingShare * full / (untilReset.toDouble() / DAY)
        // Everything left is usable before the reset, then a fresh window spread over its seven days.
        return window.remainingShare * full + threshold * full / 7 * (DAY - untilReset).toDouble() / DAY
    }

    private companion object {
        const val FIVE_HOUR = "five_hour"
        val SHARED = listOf(FIVE_HOUR, "seven_day")
        const val DAY = 86_400_000L
    }
}

object AutomaticPoolSize {
    /** An automatic value older than this (the job stopped, or has had no estimate for an hour) gives way to the manual budget. */
    const val MAX_AGE_MILLIS = 3_600_000L
    /** Every ten minutes, under one owner across replicas. */
    const val REFRESH_MILLIS = 600_000L
    /** A new value moves at most this share of the previous one per hour, so a noisy estimate cannot swing the pool. */
    const val MAX_STEP_PER_HOUR = .2
    /** A previous value older than this is no reference: the estimate applies as is. */
    const val SMOOTHING_MEMORY_MILLIS = 2 * 3_600_000L

    /**
     * The home pool's daily budget: the automatic value when the setting is on and the value is fresh, the manual budget
     * otherwise. Named pools and shared overflow reserves stay manual. The member split is unchanged (pool × weight / total).
     */
    fun effectiveBudget(poolId: String, manual: Long, automatic: Long?, computedAtMillis: Long?, enabled: Boolean, now: Long): Long =
        if (usesAutomatic(poolId, automatic, computedAtMillis, enabled, now)) automatic!! else manual

    fun usesAutomatic(poolId: String, automatic: Long?, computedAtMillis: Long?, enabled: Boolean, now: Long): Boolean =
        enabled && poolId.isEmpty() && automatic != null && computedAtMillis != null && now - computedAtMillis in 0..MAX_AGE_MILLIS

    /** At most ±[MAX_STEP_PER_HOUR] of [previous] per elapsed hour; nothing scales from zero, so a pool emptied by resets grows back. */
    fun smooth(previous: Long?, previousAtMillis: Long?, target: Long, now: Long): Long {
        if (previous == null || previousAtMillis == null || previous <= 0) return target
        val elapsed = now - previousAtMillis
        if (elapsed !in 0..SMOOTHING_MEMORY_MILLIS) return target
        val step = previous * MAX_STEP_PER_HOUR * minOf(elapsed, 3_600_000L) / 3_600_000.0
        return target.coerceIn((previous - step).roundToLong(), (previous + step).roundToLong())
    }
}

/** What the dashboard and `GET /api/v1/pools/size` show: the setting, both values and the estimate behind the automatic one. */
data class PoolSizeReport(
    val enabled: Boolean, val manualDailyUnits: Long, val automaticDailyUnits: Long?, val automaticComputedAtMillis: Long?,
    val effectiveDailyUnits: Long, val usesAutomatic: Boolean, val estimate: PoolSizeEstimate, val creditsPerMicro: Double?, val unpricedModels: List<String>,
    val capacities: Map<String, List<WindowCapacity>>,
)

interface PoolSizingService {
    /** The home pool's sizing as of now, computed on demand from the same data as the scheduled job; `team:read`. */
    suspend fun report(actor: Principal): PoolSizeReport
    /** The scheduled job: recompute and store every team's automatic value. */
    suspend fun refresh()
}
