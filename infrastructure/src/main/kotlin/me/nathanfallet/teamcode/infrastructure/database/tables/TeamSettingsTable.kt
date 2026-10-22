package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** Team-wide settings outside the pacing controller; a missing row means every default. */
object TeamSettingsTable : Table("team_settings") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE)
    /** Who can add accounts: `administrators` (default) or `members`. */
    val accountEnrollment = varchar("account_enrollment", 16).default("administrators")
    override val primaryKey = PrimaryKey(teamId)
}
