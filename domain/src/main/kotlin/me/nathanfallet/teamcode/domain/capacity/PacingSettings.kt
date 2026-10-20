package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.team.Principal

/** Observation preserves forecasts without applying predictive throttles or delivering agent advice. */
data class PacingSettings(val observationOnly: Boolean = false)

/** Team-wide controller settings; authorization and updates must use current shared membership. */
interface PacingSettingsStore {
    suspend fun get(actor: Principal): PacingSettings
    suspend fun save(actor: Principal, settings: PacingSettings): PacingSettings
}
