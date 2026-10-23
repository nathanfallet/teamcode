package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class RunRulesTest {
    private val actor = Principal(Member("member", "member", Role.MEMBER, false, false), "creator", "default")
    private val run = RunAdmissionState("creator", false, "interactive", RunPolicy(100, 2), 80, 1)
    @Test fun identityLifecycleAndCumulativeCapsAreHardConstraints() {
        val check = CheckRunAdmissionUseCase()
        check.check(actor, run, 20, "interactive")
        listOf("run_allowance" to run.copy(chargedUnits = 100), "run_concurrency" to run.copy(activeRequests = 2),
            "run_paused" to run.copy(policy = RunPolicy(paused = true)), "run_ended" to run.copy(ended = true),
            "run_credential_changed" to run.copy(tokenId = "another-login")).forEach { (code, value) ->
            assertEquals(code, assertFailsWith<TeamFailure> { check.check(actor, value, 1) }.code)
        }
        assertEquals("run_context_changed", assertFailsWith<TeamFailure> { check.check(actor, run, 1, "background") }.code)
        check.check(actor, run.copy(policy = RunPolicy(), chargedUnits = 10000, activeRequests = 100), 1)
        for (policy in listOf(RunPolicy(-1), RunPolicy(100_000_000_001), RunPolicy(maxConcurrent = 33)))
            assertFailsWith<TeamFailure> { AgentRunService.validate(policy) }
        assertFailsWith<TeamFailure> { AgentRunService.validateStart("../other", "claude", "interactive") }
        assertFailsWith<TeamFailure> { AgentRunService.validateStart("11111111-1111-1111-1111-111111111111", "unknown", "interactive") }
    }
    @Test fun activityIsOneOfTwoStatesAndNothingElse() {
        assertEquals(RunActivity.WAITING_FOR_HUMAN, RunActivity.parse("waiting_for_human"))
        assertEquals(RunActivity.WORKING, RunActivity.parse("working"))
        for (value in listOf("", "idle", "WORKING", "waiting", "working "))
            assertEquals("invalid_run_activity", assertFailsWith<TeamFailure> { RunActivity.parse(value) }.code)
    }
    @Test fun lostHeartbeatsAreUnknownProcessStateAndConfirmedEndIsRetainedAfterRevocation() {
        val states = RunStateUseCase()
        assertEquals(RunState.RUNNING, states.state(null, 1000, 91000, true))
        assertEquals(RunState.STALE, states.state(null, 1000, 91001, true))
        assertEquals(RunState.REVOKED, states.state(null, 1000, 91001, false))
        assertEquals(RunState.ENDED, states.state(2000, 1000, 91001, false))
    }
    @Test fun pacingUsesRunCapsWithoutResettingRecoveryOnEveryBilledToken() {
        val check = CheckRunPacingUseCase()
        val run = RunPacingPolicy("one", RunPolicy(100, 1), 20)
        val first = check.limits(listOf(run), 5)
        assertEquals(1, first.maxConcurrent); assertTrue(first.allowanceAvailable)
        assertEquals(first.contextKey, check.limits(listOf(run.copy(chargedUnits = 30)), 5).contextKey)
        assertNotEquals(first.contextKey, check.limits(listOf(run.copy(policy = RunPolicy(100, 2))), 5).contextKey)
        assertFalse(check.limits(listOf(run.copy(chargedUnits = 100)), 5).allowanceAvailable)
        assertFalse(check.limits(listOf(run.copy(policy = RunPolicy(paused = true))), 5).allowanceAvailable)
    }
}
