package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class GroupAdmissionTest {
    private val check = CheckGroupAdmissionUseCase()
    @Test fun allGroupsApplyAndZeroCapsGrantNoExtraConcurrency() {
        val wide = GroupAllowance(TeamGroup("wide", "Wide", 1000, 8), false, GroupUsage(100, 1))
        val tight = GroupAllowance(TeamGroup("tight", "Tight", 120, 2), false, GroupUsage(100, 1))
        val unlimited = GroupAllowance(TeamGroup("zero", "No extra cap"), false, GroupUsage(10000, 100))
        check.check(listOf(wide, tight, unlimited), 20)
        assertEquals("group_allowance", assertFailsWith<TeamFailure> { check.check(listOf(wide, tight), 21) }.code)
        assertEquals("group_concurrency", assertFailsWith<TeamFailure> { check.check(listOf(tight.copy(usage = GroupUsage(100, 2))), 1) }.code)
        assertEquals("group_paused", assertFailsWith<TeamFailure> { check.check(listOf(wide.copy(paused = true)), 1) }.code)
        assertEquals(2, check.maximum(listOf(wide, tight, unlimited), 5))
        assertEquals(1, check.maximum(listOf(wide, tight), 1))
        assertEquals(3, check.maximum(listOf(unlimited), 3))
        assertFalse(tight.copy(usage = GroupUsage(120)).budgetAvailable)
        assertFalse(wide.copy(paused = true).budgetAvailable)
        assertTrue(unlimited.budgetAvailable)
    }
    @Test fun invalidPoliciesAreRejectedRatherThanClamped() {
        val validation = CheckGroupPolicyUseCase()
        listOf(GroupPolicy(""), GroupPolicy("line\nbreak"), GroupPolicy("x".repeat(101)), GroupPolicy("Budget", -1),
            GroupPolicy("Budget", 100_000_000_001), GroupPolicy("Slots", maxConcurrent = -1), GroupPolicy("Slots", maxConcurrent = 129)).forEach {
            assertEquals("invalid_group", assertFailsWith<TeamFailure> { validation.validate(it) }.code)
        }
        validation.validate(GroupPolicy("Developers", 100_000_000_000, 128))
    }
}
