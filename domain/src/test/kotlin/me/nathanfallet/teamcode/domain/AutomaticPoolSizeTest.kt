package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import kotlin.test.*

/**
 * The case table of the automatic shared pool size (ADR 0002, step 2). Capacities are in API-equivalent micro-dollars;
 * the pool is in credits through the team's observed credits-per-micro-dollar ratio (2 here).
 */
class AutomaticPoolSizeTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_000 * day
    private val threshold = .95
    private val ratio = 2.0

    private fun account(id: String, state: AccountState = AccountState.READY, disabled: Boolean = false) =
        AccountRecord(id, AccountIdentity("team", "anthropic", id, "org"), id, 1, disabled, null, 0, state)
    private fun quota(id: String, window: String, utilization: Double, resetsIn: Long, observedAgo: Long = 0) =
        AccountQuota(id, window, utilization, now - observedAgo, now + resetsIn)
    private fun observed(record: AccountRecord, vararg windows: AccountQuota, cooldownUntil: Long = 0) = PoolAccountObservation(record, cooldownUntil, windows.toList())
    private fun capacity(fiveHour: Long?, sevenDay: Long?) = listOfNotNull(
        fiveHour?.let { WindowCapacity("five_hour", it, 3) }, sevenDay?.let { WindowCapacity("seven_day", it, 3) })
    private fun size(accounts: List<PoolAccountObservation>, capacities: Map<String, List<WindowCapacity>>, creditsPerMicro: Double? = ratio) =
        SizeSharedPoolUseCase().size(accounts, capacities, creditsPerMicro, now, threshold)

    /** Weekly util 25% (70 points left), reset in 3.5 days: 0.70 × 200M / 3.5 = 40M a day; the five-hour term is 10M × 0.95 × 24/5 = 45.6M. */
    private val plusAccount = observed(account("plus"), quota("plus", "five_hour", .2, 2 * hour), quota("plus", "seven_day", .25, 3 * day + 12 * hour))
    private val plusCapacity = "plus" to capacity(10_000_000, 200_000_000)

    @Test fun noAccountsGiveNoAutomaticValue() {
        val estimate = size(emptyList(), emptyMap())
        assertNull(estimate.dailyUnits, "Without an account the manual budget applies")
        assertEquals(PoolSizeEstimate.NO_ACCOUNTS, estimate.reason)
    }

    @Test fun oneAccountGivesTheSmallerOfItsWeeklyAndFiveHourDailyCapacity() {
        val estimate = size(listOf(plusAccount), mapOf(plusCapacity))
        val share = estimate.accounts.single()
        assertEquals(PoolContribution.COUNTED, share.status)
        assertEquals("seven_day", share.binding)
        assertEquals(40_000_000, share.dailyMicros)
        assertEquals(80_000_000, estimate.dailyUnits, "40M micro-dollars a day at 2 credits per micro-dollar")
        assertNull(estimate.reason)
    }

    @Test fun severalAccountsWithDifferentPlansAndWindowsAddUp() {
        // Max-like: weekly 50% used, reset in 2 days: 0.45 × 1000M / 2 = 225M; five-hour 50M × 0.95 × 4.8 = 228M.
        val max = observed(account("max"), quota("max", "five_hour", .7, hour), quota("max", "seven_day", .5, 2 * day))
        // Plenty of weekly left (0.90 × 400M / 6 = 60M): the five-hour rate binds, 10M × 0.95 × 4.8 = 45.6M.
        val fresh = observed(account("fresh"), quota("fresh", "five_hour", .0, 5 * hour), quota("fresh", "seven_day", .05, 6 * day))
        val estimate = size(listOf(plusAccount, max, fresh), mapOf(plusCapacity,
            "max" to capacity(50_000_000, 1_000_000_000), "fresh" to capacity(10_000_000, 400_000_000)))
        assertEquals(mapOf("plus" to 40_000_000L, "max" to 225_000_000L, "fresh" to 45_600_000L), estimate.accounts.associate { it.accountId to it.dailyMicros })
        assertEquals("five_hour", estimate.accounts.single { it.accountId == "fresh" }.binding)
        assertEquals(310_600_000, estimate.dailyMicros)
        assertEquals(621_200_000, estimate.dailyUnits)
    }

    @Test fun exhaustedAndUnavailableAccountsAddNothing() {
        val accounts = listOf(plusAccount,
            observed(account("weekly-full"), quota("weekly-full", "five_hour", .1, hour), quota("weekly-full", "seven_day", .96, 3 * day)),
            observed(account("reconnect", AccountState.RECONNECT_REQUIRED), quota("reconnect", "five_hour", .1, hour), quota("reconnect", "seven_day", .1, 3 * day)),
            observed(account("disabled", disabled = true), quota("disabled", "five_hour", .1, hour)),
            observed(account("cooling"), quota("cooling", "five_hour", .1, hour), cooldownUntil = now + 60_000),
            observed(account("pending", AccountState.PENDING_HANDOVER)))
        val capacities = mapOf(plusCapacity) + listOf("weekly-full", "reconnect", "disabled", "cooling", "pending").associateWith { capacity(10_000_000, 200_000_000) }
        val estimate = size(accounts, capacities)
        val byId = estimate.accounts.associateBy { it.accountId }
        assertEquals(0, byId.getValue("weekly-full").dailyMicros, "A weekly window at the threshold has nothing left until its reset (3 days away)")
        assertEquals(PoolContribution.COUNTED, byId.getValue("weekly-full").status)
        for (id in listOf("reconnect", "disabled", "cooling", "pending")) {
            assertEquals(PoolContribution.UNAVAILABLE, byId.getValue(id).status, id)
            assertEquals(0, byId.getValue(id).dailyMicros, id)
        }
        assertEquals(80_000_000, estimate.dailyUnits, "Only the usable account counts")
    }

    @Test fun aFiveHourWindowAtTheThresholdStillCountsItsSteadyRate() {
        // Excluded from admission until its five-hour reset, but the day ahead holds several five-hour windows.
        val busy = observed(account("busy"), quota("busy", "five_hour", .97, hour), quota("busy", "seven_day", .25, 3 * day + 12 * hour))
        val share = size(listOf(busy), mapOf("busy" to capacity(10_000_000, 200_000_000))).accounts.single()
        assertEquals(PoolContribution.COUNTED, share.status)
        assertEquals(40_000_000, share.dailyMicros)
    }

    @Test fun aWeeklyWindowResettingWithinTheDayCountsWhatComesBack() {
        // Full now, reset in 6 hours: the 18 hours after it hold 0.95 × 200M × 18 / 168 = 20,357,143.
        val full = observed(account("full"), quota("full", "five_hour", .1, hour), quota("full", "seven_day", .96, 6 * hour))
        val share = size(listOf(full), mapOf("full" to capacity(10_000_000, 200_000_000))).accounts.single()
        assertEquals("seven_day", share.binding)
        assertEquals(20_357_143, share.dailyMicros)
    }

    @Test fun accountsWithoutAnEstimateAreLeftOutAndAnUnobservedWindowDoesNotBind() {
        val unestimated = observed(account("new"), quota("new", "five_hour", .1, hour), quota("new", "seven_day", .1, 3 * day))
        val stale = observed(account("stale"), quota("stale", "five_hour", .1, -hour))
        val weeklyOnlyEstimate = observed(account("half"), quota("half", "five_hour", .1, hour), quota("half", "seven_day", .1, 3 * day))
        // A provider that reports no weekly window: only the five-hour rate applies (10M × 0.95 × 4.8 = 45.6M).
        val fiveHourOnly = observed(account("codex"), quota("codex", "five_hour", .1, hour))
        val estimate = size(listOf(unestimated, stale, weeklyOnlyEstimate, fiveHourOnly), mapOf(
            "stale" to capacity(10_000_000, 200_000_000), "half" to capacity(null, 200_000_000), "codex" to capacity(10_000_000, null)))
        val byId = estimate.accounts.associateBy { it.accountId }
        assertEquals(PoolContribution.NO_ESTIMATE, byId.getValue("new").status)
        assertEquals(PoolContribution.NO_FRESH_OBSERVATION, byId.getValue("stale").status, "A past reset says nothing about the current window")
        assertEquals(PoolContribution.NO_ESTIMATE, byId.getValue("half").status, "An observed window without an estimate could bind: no figure")
        assertEquals(PoolContribution.COUNTED, byId.getValue("codex").status)
        assertEquals(45_600_000, byId.getValue("codex").dailyMicros)
        assertEquals(0, byId.getValue("new").dailyMicros)
        assertNull(estimate.reason, "Accounts without a figure are left out of the sum; the others still size the pool")
        assertEquals(45_600_000, estimate.dailyMicros, "Only the estimated account counts")
        assertEquals(91_200_000, estimate.dailyUnits)
        assertEquals(91_200_000, size(listOf(fiveHourOnly), mapOf("codex" to capacity(10_000_000, null))).dailyUnits)
        val none = size(listOf(unestimated, stale, weeklyOnlyEstimate), mapOf("stale" to capacity(10_000_000, 200_000_000), "half" to capacity(null, 200_000_000)))
        assertNull(none.dailyUnits, "When no account has an estimate the manual budget applies")
        assertEquals(PoolSizeEstimate.NO_ESTIMATE, none.reason)
    }

    @Test fun noEstimateAtAllOrNoPricedUsageGiveNoAutomaticValue() {
        val unestimated = observed(account("new"), quota("new", "five_hour", .1, hour))
        assertEquals(PoolSizeEstimate.NO_ESTIMATE, size(listOf(unestimated), emptyMap()).reason)
        assertNull(size(listOf(unestimated), emptyMap()).dailyUnits)
        val unpriced = size(listOf(plusAccount), mapOf(plusCapacity), creditsPerMicro = null)
        assertEquals(40_000_000, unpriced.dailyMicros, "The capacity is still shown in API-equivalent")
        assertNull(unpriced.dailyUnits)
        assertEquals(PoolSizeEstimate.NO_PRICED_USAGE, unpriced.reason)
    }

    @Test fun checkboxOffKeepsTheManualBudgetAndSwitchingOnAndOffMovesOnlyTheTotal() {
        val manual = 1_000_000L
        val automatic = 80_000_000L
        val computedAt = now - 10 * 60_000
        assertEquals(manual, AutomaticPoolSize.effectiveBudget("", manual, automatic, computedAt, enabled = false, now), "Off: the hand-typed budget, whatever was computed")
        assertEquals(automatic, AutomaticPoolSize.effectiveBudget("", manual, automatic, computedAt, enabled = true, now), "On: the computed value")
        assertEquals(manual, AutomaticPoolSize.effectiveBudget("", manual, automatic, computedAt, enabled = false, now), "Off again: back to the manual budget, unchanged")
        assertEquals(manual, AutomaticPoolSize.effectiveBudget("", manual, null, null, enabled = true, now), "On without any computed value")
        assertEquals(manual, AutomaticPoolSize.effectiveBudget("", manual, automatic, now - hour - 1, enabled = true, now), "A value older than an hour is stale")
        assertEquals(manual, AutomaticPoolSize.effectiveBudget("support", manual, automatic, computedAt, enabled = true, now), "Named pools stay manual")
        assertEquals(0, AutomaticPoolSize.effectiveBudget("", manual, 0, computedAt, enabled = true, now), "Zero capacity is a real value")
    }

    @Test fun theSplitBetweenMembersStaysByWeight() {
        // Same formula as admission: pool × weight / total weight, then the optional personal cap.
        val pool = AutomaticPoolSize.effectiveBudget("", 1_000_000, 110_000_000, now, enabled = true, now)
        assertEquals(listOf(50_000_000L, 50_000_000L, 10_000_000L), listOf(5L, 5L, 1L).map { pool * it / 11 })
    }

    @Test fun aNewValueMovesAtMostTwentyPercentPerHour() {
        assertEquals(100, AutomaticPoolSize.smooth(null, null, 100, now), "The first value is the estimate")
        assertEquals(120, AutomaticPoolSize.smooth(100, now - hour, 1_000, now))
        assertEquals(80, AutomaticPoolSize.smooth(100, now - hour, 10, now))
        assertEquals(103, AutomaticPoolSize.smooth(100, now - 10 * 60_000, 1_000, now), "Ten minutes allow a sixth of the hourly step")
        assertEquals(1_100, AutomaticPoolSize.smooth(1_000, now - 30 * 60_000, 2_000, now), "Half an hour allows half the step: 20% of 1000 / 2 = 100")
        assertEquals(102, AutomaticPoolSize.smooth(100, now - 10 * 60_000, 102, now), "Within the step the estimate applies as is")
        assertEquals(5_000, AutomaticPoolSize.smooth(100, now - 3 * hour, 5_000, now), "A value older than two hours restarts from the estimate")
        assertEquals(1_000, AutomaticPoolSize.smooth(0, now - hour, 1_000, now), "Nothing scales from zero: the estimate applies, so the pool grows back after a full reset")
    }
}
