package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.routing.TokenUsage
import kotlin.test.*

/**
 * The capacity of the subscriptions, read from the provider's percentages and calibrated on TeamCode's own traffic:
 * the full daily capacity of each account, the use outside TeamCode, unknown accounts and the conversion into tokens.
 */
class SubscriptionCapacityTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_000 * day + 10 * hour
    private val weekStart = now - 58 * hour
    private val weekReset = weekStart + 7 * day

    /** [micros] settled in each hour from [from] to [now]. */
    private fun hours(from: Long, micros: Long) = (from / hour until now / hour).map { HourCost(it, micros) }

    @Test fun theWindowIsSplitBetweenTeamCodeAndOutsideUse() {
        // A $1,000 weekly window. TeamCode settled $5 an hour for the 58 hours since it opened: $290, 29 points.
        // The provider reads 40%: 11 points, $110, were used outside TeamCode, and 60% ($600) is left.
        val history = listOf(QuotaSample(.30, now - 5 * hour, weekReset), QuotaSample(.40, now, weekReset))
        val split = SplitWindowUsageUseCase().split("seven_day", history, hours(weekStart, 5_000_000), 1_000_000_000, now)!!
        assertEquals(weekStart, split.sinceMillis, "The window opened one period before its reset")
        assertEquals(.40, split.observedShare)
        assertEquals(290_000_000, split.teamCodeMicros)
        assertEquals(.29, split.teamCodeShare!!, 1e-9)
        assertEquals(.11, split.outsideShare!!, 1e-9)
        assertEquals(110_000_000, split.outsideMicros)
        assertEquals(600_000_000, split.remainingMicros)
    }

    @Test fun teamCodeAloneLeavesNoOutsideUse() {
        // TeamCode explains more than the reading (whole-point rounding, hourly buckets): outside use is zero, never negative.
        val history = listOf(QuotaSample(.28, now, weekReset))
        val split = SplitWindowUsageUseCase().split("seven_day", history, hours(weekStart, 5_000_000), 1_000_000_000, now)!!
        assertEquals(0.0, split.outsideShare)
        assertEquals(0, split.outsideMicros)
    }

    @Test fun withoutACapacityOrAPriceTheSplitIsUnknown() {
        val history = listOf(QuotaSample(.40, now, weekReset))
        val unlearned = SplitWindowUsageUseCase().split("seven_day", history, hours(weekStart, 5_000_000), null, now)!!
        assertEquals(.40, unlearned.observedShare, "The provider's reading is still shown")
        assertEquals(290_000_000, unlearned.teamCodeMicros)
        assertNull(unlearned.teamCodeShare)
        assertNull(unlearned.outsideShare)
        assertNull(unlearned.remainingMicros)
        val unpriced = hours(weekStart, 5_000_000).mapIndexed { index, cost -> if (index == 3) cost.copy(unpriced = true) else cost }
        val split = SplitWindowUsageUseCase().split("seven_day", history, unpriced, 1_000_000_000, now)!!
        assertNull(split.teamCodeMicros, "An unpriced model in the window makes TeamCode's part unknown")
        assertNull(split.outsideShare)
        assertEquals(600_000_000, split.remainingMicros, "What is left only needs the capacity and the reading")
    }

    @Test fun anAccountWithoutAReadingOfACurrentWindowHasNoSplit() {
        val splitter = SplitWindowUsageUseCase()
        assertNull(splitter.split("seven_day", emptyList(), emptyList(), 1_000_000_000, now))
        assertNull(splitter.split("seven_day", listOf(QuotaSample(.4, now - day, now - hour)), emptyList(), 1_000_000_000, now), "That window has reset since")
        assertNull(splitter.split("seven_day", listOf(QuotaSample(.4, now, null)), emptyList(), 1_000_000_000, now), "Unknown reset")
    }

    @Test fun calibrationAndSplitAgreeOnTheSameReadings() {
        // TeamCode spends $12 an hour and the provider rises 1.2 points an hour from it: a $1,000 window. From hour 35
        // someone else adds 1.2 points an hour. The seven clean pairs calibrate $1,000 (the four shared ones read $500);
        // the split then attributes the extra 24 points to outside use.
        val readings = (0..55 step 5).map { h -> QuotaSample(.012 * h + if (h > 35) .012 * (h - 35) else 0.0, weekStart + h * hour, weekReset) }
        val costs = hours(weekStart, 12_000_000)
        val capacity = EstimateWindowCapacityUseCase().estimate("seven_day", readings, costs, now)
        assertEquals(11, capacity.samples)
        assertEquals(1_000_000_000, capacity.fullWindowMicros, "The upper quartile keeps the clean pairs")
        val split = SplitWindowUsageUseCase().split("seven_day", readings, costs, capacity.fullWindowMicros, now)!!
        assertEquals(.66, split.teamCodeShare!!, 1e-9)
        assertEquals(.24, split.outsideShare!!, 1e-9)
        assertEquals(240_000_000, split.outsideMicros)
    }

    private fun account(id: String, disabled: Boolean = false) =
        AccountRecord(id, AccountIdentity("team", "anthropic", id, "org"), id, 1, disabled, null, 0, AccountState.READY)
    private fun quota(id: String, window: String, utilization: Double, resetsIn: Long) = AccountQuota(id, window, utilization, now, now + resetsIn)
    private fun learned(fiveHour: Long?, sevenDay: Long?) = listOf(WindowCapacity("five_hour", fiveHour, if (fiveHour == null) 1 else 5),
        WindowCapacity("seven_day", sevenDay, if (sevenDay == null) 0 else 4))

    @Test fun capacityIsTheFullDailyRateWhateverIsUsedAndUnknownAccountsAreCountedApart() {
        // Max-like A: five-hour $300 (24/5 → $1,440 a day), weekly $2,100 (→ $300 a day): $300 a day, whatever it used.
        // B is at 99% of its week: today it has almost nothing left, but its capacity is the same $300 a day.
        // C has no learned weekly window: unknown. D is disabled: not part of the capacity.
        val accounts = listOf(
            PoolAccountObservation(account("a"), 0, listOf(quota("a", "five_hour", .1, hour), quota("a", "seven_day", .2, 5 * day))),
            PoolAccountObservation(account("b"), 0, listOf(quota("b", "five_hour", .1, hour), quota("b", "seven_day", .99, 5 * day))),
            PoolAccountObservation(account("c"), 0, listOf(quota("c", "five_hour", .1, hour), quota("c", "seven_day", .3, 5 * day))),
            PoolAccountObservation(account("d", disabled = true), 0, listOf(quota("d", "five_hour", .1, hour), quota("d", "seven_day", .3, 5 * day))),
        )
        val capacities = mapOf("a" to learned(300_000_000, 2_100_000_000), "b" to learned(300_000_000, 2_100_000_000),
            "c" to learned(300_000_000, null), "d" to learned(300_000_000, 2_100_000_000))
        val estimate = SizeSharedPoolUseCase().size(accounts, capacities, 3.0, now, .95)
        val byId = estimate.accounts.associateBy { it.accountId }
        assertEquals(300_000_000, byId.getValue("a").capacityDailyMicros)
        assertEquals(300_000_000, byId.getValue("b").capacityDailyMicros)
        assertEquals(0, byId.getValue("b").dailyMicros, "Nothing left before its reset")
        assertNull(byId.getValue("c").capacityDailyMicros, "Unknown, not guessed")
        assertEquals(300_000_000, byId.getValue("d").capacityDailyMicros, "Still reported per account")
        assertEquals(600_000_000, estimate.capacityDailyMicros, "a + b; the disabled account cannot serve")
        assertEquals(1, estimate.capacityUnknownAccounts, "c can serve but is not learned: the total is a floor")
        // What is left today is much smaller than the capacity: a's 75 weekly points over 5 days.
        assertEquals((0.75 * 2_100_000_000 / 5).toLong(), byId.getValue("a").dailyMicros)
    }

    @Test fun aFiveHourOnlyAccountUsesItsFiveHourRateAndNoWindowMeansUnknown() {
        assertEquals(1_440_000_000, dailyCapacity(listOf("five_hour"), learned(300_000_000, null).associateBy { it.window }))
        assertNull(dailyCapacity(emptyList(), learned(300_000_000, 2_100_000_000).associateBy { it.window }))
        assertNull(dailyCapacity(listOf("five_hour", "seven_day"), emptyMap()))
        assertEquals(300_000_000, dailyCapacity(listOf("five_hour", "seven_day", "model:fable"), learned(300_000_000, 2_100_000_000).associateBy { it.window }),
            "Model windows do not size the shared capacity")
    }

    @Test fun theMixPricesCacheApartAndConvertsCapacityIntoTokens() {
        val opus = ApiPrice("anthropic", "claude-opus-5-5", 0, 4_000_000, 5_000_000, 200_000, 20_000_000)
        val prices = ApiPriceTable(listOf(opus))
        // 1M input ($4), 1M cache writes ($5), 100M cache reads ($20), 1M output ($20): $49 for 103M tokens.
        val usage = listOf(
            PricedTokens("anthropic", "claude-opus-5-5", 1, TokenUsage(input = 500_000, output = 500_000, cacheRead = 50_000_000, cacheCreation = 500_000)),
            PricedTokens("anthropic", "claude-opus-5-5", 2, TokenUsage(input = 500_000, output = 500_000, cacheRead = 50_000_000, cacheCreation = 500_000)),
            PricedTokens("codex", "gpt-5", 2, TokenUsage(input = 1_000_000)),
        )
        val mix = TokenMix.of(prices, usage)
        val priced = mix.models.first()
        assertEquals(PricedCost(4_000_000L, 5_000_000L, 20_000_000L, 20_000_000L), priced.cost)
        assertEquals(103_000_000L, priced.tokens.allTokens)
        assertNull(mix.models.single { it.provider == "codex" }.cost, "An unpriced model has no cost, and no part in the conversion")
        assertEquals(103.0 / 49, mix.tokensPerMicro!!, 1e-9)
        val report = PoolSizeReport(false, 0, null, null, 0, false,
            PoolSizeEstimate(0, null, emptyList(), null, capacityDailyMicros = 300_000_000), null, emptyList(), emptyMap(), mix = mix)
        assertEquals(Math.round(300_000_000 * 103.0 / 49), report.capacityDailyTokens)
        assertNull(report.copy(mix = TokenMix.of(ApiPriceTable(emptyList()), usage)).capacityDailyTokens, "Without prices, no conversion")
    }

    @Test fun outsideUseAddsUpOverTheWeeklyWindowsThatKnowIt() {
        fun split(window: String, outside: Long?) = WindowUsageSplit(window, 0, now, weekReset, .5, 1, .1, outside?.let { .1 }, outside, 1)
        val report = PoolSizeReport(false, 0, null, null, 0, false, PoolSizeEstimate(0, null, emptyList(), null), null, emptyList(), emptyMap(),
            usage = mapOf("a" to listOf(split("five_hour", 7), split("seven_day", 100)), "b" to listOf(split("seven_day", 50)), "c" to listOf(split("seven_day", null))))
        assertEquals(150L, report.outsideWeeklyMicros, "Weekly windows only; unknown ones are left out")
        assertNull(report.copy(usage = mapOf("c" to listOf(split("seven_day", null)))).outsideWeeklyMicros)
    }
}
