package me.nathanfallet.teamcode.domain.team

/** The token identifier is an opaque database reference, never a bearer credential. */
data class RunAdmissionState(val tokenId: String, val ended: Boolean, val workload: String, val policy: RunPolicy, val chargedUnits: Long, val activeRequests: Int)

/** Hard run constraints remain independent of advisory pacing and native provider fallback. */
class CheckRunAdmissionUseCase {
    fun check(actor: Principal, run: RunAdmissionState, units: Long, workload: String? = null) {
        if (run.tokenId != actor.tokenId) throw TeamFailure("run_credential_changed", 401, "This launch belongs to another login. Start a new launch with the current credential.")
        if (run.ended) throw TeamFailure("run_ended", 409, "This run has ended. Start a new launch.")
        if (workload != null && run.workload != workload) throw TeamFailure("run_context_changed", 409, "This workload does not match the registered launch.")
        if (run.policy.paused) throw TeamFailure("run_paused", 403, "New admissions for this run are paused by an administrator.")
        if (run.policy.budgetUnits > 0 && units > run.policy.budgetUnits - run.chargedUnits)
            throw TeamFailure("run_allowance", 429, "This TeamCode run has used its credit budget. Start a new launch, or ask an administrator to raise the run budget.", retryable = false)
        if (run.policy.maxConcurrent > 0 && run.activeRequests >= run.policy.maxConcurrent)
            throw TeamFailure("run_concurrency", 429, "This TeamCode run already has its maximum of parallel requests. This one can start when one of them finishes.", retryAfterSeconds = 5)
    }
}

/** Recent wrapper evidence, confirmed completion and credential revocation are distinct states. */
class RunStateUseCase {
    fun state(endedAt: Long?, heartbeatAt: Long, now: Long, credentialValid: Boolean): RunState = when {
        endedAt != null -> RunState.ENDED
        !credentialValid -> RunState.REVOKED
        now - heartbeatAt > AgentRunService.HEARTBEAT_TTL_MILLIS -> RunState.STALE
        else -> RunState.RUNNING
    }
}

data class RunPacingPolicy(val id: String, val policy: RunPolicy, val chargedUnits: Long)
data class RunPacingLimits(val maxConcurrent: Int, val allowanceAvailable: Boolean, val contextKey: String)

/** Only recent associated launches constrain a native-session directive; counters do not churn its key. A zero maximum is unlimited. */
class CheckRunPacingUseCase {
    fun limits(runs: List<RunPacingPolicy>, maximum: Int) = RunPacingLimits(
        me.nathanfallet.teamcode.domain.capacity.concurrencyCeiling(runs.map { it.policy.maxConcurrent } + maximum),
        runs.all { !it.policy.paused && (it.policy.budgetUnits == 0L || it.chargedUnits < it.policy.budgetUnits) },
        runs.sortedBy { it.id }.joinToString("\n") { "${it.id}:${it.policy}" },
    )
}
