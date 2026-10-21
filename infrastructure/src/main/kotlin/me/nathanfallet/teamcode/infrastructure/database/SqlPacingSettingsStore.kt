package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.capacity.PacingSettingsStore
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.PacingSettingsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*

/** Serialize mode changes with quota admission and directive delivery; keep no process-local cache. */
class SqlPacingSettingsStore(private val database: Database) : PacingSettingsStore {
    override suspend fun get(actor: Principal): PacingSettings = database.query {
        actor.requireInteractive(); actor.requireReady(); requireCurrent(this, actor)
        readPacingSettings(actor.teamId)
    }

    override suspend fun save(actor: Principal, settings: PacingSettings): PacingSettings = database.query { write(actor, settings) }

    /** The omitted threshold is read under the team lock, so concurrent administrators cannot overwrite each other. */
    override suspend fun update(actor: Principal, observationOnly: Boolean, quotaPressurePercent: Int?): PacingSettings = database.query {
        actor.requireAdmin(); lockTeam(actor.teamId); requireCurrent(this, actor)
        write(actor, PacingSettings(observationOnly, quotaPressurePercent ?: readPacingSettings(actor.teamId).quotaPressurePercent))
    }

    private fun JdbcTransaction.write(actor: Principal, settings: PacingSettings): PacingSettings {
        actor.requireAdmin(); lockTeam(actor.teamId); requireCurrent(this, actor)
        val previous = readPacingSettings(actor.teamId)
        PacingSettingsTable.upsert {
            it[teamId] = actor.teamId
            it[observationOnly] = settings.observationOnly
            it[quotaPressurePercent] = settings.quotaPressurePercent
        }
        if (settings.observationOnly != previous.observationOnly) audit(actor.teamId, actor.member.id,
            if (settings.observationOnly) "pacing.observation_enabled" else "pacing.enforcement_enabled", actor.teamId)
        if (settings.quotaPressurePercent != previous.quotaPressurePercent) audit(actor.teamId, actor.member.id,
            "pacing.quota_pressure_changed", "${actor.teamId}:${previous.quotaPressurePercent}->${settings.quotaPressurePercent}")
        return settings
    }
}

/** Call inside the caller's transaction so mode and policy decisions share the same team lock. */
internal fun readPacingSettings(teamId: String): PacingSettings = PacingSettingsTable.selectAll()
    .where { PacingSettingsTable.teamId eq teamId }.singleOrNull()
    ?.let { PacingSettings(it[PacingSettingsTable.observationOnly],
        // A stored value outside the allowed range (manual edit) must not fail admission; clamp it.
        it[PacingSettingsTable.quotaPressurePercent].coerceIn(MIN_QUOTA_PRESSURE_PERCENT, MAX_QUOTA_PRESSURE_PERCENT)) } ?: PacingSettings()
