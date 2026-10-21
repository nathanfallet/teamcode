package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.team.*

/** A member's current assignment plus durable usage from the original reservation-group snapshots. */
data class GroupAllowance(val group: TeamGroup, val paused: Boolean, val usage: GroupUsage) {
    val budgetAvailable: Boolean get() = !paused && (group.dailyUnits == 0L || usage.chargedAndReservedUnits < group.dailyUnits)
}

/** Every active membership applies. Membership edits cannot refund historical reservations. */
class CheckGroupAdmissionUseCase {
    fun check(groups: List<GroupAllowance>, units: Long, now: Long = System.currentTimeMillis()) {
        if (groups.any { it.paused }) throw TeamFailure("group_paused", 403, "Admission is paused by one of your groups. Contact a group manager or administrator.")
        val exhausted = groups.filter { it.group.dailyUnits > 0 && units > it.group.dailyUnits - it.usage.chargedAndReservedUnits }
        if (exhausted.isNotEmpty()) {
            throw RetryWindows.dailyBudget("group_allowance", "The daily budget of one of your TeamCode groups", "A group manager or administrator can raise it.", now,
                heldByRunningRequests = exhausted.all { it.usage.activeRequests > 0 })
        }
        if (groups.any { it.group.maxConcurrent > 0 && it.usage.activeRequests >= it.group.maxConcurrent }) {
            throw TeamFailure("group_concurrency", 429, "One of your TeamCode groups has reached its concurrent request limit. This request can start when one of the group's requests finishes.", retryAfterSeconds = RetryWindows.SHORT_RETRY_SECONDS)
        }
    }
    /** Zero means neither the member nor any group sets a concurrency limit. */
    fun maximum(groups: List<GroupAllowance>, memberMaximum: Int): Int = concurrencyCeiling(groups.map { it.group.maxConcurrent } + memberMaximum)
}
