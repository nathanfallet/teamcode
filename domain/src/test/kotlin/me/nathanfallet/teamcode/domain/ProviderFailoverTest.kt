package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.gateway.*
import kotlin.test.*

class ProviderFailoverTest {
    @Test fun onlyDefiniteRefusalsMoveARequestToAnotherAccount() {
        assertEquals(ProviderRefusal.ACCOUNT, ProviderRefusal.of(401, subscriptionSpent = false))
        assertEquals(ProviderRefusal.ACCOUNT, ProviderRefusal.of(403, subscriptionSpent = false))
        assertEquals(ProviderRefusal.ACCOUNT, ProviderRefusal.of(429, subscriptionSpent = true))
        assertEquals(ProviderRefusal.RATE_LIMIT, ProviderRefusal.of(429, subscriptionSpent = false))
        // The provider may have done the work: never sent to another account.
        for (status in listOf(400, 404, 413, 500, 502, 529)) assertNull(ProviderRefusal.of(status, subscriptionSpent = false), "status $status")
    }

    @Test fun aRateLimitAllowsOneSwitchPerProviderThenStops() {
        val budget = ProviderFailoverBudget()
        assertTrue(budget.allows("anthropic"))
        assertTrue(budget.record("anthropic", ProviderRefusal.RATE_LIMIT, 40), "The first rate limit switches account")
        assertNull(budget.failure("anthropic"))
        assertFalse(budget.record("anthropic", ProviderRefusal.RATE_LIMIT, 25), "The second one stops this provider")
        assertFalse(budget.allows("anthropic"))
        assertTrue(budget.allows("codex"), "Another provider keeps its own switch")
    }

    @Test fun accountRefusalsNeverSpendTheRateLimitSwitch() {
        val budget = ProviderFailoverBudget()
        repeat(20) { assertTrue(budget.record("anthropic", ProviderRefusal.ACCOUNT, 60)) }
        assertTrue(budget.record("anthropic", ProviderRefusal.RATE_LIMIT, 60))
        assertNull(budget.failure("anthropic"))
    }

    @Test fun theStopSaysHowManyAccountsWereLimitedAndRetriesAtTheShortestAnnouncedWait() {
        val budget = ProviderFailoverBudget()
        budget.record("anthropic", ProviderRefusal.RATE_LIMIT, 40)
        budget.record("anthropic", ProviderRefusal.RATE_LIMIT, 25)
        val failure = budget.failure("anthropic")!!
        assertEquals("provider_rate_limited", failure.code)
        assertEquals(429, failure.status)
        assertEquals(25, failure.retryAfterSeconds)
        assertTrue(failure.retryable)
        assertEquals("The provider rate-limited 2 Claude accounts of your TeamCode pool one after the other for this request. " +
            "They are paused; TeamCode does not try the rest of the pool on a rate limit that may apply to every account. " +
            "Retry in 25s.", failure.message)
        assertEquals("rate_limit_error", InferenceErrors.type(WireProtocol.MESSAGES, failure.status, failure.code))
    }

    @Test fun aLongAnnouncedWaitIsShownInsteadOfRetriedAutomatically() {
        val budget = ProviderFailoverBudget()
        budget.record("codex", ProviderRefusal.RATE_LIMIT, 900)
        budget.record("codex", ProviderRefusal.RATE_LIMIT, 1800)
        val failure = budget.failure("codex")!!
        assertEquals(900, failure.retryAfterSeconds)
        assertFalse(failure.retryable)
        assertTrue(failure.message!!.contains("2 Codex accounts"))
        assertTrue(failure.message!!.endsWith("Retry in 15 min."))
    }
}
