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
 * [fullWindowMicros] is the median of the samples, what the automatic pool budget (admission) multiplies;
 * [capacityMicros] their upper quartile ([CAPACITY_QUANTILE]), what the capacity and the outside-use split report.
 */
data class WindowCapacity(val window: String, val fullWindowMicros: Long?, val samples: Int, val capacityMicros: Long? = fullWindowMicros)

const val MIN_CAPACITY_SAMPLES = 3
/** A utilization rise smaller than this is mostly rounding (providers report whole points). */
const val MIN_CAPACITY_DELTA = .05
/** Hourly usage buckets make shorter pairs noisy: a pair also spans at least this long. */
const val MIN_CAPACITY_PAIR_MILLIS = 3_600_000L
/** Reported resets may jitter between readings; readings whose resets round to the same minute share a period. */
private const val RESET_GRANULARITY_MILLIS = 60_000L
/** The retention of `quota_history`: a longer look-back needs a retention change first. */
const val CAPACITY_HISTORY_MILLIS = 7 * 86_400_000L
private const val HOUR_MILLIS = 3_600_000L

/**
 * One sample = the API-equivalent settled between two readings of the same window period (same known reset), at least
 * [MIN_CAPACITY_DELTA] and [MIN_CAPACITY_PAIR_MILLIS] apart, ÷ their utilization rise (resets equal to the minute). Readings are walked in time order: a rise of five points closes
 * a pair and becomes the next anchor, a decrease restarts the anchor. Hours are priced in proportion to the part of the
 * hour the pair covers (usage is bucketed by settlement hour, so a sample is only accurate to the hour). A pair that
 * covers an unpriced model, or no traffic at all (the subscription was used outside TeamCode), is no sample.
 *
 * The provider meters every use of the subscription, TeamCode's or not, while the cost only counts TeamCode's: traffic
 * outside TeamCode adds points without cost, so a sample is mostly a **lower bound** of the window's capacity (whole
 * points and hourly buckets also move it either way). Two figures come out of the last seven days' samples: the median
 * ([WindowCapacity.fullWindowMicros]), unchanged for admission, which stays conservative against noise that inflates a
 * sample; and the upper quartile ([WindowCapacity.capacityMicros], [CAPACITY_QUANTILE]) for the reported capacity, which
 * a median would pull down as soon as half the pairs overlap outside use, the upper quartile only when three quarters do.
 */
