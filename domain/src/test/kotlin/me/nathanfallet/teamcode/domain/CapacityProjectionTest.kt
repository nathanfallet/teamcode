package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import kotlin.test.*

/**
 * The case table of the aggregated projection: pacing slows down only when, at the team's current burn rate, every
 * usable account runs out before a reset gives capacity back, and only spaces admissions.
 */
class CapacityProjectionTest {
    private val now = 1_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val controller = PacingUseCase()
    private val projection = ProjectCapacityUseCase()
    private val headroom = AccountHeadroomUseCase()

    private fun record(id: String, state: AccountState = AccountState.READY) =
        AccountRecord(id, AccountIdentity("team", "anthropic", id, "org"), id, 1, false, null, 0, state)

    /** One observed window; [ratePerHour] is the learned rate as a share of the window per hour, null when not learned. */
    private fun window(id: String, name: String, used: Double, resetIn: Long?, ratePerHour: Double? = 0.0, observedAt: Long = now) = WindowPacing(
        AccountQuota(id, name, used, observedAt, resetIn?.let { now + it }),
        QuotaForecast(null, ratePerHour?.times(100), when (ratePerHour) { null -> QuotaForecast.State.UNKNOWN; 0.0 -> QuotaForecast.State.IDLE; else -> QuotaForecast.State.RESET_FIRST }))

    /** An account with both shared windows. */
    private fun account(
        id: String, fiveHour: Double, fiveHourResetIn: Long, weekly: Double, weeklyResetIn: Long,
        fiveHourRate: Double? = 0.0, weeklyRate: Double? = 0.0, state: AccountState = AccountState.READY,
    ) = AccountPacing(record(id, state), listOf(
        window(id, "five_hour", fiveHour, fiveHourResetIn, fiveHourRate), window(id, "seven_day", weekly, weeklyResetIn, weeklyRate)))

    private fun gap(accounts: List<AccountPacing>, threshold: Double = DEFAULT_QUOTA_PRESSURE_THRESHOLD): CapacityGap? {
        val observations = accounts.map { PoolAccountObservation(it.account, 0, it.windows.map { w -> w.quota }) }
        val rates = accounts.flatMap { it.windows }.filter { it.forecast.pointsPerHour != null }.groupBy { it.quota.window }
            .mapValues { (_, windows) -> windows.sumOf { it.forecast.pointsPerHour!! / 100 } }
        return projection.firstGap(headroom.usable(observations, now, threshold), headroom.exhausted(observations, now, threshold), rates, now, threshold)
    }

    private fun interval(vararg accounts: AccountPacing) = controller.advise(accounts.toList(), 0, now).minIntervalMillis

    @Test fun aSingleAccountWithHeadroomIsNotSlowedDown() {
        val single = account("a", .2, 3 * hour, .3, 5 * day, fiveHourRate = .1, weeklyRate = .005)
        assertNull(gap(listOf(single)))
        val advice = controller.advise(listOf(single), 0, now)
        assertEquals(Triple(Pace.NORMAL, 0, 0L), Triple(advice.pace, advice.maxConcurrent, advice.minIntervalMillis))
    }

    @Test fun anExhaustedAccountIsBridgedByAnotherUntilItsReset() {
        // "full" is at 100 % until its reset in one hour; "spare" lasts 0.55 / 0.3 = 1.83 h at the team rate.
        val full = account("full", 1.0, hour, .1, 6 * day)
        val spare = account("spare", .4, 4 * hour, .1, 6 * day, fiveHourRate = .3, weeklyRate = .01)
        assertNull(gap(listOf(full, spare)))
        assertEquals(0L, interval(full, spare))
        // At 1.1 per hour the spare account lasts half an hour: a half-hour gap until the reset, half the rate affordable.
        val short = spare.copy(windows = listOf(window("spare", "five_hour", .4, 4 * hour, 1.1), window("spare", "seven_day", .1, 6 * day, .01)))
        val found = gap(listOf(full, short))!!
        assertEquals(now + hour, found.endsAtMillis)
        assertEquals(now + Math.round(.55 / 1.1 * hour), found.startsAtMillis)
        assertEquals(SlowDownLevel.LIGHT.intervalMillis, interval(full, short))
    }

