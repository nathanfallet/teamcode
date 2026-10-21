package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** One durable controller mode per team, shared by all admission and delivery replicas. */
object PacingSettingsTable : Table("pacing_settings") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE)
    val observationOnly = bool("observation_only").default(false)
    val quotaPressurePercent = integer("quota_pressure_percent").default(95)
    override val primaryKey = PrimaryKey(teamId)
}