class EstimateWindowCapacityUseCase {
    fun estimate(window: String, history: List<QuotaSample>, hours: List<HourCost>, now: Long): WindowCapacity {
        val costs = hours.associateBy { it.hourNumber }
        val samples = mutableListOf<Double>()
        history.filter { it.resetsAtMillis != null && it.observedAtMillis in (now - CAPACITY_HISTORY_MILLIS)..now && it.utilization.isFinite() && it.utilization in 0.0..1.0 }
            .groupBy { (it.resetsAtMillis!! + RESET_GRANULARITY_MILLIS / 2) / RESET_GRANULARITY_MILLIS }.values.forEach { period ->
                var anchor: QuotaSample? = null
                for (reading in period.sortedBy { it.observedAtMillis }) {
                    val start = anchor
                    if (start == null || reading.utilization < start.utilization) { anchor = reading; continue }
                    val rise = reading.utilization - start.utilization
                    if (rise + 1e-9 < MIN_CAPACITY_DELTA || reading.observedAtMillis - start.observedAtMillis < MIN_CAPACITY_PAIR_MILLIS) continue
                    pricedBetween(costs, start.observedAtMillis, reading.observedAtMillis)?.takeIf { it > 0 }?.let { samples += it / rise }
                    anchor = reading
                }
            }
        if (samples.size < MIN_CAPACITY_SAMPLES) return WindowCapacity(window, null, samples.size, null)
        val sorted = samples.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        // Nearest rank: with three samples this is the largest, with eight the sixth.
        val upper = sorted[(kotlin.math.ceil(CAPACITY_QUANTILE * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
        return WindowCapacity(window, median.roundToLong(), samples.size, upper.roundToLong())
    }
}

/** The quantile of the samples reported as a window's capacity: samples are mostly lower bounds (see [EstimateWindowCapacityUseCase]). */
const val CAPACITY_QUANTILE = .75

/** The API-equivalent of TeamCode's traffic between [from] and [to], hours prorated; null when an hour holds an unpriced model. */
internal fun pricedBetween(costs: Map<Long, HourCost>, from: Long, to: Long): Double? {
    var total = 0.0
    if (to <= from) return total
    for (hour in from / HOUR_MILLIS..(to - 1) / HOUR_MILLIS) {
        val overlap = minOf(to, (hour + 1) * HOUR_MILLIS) - maxOf(from, hour * HOUR_MILLIS)
        if (overlap <= 0) continue
        val cost = costs[hour] ?: continue
        if (cost.unpriced) return null
        total += cost.micros.toDouble() * overlap / HOUR_MILLIS
    }
    return total
}

/**
 * What one account's current window has consumed since it opened, split between TeamCode and everything else.
 * [observedShare] is the provider's reading (0.0 to 1.0), [teamCodeMicros] the API-equivalent TeamCode settled on this
 * account since [sinceMillis] (the window's opening: its reset minus its length), up to the reading at [observedAtMillis]
 * (hours prorated, so accurate to the hour; tokens settled after the reading and hours before the raw ledger existed
 * count as outside). With a learned capacity ([WindowCapacity.capacityMicros]),
 * [teamCodeShare] = [teamCodeMicros] ÷ capacity, [outsideShare] = observed − TeamCode (never negative) is the use of the
 * subscription outside TeamCode, worth [outsideMicros], and [remainingMicros] = (1 − observed) × capacity is what is left
 * of the window. Without a capacity, or with an unpriced model in the window, those are null: unknown, never guessed.
 */
data class WindowUsageSplit(
    val window: String, val sinceMillis: Long, val observedAtMillis: Long, val resetsAtMillis: Long, val observedShare: Double,
    val teamCodeMicros: Long?, val teamCodeShare: Double?, val outsideShare: Double?, val outsideMicros: Long?, val remainingMicros: Long?,
)

/**
 * Splits the newest reading of a window between TeamCode and outside use: the provider's percentage counts every use
 * of the subscription, the ledger only TeamCode's, so the difference is what was used elsewhere. Null without a reading
 * of a current period (known reset in the future) or for a window of unknown length.
 */
class SplitWindowUsageUseCase {
    fun split(window: String, history: List<QuotaSample>, hours: List<HourCost>, fullWindowMicros: Long?, now: Long): WindowUsageSplit? {
        val length = windowPeriodMillis(window) ?: return null
        val latest = history.filter { it.resetsAtMillis != null && it.resetsAtMillis > now && it.observedAtMillis <= now && it.utilization.isFinite() && it.utilization in 0.0..1.0 }
            .maxByOrNull { it.observedAtMillis } ?: return null
        val since = latest.resetsAtMillis!! - length
        val teamCode = pricedBetween(hours.associateBy { it.hourNumber }, since, latest.observedAtMillis)
        val full = fullWindowMicros?.takeIf { it > 0 }?.toDouble()
        val teamCodeShare = if (teamCode != null && full != null) teamCode / full else null
        val outside = teamCodeShare?.let { (latest.utilization - it).coerceAtLeast(0.0) }
        return WindowUsageSplit(window, since, latest.observedAtMillis, latest.resetsAtMillis, latest.utilization, teamCode?.roundToLong(),
            teamCodeShare, outside, outside?.let { (it * full!!).roundToLong() }, full?.let { ((1 - latest.utilization) * it).roundToLong() })
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

/**
 * [binding] is the window that gives the smaller daily figure. [capacityDailyMicros] is the account's full daily
 * capacity whatever is used now ([dailyCapacity]), null when it is unknown; [dailyMicros] is what is left of it today.
 */
data class AccountPoolShare(
    val accountId: String, val status: PoolContribution, val dailyMicros: Long, val binding: String? = null, val capacityDailyMicros: Long? = null,
)

/**
 * An account's full daily capacity in API-equivalent micro-dollars ([WindowCapacity.capacityMicros]), independent of what it has used: the smaller of a
 * day of five-hour windows (C5 × 24 / 5) and a seventh of its weekly window (C7 / 7), over the shared windows it
 * reports ([reported]). Null when it reports none or one of them has no estimate: unknown, never guessed.
 */
fun dailyCapacity(reported: Collection<String>, capacities: Map<String, WindowCapacity>): Long? {
    val windows = reported.filter { it in SHARED_CAPACITY_WINDOWS }.distinct().ifEmpty { return null }
    return windows.minOf { window ->
        val full = capacities[window]?.capacityMicros ?: return null
        if (window == "five_hour") full * 24.0 / 5 else full / 7.0
    }.roundToLong()
}

private val SHARED_CAPACITY_WINDOWS = setOf("five_hour", "seven_day")

/**
 * The home pool's daily capacity: [dailyMicros] in API-equivalent micro-dollars, [dailyUnits] in credits (null with
 * the [reason] when there is no automatic value, and then the manual budget applies).
 */
data class PoolSizeEstimate(
    val dailyMicros: Long, val dailyUnits: Long?, val accounts: List<AccountPoolShare>, val reason: String?,
    /** The sum of the known full daily capacities of the accounts that can serve ([AccountPoolShare.capacityDailyMicros]). */
    val capacityDailyMicros: Long = 0,
    /** Accounts that can serve but whose capacity is unknown: [capacityDailyMicros] leaves them out, so it is a floor. */
    val capacityUnknownAccounts: Int = 0,
) {
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
 * estimate, a learned window without a fresh reading, or no fresh observation at all, leaves the account without a figure,
 * and it is left out of the sum. When no account has a figure, or the sum is zero while an account is left out, there is
 * no automatic value ([PoolSizeEstimate.NO_ESTIMATE]). Credits = micro-dollars × [creditsPerMicro].
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
            val learned = capacities[id].orEmpty().associateBy { it.window }
            val capacity = dailyCapacity(observed.windows.map { it.window }, learned)
            val current = serving[id] ?: return@map AccountPoolShare(id, PoolContribution.UNAVAILABLE, 0, capacityDailyMicros = capacity)
            share(id, current, learned, now, pressureThreshold).copy(capacityDailyMicros = capacity)
        }
        val canServe = shares.filter { it.status != PoolContribution.UNAVAILABLE }
        val counted = shares.filter { it.status == PoolContribution.COUNTED }
        val micros = counted.sumOf { it.dailyMicros }
        val leftOut = shares.any { it.status == PoolContribution.NO_ESTIMATE || it.status == PoolContribution.NO_FRESH_OBSERVATION }
        val reason = when {
            // A zero sum while an account that can serve is left out would refuse everyone: no automatic value then.
            counted.isEmpty() || (micros == 0L && leftOut) -> PoolSizeEstimate.NO_ESTIMATE
            creditsPerMicro == null -> PoolSizeEstimate.NO_PRICED_USAGE
            else -> null
        }
        return PoolSizeEstimate(micros, if (reason == null) (micros * creditsPerMicro!!).roundToLong() else null, shares, reason,
            canServe.sumOf { it.capacityDailyMicros ?: 0 }, canServe.count { it.capacityDailyMicros == null })
    }

    private fun share(id: String, current: AccountHeadroom, capacities: Map<String, WindowCapacity>, now: Long, threshold: Double): AccountPoolShare {
        val observed = SHARED.mapNotNull { current.window(it) }
        if (observed.isEmpty()) return AccountPoolShare(id, PoolContribution.NO_FRESH_OBSERVATION, 0)
        // A window learned before but without a fresh reading (its reset just passed) could bind: no figure until it is read again.
        if (SHARED.any { window -> current.window(window) == null && capacities[window]?.fullWindowMicros != null })
            return AccountPoolShare(id, PoolContribution.NO_FRESH_OBSERVATION, 0)
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
    /** Per account, its current shared windows split between TeamCode and outside use ([SplitWindowUsageUseCase]). */
    val usage: Map<String, List<WindowUsageSplit>> = emptyMap(),
    /** The team's seven-day model mix, priced by token type, and its tokens per API-equivalent micro-dollar. */
    val mix: TokenMix = TokenMix(emptyList(), null),
) {
    /** [PoolSizeEstimate.capacityDailyMicros] in tokens at the team's mix; null without priced traffic. */
    val capacityDailyTokens: Long? get() = mix.tokensPerMicro?.let { (estimate.capacityDailyMicros * it).roundToLong() }
    /**
     * What the accounts' current weekly windows used outside TeamCode, in API-equivalent, over the accounts that know it;
     * null when none does. [outsideUnknownAccounts] counts the home pool accounts left out, so with a non-zero count it is a floor.
     */
    val outsideWeeklyMicros: Long? get() = weeklyOutside().mapNotNull { it }.takeIf { it.isNotEmpty() }?.sum()
    val outsideUnknownAccounts: Int get() = estimate.accounts.count { usage[it.accountId].orEmpty().firstOrNull { split -> split.window == "seven_day" }?.outsideMicros == null }
    private fun weeklyOutside() = estimate.accounts.map { usage[it.accountId].orEmpty().firstOrNull { split -> split.window == "seven_day" }?.outsideMicros }
}

interface PoolSizingService {
    /** The home pool's sizing as of now, computed on demand from the same data as the scheduled job; `team:read`. */
    suspend fun report(actor: Principal): PoolSizeReport
    /** The scheduled job: recompute and store every team's automatic value. */
    suspend fun refresh()
}