    @Test fun whenEveryAccountRunsOutTheLevelGrowsWithTheGapsLengthAndProximity() {
        // A single account whose five-hour window empties [lead] hours from now and resets [reset] hours from now.
        fun level(lead: Double, reset: Double): Long {
            val rate = .5
            return interval(AccountPacing(record("a"), listOf(
                window("a", "five_hour", .95 - rate * lead, Math.round(reset * hour), rate), window("a", "seven_day", .1, 6 * day))))
        }
        assertEquals(SlowDownLevel.LIGHT.intervalMillis, level(lead = 1.5, reset = 1.75))
        // A longer gap, the same lead.
        assertEquals(SlowDownLevel.MODERATE.intervalMillis, level(lead = 1.0, reset = 3.0))
        assertEquals(SlowDownLevel.SEVERE.intervalMillis, level(lead = 1.0, reset = 4.5))
        // The same one-hour gap, closer.
        assertEquals(SlowDownLevel.LIGHT.intervalMillis, level(lead = 1.8, reset = 2.8))
        assertEquals(SlowDownLevel.MODERATE.intervalMillis, level(lead = .5, reset = 1.5))
        assertEquals(SlowDownLevel.SEVERE.intervalMillis, level(lead = .2, reset = 1.2))
        // Spacing only, never a concurrency ceiling, and an explicit member limit is kept.
        val slow = controller.advise(listOf(account("a", .9, hour, .1, 6 * day, fiveHourRate = .5)), 0, now)
        assertEquals(Pace.SLOW_DOWN to 0, slow.pace to slow.maxConcurrent)
        assertEquals(3, controller.advise(listOf(account("a", .9, hour, .1, 6 * day, fiveHourRate = .5)), 3, now).maxConcurrent)
        assertEquals(SlowDownLevel.LIGHT, SlowDownLevel.of(.5))
        assertEquals(SlowDownLevel.MODERATE, SlowDownLevel.of(.25))
        assertEquals(SlowDownLevel.SEVERE, SlowDownLevel.of(.2499))
        assertEquals(SlowDownLevel.SEVERE, SlowDownLevel.of(Double.NaN))
    }

    @Test fun staggeredFiveHourAndSevenDayResetsOpenOrCloseAGap() {
        // The five-hour window empties in 20 minutes: a reset in 10 minutes closes the gap, one in 30 minutes leaves one.
        assertNull(gap(listOf(account("a", .9, 10 * 60_000L, .1, 6 * day, fiveHourRate = .15))))
        assertEquals(CapacityGap(now + 20 * 60_000L, now + 30 * 60_000L), gap(listOf(account("a", .9, 30 * 60_000L, .1, 6 * day, fiveHourRate = .15))))
        // The weekly window empties in 5 hours and resets in 3 days: a long gap the five-hour resets cannot close.
        val weekly = account("a", .1, 2 * hour, .9, 3 * day, weeklyRate = .01)
        assertEquals(CapacityGap(now + 5 * hour, now + 3 * day), gap(listOf(weekly)))
        assertEquals(SlowDownLevel.SEVERE.intervalMillis, interval(weekly))
        // Another account whose weekly window, at the threshold now, resets in 4 hours closes it.
        val staggered = account("b", .1, 2 * hour, .95, 4 * hour)
        assertNull(gap(listOf(weekly, staggered)))
        assertEquals(0L, interval(weekly, staggered))
    }

    @Test fun bothWindowsOfOneAccountBind() {
        // Plenty of five-hour quota, but the weekly window is nearly empty: the account stops when the weekly one does,
        // and the five-hour reset in an hour does not bring it back. Only the weekly reset does.
        val nearlyWeekly = account("a", .5, hour, .94, 2 * day, fiveHourRate = .1, weeklyRate = .02)
        assertEquals(CapacityGap(now + Math.round(.01 / .02 * hour), now + 2 * day), gap(listOf(nearlyWeekly)))
        // And the reverse: the five-hour window binds although the weekly one has room.
        val nearlyFiveHour = account("a", .85, hour, .1, 6 * day, fiveHourRate = .2, weeklyRate = .02)
        assertEquals(CapacityGap(now + Math.round(.1 / .2 * hour), now + hour), gap(listOf(nearlyFiveHour)))
    }

