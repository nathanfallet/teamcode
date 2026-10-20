package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class BudgetOverrideTest {
    private val check = CheckBudgetOverrideUseCase()
    @Test fun deadlinesAreBoundedByDurationAndTheOriginalUtcDay() {
        val day = 86_400_000L
        val change = BudgetOverrideChange(0, 60, "Support incident")
        assertEquals(3_600_000L, check.expiresAt(change, 0))
        assertEquals(day, check.expiresAt(change, day - 1))
        assertEquals(day, check.expiresAt(change.copy(durationMinutes = 1440), 1))
        assertEquals("Support incident", check.reason(" Support incident "))
        for (invalid in listOf(change.copy(dailyUnits = -1), change.copy(dailyUnits = 100_000_000_001),
            change.copy(durationMinutes = 0), change.copy(durationMinutes = 1441), change.copy(reason = " "),
            change.copy(reason = "private\nsecond line"), change.copy(reason = "a".repeat(241)))) {
            assertEquals("invalid_budget_override", assertFailsWith<TeamFailure> { check.expiresAt(invalid, 0) }.code)
        }
    }
    @Test fun revocationDeadlinePoolAndCreationTimeAllConstrainAnException() {
        val grant = BudgetOverride("id", "member", null, 700, "admin", "Incident", 100, 200)
        assertTrue(grant.isActive(100, null)); assertTrue(grant.isActive(199, null))
        assertFalse(grant.isActive(99, null)); assertFalse(grant.isActive(200, null))
        assertFalse(grant.isActive(150, "protected")); assertFalse(grant.copy(revokedAtMillis = 149).isActive(150, null))
        assertEquals(BudgetOverrideSummary("id", 700, 200), grant.summary())
    }
}
