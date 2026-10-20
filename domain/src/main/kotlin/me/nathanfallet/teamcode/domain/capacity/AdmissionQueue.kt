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
        try {
            return withTimeoutOrNull(MAX_WAIT_MILLIS) {
                while (true) {
                    beforeAttempt()
                    try { return@withTimeoutOrNull queue.reserveQueued(actor, ticket, input).also { admitted = true } }
                    catch (error: TeamFailure) {
                        if (error.code !in WAITABLE) throw error
                        queue.renew(actor, ticket, error.code in setOf("member_concurrency", "group_concurrency", "run_concurrency", "pacing_concurrency", "pacing_delay"), input)
                        delay(POLL_MILLIS)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            } ?: throw TeamFailure("queue_timeout", 503, "The bounded admission wait expired. Retry shortly.")
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
        const val LEASE_MILLIS = 15_000L
        val WAITABLE = setOf("member_concurrency", "group_concurrency", "run_concurrency", "pacing_concurrency", "pacing_delay", "pool_busy", "queue_turn")
    }
}

data class QueueCandidate(val id: String, val memberId: String, val createdAt: Long, val workload: Workload, val lastTurn: Long)
class SelectQueueTurnUseCase {
    fun select(candidates: List<QueueCandidate>, now: Long): String? = candidates.minWithOrNull(
        compareBy<QueueCandidate> { if (it.workload == Workload.INTERACTIVE || now - it.createdAt >= 30_000) 0 else 1 }
            .thenBy { it.lastTurn }.thenBy { it.createdAt }.thenBy { it.id }
    )?.id
}
