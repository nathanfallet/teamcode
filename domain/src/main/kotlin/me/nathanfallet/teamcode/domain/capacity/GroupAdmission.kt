package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.team.*

/** A member's current assignment plus durable usage from the original reservation-group snapshots. */
data class GroupAllowance(val group: TeamGroup, val paused: Boolean, val usage: GroupUsage) {
    val budgetAvailable: Boolean get() = !paused && (group.dailyUnits == 0L || usage.chargedAndReservedUnits < group.dailyUnits)
}

/** Every active membership applies. Membership edits cannot refund historical reservations. */
class CheckGroupAdmissionUseCase {
    fun check(groups: List<GroupAllowance>, units: Long) {
        if (groups.any { it.paused }) throw TeamFailure("group_paused", 403, "Admission is paused by one of your groups. Contact a group manager or administrator.")
        if (groups.any { it.group.dailyUnits > 0 && units > it.group.dailyUnits - it.usage.chargedAndReservedUnits }) {
            throw TeamFailure("group_allowance", 429, "A group daily budget has insufficient remaining units for this request.")
        }
        if (groups.any { it.group.maxConcurrent > 0 && it.usage.activeRequests >= it.group.maxConcurrent }) {
            throw TeamFailure("group_concurrency", 429, "A group concurrent request limit has been reached.")
        }
    }
    /** Zero means neither the member nor any group sets a concurrency limit. */
    fun maximum(groups: List<GroupAllowance>, memberMaximum: Int): Int = concurrencyCeiling(groups.map { it.group.maxConcurrent } + memberMaximum)
}
