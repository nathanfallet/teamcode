package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.routing.*
import kotlin.test.*

class PoolAccountSelectionTest {
    @Test fun homeTierWinsBeforeHigherPriorityIdleOverflowUnlessConversationIsPinned() {
        val home = account("home", "private", -10)
        val reserve = account("reserve", "overflow", 10)
        val selector = SelectPoolAccountUseCase()
        assertEquals(home.account, selector.select(listOf(reserve, home), emptyList(), "private"))
        assertEquals(reserve.account, selector.select(listOf(reserve, home), listOf("reserve"), "private"))
        assertEquals(home.account, selector.select(listOf(home), listOf("reserve"), "private"))
        val preferred = account("preferred", "private", 20)
        assertEquals(home.account, selector.select(listOf(home, preferred), listOf("home"), "private"), "A higher priority never preempts a pin")
        assertEquals(preferred.account, selector.select(listOf(home, preferred), emptyList(), "private"))
        val loaded = account("loaded", "private", -10, 50)
        assertEquals(loaded.account, selector.select(listOf(loaded, preferred), listOf("loaded"), "private"), "Load never moves a pin")
        assertNull(selector.select(emptyList(), listOf("reserve"), "private"))
    }
    @Test fun reserveTierRetainsPriorityAndLoadOrderingWithoutTreatingPinsAsAuthorization() {
        val low = account("low", "overflow", 0)
        val busy = account("busy", "overflow", 10, 1)
        val idle = account("idle", "overflow", 10)
        val selector = SelectPoolAccountUseCase()
        assertEquals(idle.account, selector.select(listOf(low, busy, idle), listOf("foreign-private-account"), null))
        assertEquals(busy.account, selector.select(listOf(low, busy, idle), listOf("busy"), null))
    }
    private fun account(id: String, pool: String?, priority: Int, active: Int = 0) = AccountPlacement(
        AccountRecord(id, AccountIdentity("default", "anthropic", id, "synthetic-org"), id, 1, false, pool, priority, AccountState.ACCESS_ONLY), active, 0, .1)
}
