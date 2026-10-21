package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import kotlin.test.*

class PoolAccountStatusTest {
    private val now = 1_000_000_000L
    private fun account(id: String, priority: Int = 0, state: AccountState = AccountState.READY, disabled: Boolean = false) =
        AccountRecord(id, AccountIdentity("default", "anthropic", id, "org"), "Name $id", 1, disabled, null, priority, state)
    private fun window(id: String, name: String, utilization: Double, resets: Long? = now + 60_000, observed: Long = now) = AccountQuota(id, name, utilization, observed, resets)

    @Test fun usabilityFollowsAdmissionEligibilityInAFixedOrder() {
        val describe = DescribePoolAccountsUseCase()
        val result = describe.describe(listOf(
            PoolAccountObservation(account("a", 5), 0, listOf(window("a", "five_hour", .5), window("a", "model:fable", 1.0))),
            PoolAccountObservation(account("b", disabled = true, state = AccountState.RECONNECT_REQUIRED), 0, emptyList()),
            PoolAccountObservation(account("c", state = AccountState.PENDING_HANDOVER), 0, emptyList()),
            PoolAccountObservation(account("d", state = AccountState.RECONNECT_REQUIRED), 0, emptyList()),
            PoolAccountObservation(account("e"), now + 1_000, listOf(window("e", "seven_day", .99))),
            PoolAccountObservation(account("f"), now - 1, listOf(window("f", "seven_day", QUOTA_EXHAUSTED_UTILIZATION))),
            PoolAccountObservation(account("g"), 0, listOf(window("g", "seven_day", .99, resets = now - 1), window("g", "five_hour", .99, resets = null, observed = now - 600_000))),
        ), now, revealIdentity = true)
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), result.map { it.accountId })
        assertEquals(listOf(null, AccountUnavailability.DISABLED, AccountUnavailability.PENDING_HANDOVER, AccountUnavailability.RECONNECT_REQUIRED,
            AccountUnavailability.COOLING_DOWN, AccountUnavailability.QUOTA_EXHAUSTED, null), result.map { it.unavailable })
        assertTrue(result[0].usable, "A model-scoped window bars only its model")
        assertEquals(now + 1_000, result[4].cooldownUntilMillis); assertNull(result[5].cooldownUntilMillis)
        assertTrue(result[6].windows.isEmpty(), "A passed reset or a stale unknown-reset reading no longer describes the account")
    }

    @Test fun membersGetStableNeutralLabelsWithoutIdentifiers() {
        val observed = listOf(PoolAccountObservation(account("z", 1), 0, emptyList()), PoolAccountObservation(account("y", 1), 0, emptyList()), PoolAccountObservation(account("x", 9), 0, emptyList()))
        val hidden = DescribePoolAccountsUseCase().describe(observed, now, revealIdentity = false)
        assertEquals(listOf("Account 1", "Account 2", "Account 3"), hidden.map { it.label })
        assertTrue(hidden.all { it.accountId == null && it.name == null })
        assertEquals(listOf("Name x", "Name y", "Name z"), DescribePoolAccountsUseCase().describe(observed, now, revealIdentity = true).map { it.label })
    }
}
