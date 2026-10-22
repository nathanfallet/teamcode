package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.capacity.GroupAllowance
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Read under the caller's team transaction; dated/leased associations bound aggregate index ranges. */
/** Aggregates read once per transaction when several members are read together; a member alone uses a fresh one. */
internal class CapacityReadCache {
    val groupUsage = mutableMapOf<String, GroupUsage>()
    val totalWeight = mutableMapOf<String?, Long>()
}

internal fun readGroupUsage(teamId: String, ids: List<String>, now: Long, cache: CapacityReadCache): Map<String, GroupUsage> {
    val missing = ids.filterNot { it in cache.groupUsage }
    if (missing.isNotEmpty()) cache.groupUsage += readGroupUsage(teamId, missing, now)
    return ids.associateWith { cache.groupUsage.getValue(it) }
}

internal fun readGroupUsage(teamId: String, ids: List<String>, now: Long): Map<String, GroupUsage> {
    if (ids.isEmpty()) return emptyMap()
    val units = Reservations.units.sum()
    val spent = (ReservationGroups innerJoin Reservations).select(ReservationGroups.groupId, units).where {
        (ReservationGroups.groupId inList ids) and (ReservationGroups.dayNumber eq now / 86_400_000) and (Reservations.teamId eq teamId)
    }.groupBy(ReservationGroups.groupId).associate { it[ReservationGroups.groupId] to (it[units] ?: 0L) }
    val count = ReservationGroups.reservationId.count()
    val active = (ReservationGroups innerJoin Reservations).select(ReservationGroups.groupId, count).where {
        (ReservationGroups.groupId inList ids) and (ReservationGroups.expiresAt greater now) and (Reservations.expiresAt greater now) and
            (Reservations.state eq "ACTIVE") and (Reservations.teamId eq teamId)
    }.groupBy(ReservationGroups.groupId).associate { it[ReservationGroups.groupId] to it[count].toInt() }
    return ids.associateWith { GroupUsage(spent[it] ?: 0, active[it] ?: 0) }
}

internal fun readGroupAllowances(teamId: String, memberId: String, now: Long, cache: CapacityReadCache = CapacityReadCache()): List<GroupAllowance> {
    val memberships = (GroupMembers innerJoin TeamGroups).select(TeamGroups.columns + GroupMembers.paused).where {
        (TeamGroups.teamId eq teamId) and (TeamGroups.archived eq false) and (GroupMembers.memberId eq memberId)
    }.orderBy(TeamGroups.id).limit(17).toList()
    if (memberships.size > 16) throw TeamFailure("group_limit", 503, "Too many active group assignments. Ask an administrator to review them.")
    val usage = readGroupUsage(teamId, memberships.map { it[TeamGroups.id] }, now, cache)
    return memberships.map { GroupAllowance(groupRecord(it), it[GroupMembers.paused], usage.getValue(it[TeamGroups.id])) }
}

internal fun groupRecord(row: ResultRow) = TeamGroup(row[TeamGroups.id], row[TeamGroups.name], row[TeamGroups.dailyUnits], row[TeamGroups.maxConcurrent], row[TeamGroups.archived])
