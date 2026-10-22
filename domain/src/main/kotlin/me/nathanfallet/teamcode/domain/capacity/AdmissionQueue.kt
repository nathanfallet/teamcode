package me.nathanfallet.teamcode.domain.capacity

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure

enum class Workload { INTERACTIVE, BACKGROUND }
data class AdmissionInput(val units: Long, val model: String, val provider: String, val excludedAccountIds: Set<String> = emptySet(), val affinity: RequestAffinity? = null, val workload: Workload = Workload.INTERACTIVE, val context: AdmissionContext? = null, val runId: String? = null)
data class AdmissionContext(val requestedModel: String, val protocol: WireProtocol, val targets: List<RouteTarget>, val effort: String?, val thinkingTokens: Long?, val translated: Boolean, val targetEffort: String?)
data class AdmissionTicket(val id: String)
interface AdmissionQueueStore {
    suspend fun enqueue(actor: Principal, input: AdmissionInput): AdmissionTicket
    suspend fun reserveQueued(actor: Principal, ticket: AdmissionTicket, input: AdmissionInput): Reservation
    suspend fun renew(actor: Principal, ticket: AdmissionTicket, blocked: Boolean)
    /** Refresh physical queue scope from the request metadata; no body is persisted. */
    suspend fun renew(actor: Principal, ticket: AdmissionTicket, blocked: Boolean, input: AdmissionInput) = renew(actor, ticket, blocked)
    suspend fun release(actor: Principal, ticket: AdmissionTicket)
}

/** Waiting owns no reservation or database connection. Authorization is checked on every attempt. */
class AwaitAdmissionUseCase(private val queue: AdmissionQueueStore) {
    suspend fun reserve(actor: Principal, input: AdmissionInput, beforeAttempt: suspend () -> Unit = {}): Reservation {
        val ticket = queue.enqueue(actor, input)
        var admitted = false
        var attempt = 0
        try {
            return withTimeoutOrNull(MAX_WAIT_MILLIS) {
                while (true) {
                    beforeAttempt()
                    try { return@withTimeoutOrNull queue.reserveQueued(actor, ticket, input).also { admitted = true } }
                    catch (error: TeamFailure) {
                        if (error.code !in WAITABLE) throw error
                        val blocked = error.code in setOf("member_concurrency", "group_concurrency", "run_concurrency", "pacing_concurrency", "pacing_delay")
                        // Recomputing the physical queue scope reads every account under the team lock: refresh it on
                        // the first retry and then every few, the lease renewal alone is enough in between.
                        if (attempt % SCOPE_REFRESH_EVERY == 0) queue.renew(actor, ticket, blocked, input) else queue.renew(actor, ticket, blocked)
                        delay(pollDelay(attempt++))
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            } ?: throw TeamFailure("queue_timeout", 503, "TeamCode waited ${MAX_WAIT_MILLIS / 1000}s for free capacity in your pool without getting a turn. Retry shortly.", retryAfterSeconds = 15)
        } finally {
            // A revoked session must still be able to remove its own pending row.
            // Lease expiry provides cleanup if the database is unreachable or the replica crashes.
            if (!admitted) withContext(NonCancellable) {
                try { withTimeout(5_000) { queue.release(actor, ticket) } }
                catch (_: TimeoutCancellationException) { /* Expiring metadata does not reserve quota. */ }
            }
        }
    }
    companion object {
        const val MAX_WAIT_MILLIS = 90_000L
        const val POLL_MILLIS = 500L
        const val SCOPE_REFRESH_EVERY = 4
        /**
         * Each poll is a team-locked transaction, and every queued request polls: a long wait must cost less than a short
         * one. Fixed at [POLL_MILLIS] for the first attempts so a freed slot is still taken quickly, then 1 s, then 2 s
         * (well inside [LEASE_MILLIS]), with jitter so waiters do not poll in step.
         */
        fun pollDelay(attempt: Int, random: kotlin.random.Random = kotlin.random.Random.Default): Long {
            if (attempt < 4) return POLL_MILLIS
            val base = if (attempt < 10) 2 * POLL_MILLIS else 4 * POLL_MILLIS
            return base - base / 5 + random.nextLong(base * 2 / 5 + 1)
        }
        const val LEASE_MILLIS = 15_000L
        // "pacing_concurrency" is no longer emitted (pacing only spaces admissions); it stays waitable while older
        // replicas still run during a rolling upgrade.
        // "pool_busy" is no longer emitted (accounts have no parallelism limit); it stays waitable for servers that predate this.
        val WAITABLE = setOf("member_concurrency", "group_concurrency", "run_concurrency", "pacing_concurrency", "pacing_delay", "queue_turn", "pool_busy")
    }
}

data class QueueCandidate(val id: String, val memberId: String, val createdAt: Long, val workload: Workload, val lastTurn: Long)
class SelectQueueTurnUseCase {
    fun select(candidates: List<QueueCandidate>, now: Long): String? = candidates.minWithOrNull(
        compareBy<QueueCandidate> { if (it.workload == Workload.INTERACTIVE || now - it.createdAt >= 30_000) 0 else 1 }
            .thenBy { it.lastTurn }.thenBy { it.createdAt }.thenBy { it.id }
    )?.id
}
