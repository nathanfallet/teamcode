package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.policy.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class PolicyAndPacingTest {
    @Test fun deniedModelsCannotHideBehindAliasesAndEffortsAreExplicit() {
        val checker = CheckModelPolicyUseCase()
        val policy = ModelPolicy(allowedModels = listOf("opus", "claude-*"), allowedEfforts = listOf("low", "budgeted"), maximumThinkingTokens = 1024)
        assertEquals("model_not_allowed", assertFailsWith<TeamFailure> { checker.check(policy, "opus", "claude-FABLE-test", "low", null) }.code)
        assertEquals("effort_not_allowed", assertFailsWith<TeamFailure> { checker.check(policy, "opus", "claude-opus-test", "high", null) }.code)
        assertFailsWith<TeamFailure> { checker.check(policy, "opus", "claude-opus-test", null, null) }
        assertFailsWith<TeamFailure> { checker.check(policy, "opus", "claude-opus-test", "low", 2048) }
        checker.check(policy, "opus", "claude-opus-test", "low", null)
        checker.check(policy, "opus", "claude-opus-test", null, 1024)
    }
    @Test fun trendsSlowHighBurnAndOnlyFreshCompleteWindowsPermitAcceleration() {
        val history = ForecastHistoryUseCase()
        fun windows(values: List<Double>, now: Long = 120_000) = listOf("five_hour", "seven_day").map { window ->
            history.evaluate(values.mapIndexed { i, used -> AccountQuota("account", window, used, i * 60_000L, 3_600_000) }, now)!!
        }
        val controller = PacingUseCase()
        val record = me.nathanfallet.teamcode.domain.accounts.AccountRecord("account", me.nathanfallet.teamcode.domain.accounts.AccountIdentity("team", "anthropic", "account", "org"),
            "account", 1, false, null, 0, me.nathanfallet.teamcode.domain.accounts.AccountState.READY)
        fun account(windows: List<WindowPacing>) = listOf(AccountPacing(record, windows))
        assertEquals(Pace.SLOW_DOWN, controller.advise(account(windows(listOf(.1, .2, .3))), 5, 120_000).pace)
        val spare = controller.advise(account(windows(listOf(.1, .1, .1))), 5, 120_000)
        assertEquals(Pace.CAN_INCREASE, spare.pace)
        assertEquals(5, spare.maxConcurrent)
        assertEquals(Pace.UNKNOWN, controller.advise(account(windows(listOf(.1, .1, .1)).take(1)), 5, 120_000).pace)
        assertEquals(Pace.UNKNOWN, controller.advise(account(windows(listOf(.1, .1, .1), 600_000)), 5, 600_000).pace)
        assertEquals(Pace.UNKNOWN, controller.advise(account(windows(listOf(.1, .2, .05))), 5, 120_000).pace)
        assertEquals(Pace.PAUSE, controller.advise(account(windows(listOf(.95, .95, .95))), 5, 120_000).pace)
    }
}
