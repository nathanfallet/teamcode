package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import kotlin.test.*

class AccountHeadroomTest {
    private val now = 1_000_000_000L
    private fun account(id: String, state: AccountState = AccountState.READY, disabled: Boolean = false) =
        AccountRecord(id, AccountIdentity("team", "anthropic", id, "org"), id, 1, disabled, null, 0, state)
    private fun quota(id: String, window: String, utilization: Double, resetsIn: Long? = 3_600_000, observedAgo: Long = 0) =
        AccountQuota(id, window, utilization, now - observedAgo, resetsIn?.let { now + it })
    private fun observed(record: AccountRecord, vararg windows: AccountQuota, cooldownUntil: Long = 0) = PoolAccountObservation(record, cooldownUntil, windows.toList())

    @Test fun remainingShareIsTheThresholdMinusUtilizationUntilEachReset() {
        val result = AccountHeadroomUseCase().usable(listOf(observed(account("a"),
            quota("a", "seven_day", .5, resetsIn = 86_400_000), quota("a", "five_hour", .2))), now, pressureThreshold = .9)
        val headroom = result.single()
        assertEquals(.7, headroom.window("five_hour")!!.remainingShare, 1e-9)
        assertEquals(.4, headroom.window("seven_day")!!.remainingShare, 1e-9)
        assertEquals(now + 86_400_000, headroom.window("seven_day")!!.resetsAtMillis)
        assertEquals(listOf("five_hour", "seven_day"), headroom.windows.map { it.window })
    }

    @Test fun exhaustedDisconnectedPendingDisabledAndCoolingAccountsAreNotHeadroom() {
        val accounts = listOf(
            observed(account("usable"), quota("usable", "five_hour", .3)),
            observed(account("exhausted"), quota("exhausted", "five_hour", .3), quota("exhausted", "seven_day", .95)),
            observed(account("reconnect", AccountState.RECONNECT_REQUIRED), quota("reconnect", "five_hour", .1)),
            observed(account("pending", AccountState.PENDING_HANDOVER)),
            observed(account("disabled", disabled = true)),
            observed(account("cooling"), cooldownUntil = now + 1),
            observed(account("access", AccountState.ACCESS_ONLY)),
        )
        assertEquals(listOf("usable", "access"), AccountHeadroomUseCase().usable(accounts, now).map { it.account.id })
    }

    @Test fun staleOrUnknownWindowsAreAbsentNotFull() {
        val result = AccountHeadroomUseCase().usable(listOf(observed(account("a"),
            quota("a", "five_hour", .99, resetsIn = -1), quota("a", "seven_day", .99, resetsIn = null, observedAgo = MAX_QUOTA_OBSERVATION_AGE_MILLIS + 1))), now).single()
        assertEquals(emptyList(), result.windows, "A past reset or an old observation without reset says nothing about today")
    }

    @Test fun modelWindowsCountOnlyForTheirModel() {
        val accounts = listOf(observed(account("a"), quota("a", "five_hour", .1), quota("a", "model:opus:seven_day", .97)))
        assertEquals(listOf("five_hour"), AccountHeadroomUseCase().usable(accounts, now).single().windows.map { it.window }, "Without a model only shared windows apply")
        assertEquals(1, AccountHeadroomUseCase().usable(accounts, now, model = "claude-sonnet-test").size)
        assertEquals(emptyList(), AccountHeadroomUseCase().usable(accounts, now, model = "claude-opus-test"), "An exhausted model window bars its model")
    }
}