    @Test fun exhaustedAndDisconnectedAccountsAreNotCapacity() {
        val busy = account("busy", .85, hour, .1, 6 * day, fiveHourRate = .2)
        val disconnected = account("reconnect", .0, 4 * hour, .0, 6 * day, state = AccountState.RECONNECT_REQUIRED)
        val disabled = disconnected.copy(account = record("disabled").copy(disabled = true), windows = disconnected.windows.map { it.copy(quota = it.quota.copy(accountId = "disabled")) })
        val exhaustedLater = account("weekly-full", .0, 4 * hour, .97, 2 * day)
        assertEquals(CapacityGap(now + 30 * 60_000L, now + hour), gap(listOf(busy, disconnected, disabled, exhaustedLater)))
        assertEquals(Pace.SLOW_DOWN, controller.advise(listOf(busy, disconnected, disabled, exhaustedLater), 0, now).pace)
        // No usable account at all: pause.
        val pause = controller.advise(listOf(disconnected, exhaustedLater), 0, now)
        assertEquals(Triple(Pace.PAUSE, 0, PacingUseCase.PAUSE_INTERVAL_MILLIS), Triple(pause.pace, pause.maxConcurrent, pause.minIntervalMillis))
        assertEquals(listOf("weekly-full"), headroom.exhausted(listOf(busy, disconnected, exhaustedLater).map {
            PoolAccountObservation(it.account, 0, it.windows.map { w -> w.quota }) }, now).map { it.account.id })
    }

    @Test fun anAccountWithoutDataIsCapacityButNoReasonToSpeedUp() {
        val busy = account("busy", .85, hour, .1, 6 * day, fiveHourRate = .2)
        val unknown = AccountPacing(record("new"), emptyList())
        assertNull(gap(listOf(busy, unknown)))
        val advice = controller.advise(listOf(busy, unknown), 0, now)
        assertEquals(Triple(Pace.UNKNOWN, 0, 0L), Triple(advice.pace, advice.maxConcurrent, advice.minIntervalMillis))
        // A rate that cannot be learned is not a zero rate either.
        val unlearned = account("a", .2, hour, .2, 6 * day, fiveHourRate = null, weeklyRate = null)
        assertEquals(Pace.UNKNOWN, controller.advise(listOf(unlearned), 0, now).pace)
    }

    @Test fun aZeroBurnRateNeverSlowsDown() {
        val idle = account("a", .9, hour, .9, 6 * day)
        assertNull(gap(listOf(idle)))
        val advice = controller.advise(listOf(idle), 0, now)
        assertEquals(Pace.CAN_INCREASE to 0L, advice.pace to advice.minIntervalMillis)
        assertFalse("up to 0" in advice.message, advice.message)
    }

    @Test fun aBriefPeakIsAveragedOverTheLastHour() {
        // Flat at 10 % for 55 minutes, then +10 % in the last five minutes: the learned rate is 10 % per hour, not 120 %.
        val history = ForecastHistoryUseCase()
        fun learned(name: String, peak: Double, reset: Long) = history.evaluate((0..12).map { index ->
            AccountQuota("a", name, if (index == 12) .1 + peak else .1, now - hour + index * 5 * 60_000L, now + reset)
        }, now)!!
        val fiveHour = learned("five_hour", .1, hour)
        assertEquals(10.0, fiveHour.forecast.pointsPerHour!!, 1e-9)
        val peaked = AccountPacing(record("a"), listOf(fiveHour, learned("seven_day", .0, 6 * day)))
        assertNull(gap(listOf(peaked)))
        assertEquals(0L, controller.advise(listOf(peaked), 0, now).minIntervalMillis)
    }

    @Test fun noUsableAccountPausesAndNoAccountIsUnknown() {
        assertEquals(Pace.PAUSE, controller.advise(listOf(account("a", .95, hour, .1, 6 * day), account("b", .1, hour, 1.0, 6 * day)), 0, now).pace)
        assertEquals(Pace.PAUSE, controller.advise(listOf(account("a", .1, hour, .1, 6 * day, state = AccountState.RECONNECT_REQUIRED)), 0, now).pace)
        val none = controller.advise(emptyList(), 0, now)
        assertEquals(Triple(Pace.UNKNOWN, 0, 0L), Triple(none.pace, none.maxConcurrent, none.minIntervalMillis))
        assertFailsWith<IllegalArgumentException> { controller.advise(emptyList(), -1, now) }
    }

