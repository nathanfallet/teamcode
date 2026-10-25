package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Scopes of [ReservationDailyUnits]. */
internal object DailyScope {
    const val MEMBER = "member"
    const val POOL = "pool"
    const val LENT = "lent"
    const val GROUP = "group"
}

/**
 * Adds [delta] to every daily total a reservation counts in: its member, its home pool, the source pool when another pool's
 * account served it, and its groups. Call in the team-locked transaction that changes the reservation's units.
 */
internal fun addDailyUnits(teamId: String, day: Long, memberId: String, poolId: String, sourcePoolId: String?, groupIds: List<String>, delta: Long) {
    if (delta == 0L) return
    val scopes = buildList {
        add(DailyScope.MEMBER to memberId)
        add(DailyScope.POOL to poolId)
        // Same rule as the borrowed sum this replaces (a case-insensitive SQL comparison): a legacy row without a source pool lent nothing.
        if (sourcePoolId != null && !sourcePoolId.equals(poolId, ignoreCase = true)) add(DailyScope.LENT to sourcePoolId)
        groupIds.forEach { add(DailyScope.GROUP to it) }
    }
    val t = ReservationDailyUnits
    t.batchUpsert(scopes, onUpdate = { it[t.units] = t.units + delta }, shouldReturnGeneratedValues = false) { (scope, id) ->
        this[t.dayNumber] = day; this[t.teamId] = teamId; this[t.scope] = scope; this[t.scopeId] = id; this[t.units] = delta
    }
}

/** The day's totals of [ids] in one scope, read by primary key; an id without a row has no units. */
internal fun readDailyUnits(teamId: String, day: Long, scope: String, ids: Collection<String>): Map<String, Long> {
    if (ids.isEmpty()) return emptyMap()
    val t = ReservationDailyUnits
    return t.select(t.scopeId, t.units).where { (t.dayNumber eq day) and (t.teamId eq teamId) and (t.scope eq scope) and (t.scopeId inList ids) }
        .associate { it[t.scopeId] to it[t.units] }
}

/** Admission records at most 16 groups per reservation. */
internal fun reservationGroupIds(reservationId: String): List<String> =
    ReservationGroups.select(ReservationGroups.groupId).where { ReservationGroups.reservationId eq reservationId }.limit(16).map { it[ReservationGroups.groupId] }
