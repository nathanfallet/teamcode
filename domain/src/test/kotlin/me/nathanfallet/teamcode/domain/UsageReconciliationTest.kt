package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class UsageReconciliationTest {
    private val rules = CheckUsageReconciliationUseCase()
    private val change = UsageReconciliationChange(100, 40, UsageEvidence.PROVIDER_RECORD, "Provider record", "Terminal usage verified", "req_fixture")
    @Test fun onlyUnknownOrExpiredChargesAreEligibleAndSnapshotsMustStillMatch() {
        assertTrue(rules.eligible("UNCERTAIN", 200, 100)); assertTrue(rules.eligible("ACTIVE", 100, 100))
        assertFalse(rules.eligible("ACTIVE", 101, 100)); assertFalse(rules.eligible("SETTLED", 0, 100)); assertFalse(rules.eligible("RECONCILED", 0, 100))
        rules.check("UNCERTAIN", 100, 200, 100, "req_fixture", change)
        assertEquals("usage_not_pending", assertFailsWith<TeamFailure> { rules.check("ACTIVE", 100, 200, 100, "req_fixture", change) }.code)
        for ((units, request) in listOf(99L to "req_fixture", 100L to "req_other", 100L to null)) {
            assertEquals("usage_changed", assertFailsWith<TeamFailure> { rules.check("UNCERTAIN", units, 0, 100, request, change) }.code)
        }
    }
    @Test fun verifiedCreditsAndEvidenceAreBoundedWithoutTreatingEstimatesAsProof() {
        assertEquals(change, rules.validate(change.copy(reference = " Provider record ")))
        assertEquals(0, rules.validate(change.copy(actualUnits = 0)).actualUnits)
        assertEquals(100_000_000, rules.validate(change.copy(actualUnits = 100_000_000)).actualUnits)
        for (bad in listOf(change.copy(expectedUnits = -1), change.copy(actualUnits = -1), change.copy(actualUnits = 100_000_001),
            change.copy(reference = " "), change.copy(reason = "a\nsecond line"), change.copy(reason = "a".repeat(241)),
            change.copy(expectedProviderRequestId = "https://untrusted.example/credential"))) {
            assertEquals("invalid_reconciliation", assertFailsWith<TeamFailure> { rules.validate(bad) }.code)
        }
    }
    @Test fun correlationIdentifiersAreBoundedOpaqueMetadata() {
        for (id in listOf("req_fixture", "abc-123.4:5", "A".repeat(128))) assertTrue(ProviderRequestIds.valid(id))
        for (id in listOf("", "x".repeat(129), "space secret", "bad\r\nheader", "https://example.org/", "../escape")) assertFalse(ProviderRequestIds.valid(id))
    }
}