    @Test fun aResetAlreadyInThePastIsNotCurrentCapacityOrExhaustion() {
        // An observation at 100 % whose reset has passed no longer bars the account; its window is unknown again.
        val reset = AccountPacing(record("a"), listOf(
            window("a", "five_hour", 1.0, -1, .5, observedAt = now - hour), window("a", "seven_day", .1, 6 * day)))
        assertNull(gap(listOf(reset)))
        assertEquals(Pace.UNKNOWN, controller.advise(listOf(reset), 0, now).pace)
        assertTrue(headroom.exhausted(listOf(PoolAccountObservation(reset.account, 0, reset.windows.map { it.quota })), now).isEmpty())
    }

    @Test fun aGapThatWouldStartExactlyAtAResetIsNoGap() {
        // At threshold 1.0, 0.1 left at 0.1 per hour empties exactly at the reset one hour away.
        val exact = account("a", .9, hour, .0, 6 * day, fiveHourRate = .1)
        assertNull(gap(listOf(exact), threshold = 1.0))
        // One millisecond earlier, the reset leaves a one-millisecond gap.
        val early = account("a", .9, hour + 1, .0, 6 * day, fiveHourRate = .1)
        assertEquals(CapacityGap(now + hour, now + hour + 1), gap(listOf(early), threshold = 1.0))
        // Another account's reset at that same instant also closes it.
        val other = account("b", 1.0, hour, .0, 6 * day)
        assertNull(gap(listOf(early, other), threshold = 1.0))
        assertEquals(1.0, CapacityGap(now, now).affordableShare(now))
        assertEquals(0.0, CapacityGap(now, now + hour).affordableShare(now))
    }

    @Test fun easingWaitsAndTighteningAppliesAtOnce() {
        val hysteresis = PacingHysteresisUseCase()
        fun at(lead: Double, reset: Double) = controller.advise(listOf(AccountPacing(record("a"), listOf(
            window("a", "five_hour", .95 - .5 * lead, Math.round(reset * hour), .5), window("a", "seven_day", .1, 6 * day)))), 0, now)
        val severe = at(.2, 1.2)
        val light = at(1.5, 1.75)
        fun memory(advice: PacingAdvice, recovery: PacingRecovery? = null) = PacingMemory(advice, now + 1_000_000, recovery)
        assertEquals(severe, hysteresis.decide(memory(light), severe, now).advice, "A stronger level applies at once")
        val pending = hysteresis.decide(memory(severe), light, now)
        assertEquals(severe, pending.advice)
        assertEquals(severe, hysteresis.decide(memory(severe, pending.recovery), light, now + 119_999).advice)
        assertEquals(light, hysteresis.decide(memory(severe, pending.recovery), light, now + 120_000).advice)
    }

    /**
     * Replays the production observations of 2026-10-21 17:50Z (four accounts, the weekly window driving every
     * per-account forecast to SLOW_DOWN, one request every ten seconds). Together the three usable accounts last about
     * eleven hours at the team's weekly rate, and the exhausted one comes back 24 hours from now: a gap, graded on
     * its distance and length, not the per-account severe throttle.
     */
    @Test fun replaysTheProductionStateOfTheIncident() {
        val minute = 60_000L
        val accounts = listOf(
            AccountPacing(record("28c8"), listOf(window("28c8", "five_hour", .0, 3 * hour + 40 * minute, null), window("28c8", "seven_day", 1.0, day + 10 * minute, null))),
            account("87ee", .06, 4 * hour + 10 * minute, .13, 6 * day + 14 * hour + 10 * minute, fiveHourRate = .073, weeklyRate = .011),
            AccountPacing(record("ef7f"), listOf(window("ef7f", "seven_day", .50, 5 * day + 19 * hour + 10 * minute, .059))),
            account("f85c", .77, 2 * hour + 10 * minute, .81, 3 * day + 9 * hour + 10 * minute, fiveHourRate = .2, weeklyRate = .06),
        )
        val found = gap(accounts)!!
        assertTrue(found.startsAtMillis - now in 10 * hour..12 * hour, "${(found.startsAtMillis - now) / minute} minutes")
        assertEquals(now + day + 10 * minute, found.endsAtMillis)
        assertEquals(SlowDownLevel.MODERATE.intervalMillis, interval(*accounts.toTypedArray()))
    }
}
