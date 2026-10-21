package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.InferenceErrors
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import kotlin.test.*

class PoolAvailabilityTest {
    private val describe = DescribePoolUnavailableUseCase()
    // 2026-10-21 12:00:00 UTC
    private val now = 1_792_584_000_000L

    @Test fun accountsAwaitingHandoverAreNotRetriedAndSayWhatToDo() {
        val failure = describe.failure(PoolStanding("anthropic", pendingHandover = 6), now)
        assertEquals("pool_unavailable", failure.code)
        assertEquals(503, failure.status)
        assertFalse(failure.retryable)
        assertNull(failure.retryAfterSeconds)
        assertEquals("No Claude account in your TeamCode pool is usable. 6 accounts are waiting for a handover: an administrator " +
            "must confirm it with Handover on the dashboard Accounts page, or with `teamcode accounts handover <id> --confirm`. " +
            "Retrying will not help until this is fixed.", failure.message)
    }

    @Test fun everyBlockingCauseIsCountedWithoutNames() {
        val failure = describe.failure(PoolStanding("codex", pendingHandover = 1, reconnectRequired = 2, disabled = 1), now)
        assertEquals(503, failure.status)
        assertFalse(failure.retryable)
        assertEquals("No Codex account in your TeamCode pool is usable. 1 account is waiting for a handover: an administrator " +
            "must confirm it with Handover on the dashboard Accounts page, or with `teamcode accounts handover <id> --confirm`. " +
            "2 accounts are disconnected: an administrator must reconnect them with `teamcode accounts add`. " +
            "1 account is disabled by an administrator. Retrying will not help until this is fixed.", failure.message)
    }

    @Test fun anEmptyPoolAsksForAnAccount() {
        val failure = describe.failure(PoolStanding("anthropic"), now)
        assertEquals(503, failure.status)
        assertFalse(failure.retryable)
        assertEquals("No Claude account is connected to your TeamCode pool. An administrator must add one with " +
            "`teamcode accounts add` and assign it to your pool.", failure.message)
    }

    @Test fun aQuotaResetHoursAwayGivesItsTimeAndStopsAutomaticRetries() {
        val reset = now + 2 * 3_600_000 + 10 * 60_000
        val failure = describe.failure(PoolStanding("anthropic", pendingHandover = 1, quotaExhausted = 2, nextAvailableAt = reset), now)
        assertEquals(429, failure.status)
        assertEquals(7_800, failure.retryAfterSeconds)
        assertFalse(failure.retryable)
        assertTrue(failure.message!!.endsWith("2 accounts are at their subscription limit. The next account should be usable at 14:10 UTC (in 2 h 10 min)."), failure.message)
    }

    @Test fun aShortCooldownStaysRetryable() {
        val failure = describe.failure(PoolStanding("anthropic", coolingDown = 1, nextAvailableAt = now + 45_000), now)
        assertEquals(429, failure.status)
        assertEquals(45, failure.retryAfterSeconds)
        assertTrue(failure.retryable)
        assertEquals("No Claude account in your TeamCode pool is usable. 1 account is paused after a provider rate limit or refusal. " +
            "The next account should be usable at 12:00 UTC (in 45s).", failure.message)
    }

    @Test fun dailyBudgetsResetAtUtcMidnightAndAreNotRetried() {
        val failure = RetryWindows.dailyBudget("member_allowance", "Your daily TeamCode allowance", "An administrator can raise it.", now)
        assertEquals(429, failure.status)
        assertEquals(12 * 3600L, failure.retryAfterSeconds)
        assertFalse(failure.retryable)
        assertEquals("Your daily TeamCode allowance is used up for today. It resets at 00:00 UTC (in 12 h). An administrator can raise it.", failure.message)
        assertEquals("group_allowance", assertFailsWith<me.nathanfallet.teamcode.domain.team.TeamFailure> {
            CheckGroupAdmissionUseCase().check(listOf(GroupAllowance(me.nathanfallet.teamcode.domain.team.TeamGroup("g", "G", 10), false,
                me.nathanfallet.teamcode.domain.team.GroupUsage(10))), 1, now)
        }.also { assertFalse(it.retryable); assertEquals(12 * 3600L, it.retryAfterSeconds) }.code)
    }

    @Test fun aBudgetHeldByRunningRequestsStaysRetryable() {
        val failure = RetryWindows.dailyBudget("member_allowance", "Your daily TeamCode allowance", "An administrator can raise it.", now, heldByRunningRequests = true)
        assertEquals(429, failure.status)
        assertTrue(failure.retryable)
        assertEquals(RetryWindows.HELD_BUDGET_RETRY_SECONDS, failure.retryAfterSeconds)
        assertEquals("Your daily TeamCode allowance is fully reserved by requests still running. Retry when they finish: they usually " +
            "cost less than reserved. Otherwise it resets at 00:00 UTC (in 12 h). An administrator can raise it.", failure.message)
        val group = me.nathanfallet.teamcode.domain.team.TeamGroup("g", "G", 10)
        assertTrue(assertFailsWith<me.nathanfallet.teamcode.domain.team.TeamFailure> {
            CheckGroupAdmissionUseCase().check(listOf(GroupAllowance(group, false, me.nathanfallet.teamcode.domain.team.GroupUsage(10, 1))), 1, now)
        }.retryable)
    }

    @Test fun conflictsAreFinalUnlessMarkedRetryable() {
        assertFalse(me.nathanfallet.teamcode.domain.team.TeamFailure("run_ended", 409, "Ended.").retryable)
        assertTrue(me.nathanfallet.teamcode.domain.team.TeamFailure("queue_turn", 429, "Not your turn.").retryable)
    }

    @Test fun envelopeTypesAreDocumentedValues() {
        assertEquals("billing_error", InferenceErrors.type(WireProtocol.MESSAGES, 429, "member_allowance"))
        assertEquals("rate_limit_error", InferenceErrors.type(WireProtocol.MESSAGES, 429, "pool_unavailable"))
        assertEquals("api_error", InferenceErrors.type(WireProtocol.MESSAGES, 503, "pool_unavailable"))
        assertEquals("permission_error", InferenceErrors.type(WireProtocol.MESSAGES, 403, "model_not_allowed"))
        assertEquals("rate_limit_error", InferenceErrors.type(WireProtocol.RESPONSES, 429, "member_allowance"))
        assertEquals("server_error", InferenceErrors.type(WireProtocol.RESPONSES, 503, "pool_unavailable"))
    }
}
