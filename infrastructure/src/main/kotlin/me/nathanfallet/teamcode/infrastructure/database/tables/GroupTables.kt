package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** Archived groups retain history; only active group memberships constrain new admission. */
object TeamGroups : Table("team_groups") {
    val id = varchar("id", 36)
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 100)
    val dailyUnits = long("daily_units").default(0)
    val maxConcurrent = integer("max_concurrent").default(0)
    val archived = bool("archived").default(false)
    override val primaryKey = PrimaryKey(id)
    init { index("groups_team", false, teamId, id) }
}
object GroupMembers : Table("group_members") {
    val groupId = reference("group_id", TeamGroups.id, onDelete = ReferenceOption.CASCADE)
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE)
    val role = varchar("role", 16).default("MEMBER")
    val paused = bool("paused").default(false)
    override val primaryKey = PrimaryKey(groupId, memberId)
    init { index("member_groups", false, memberId, groupId) }
}

/** Immutable membership/date/lease snapshots; actual units and state remain solely in reservations. */
object ReservationGroups : Table("reservation_groups") {
    val reservationId = reference("reservation_id", Reservations.id, onDelete = ReferenceOption.CASCADE)
    val groupId = reference("group_id", TeamGroups.id, onDelete = ReferenceOption.NO_ACTION)
    val dayNumber = long("day_number")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(reservationId, groupId)
    init {
        index("group_daily", false, groupId, dayNumber, reservationId)
        index("group_active", false, groupId, expiresAt, reservationId)
    }
}
