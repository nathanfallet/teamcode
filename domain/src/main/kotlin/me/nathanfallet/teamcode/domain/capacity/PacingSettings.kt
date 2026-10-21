package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure

const val DEFAULT_QUOTA_PRESSURE_PERCENT = 95
/** Below half of a window the controller already advises acceleration, so a lower threshold would contradict it. */
const val MIN_QUOTA_PRESSURE_PERCENT = 50
/** A window at 100% is exhausted by definition; a larger value could never be reached. */
const val MAX_QUOTA_PRESSURE_PERCENT = 100
const val DEFAULT_QUOTA_PRESSURE_THRESHOLD = DEFAULT_QUOTA_PRESSURE_PERCENT / 100.0

/**
 * Observation preserves forecasts without applying predictive throttles or delivering agent advice.
 * [quotaPressurePercent] is the single team-wide pressure threshold: at or above it on an applicable provider window
 * an account is no longer chosen, forecasts and pacing treat the window as exhausted, and pinned conversations move.
 * [autoPoolSize] sizes the home pool's daily budget from the connected subscriptions ([AutomaticPoolSize]) instead of
 * the hand-typed budget; the split between members is unchanged.
 */
data class PacingSettings(val observationOnly: Boolean = false, val quotaPressurePercent: Int = DEFAULT_QUOTA_PRESSURE_PERCENT, val autoPoolSize: Boolean = false) {
    init {
        if (quotaPressurePercent !in MIN_QUOTA_PRESSURE_PERCENT..MAX_QUOTA_PRESSURE_PERCENT) throw TeamFailure("invalid_quota_pressure", 400,
            "The quota pressure threshold must be a whole percentage from $MIN_QUOTA_PRESSURE_PERCENT to $MAX_QUOTA_PRESSURE_PERCENT.")
    }
    val quotaPressureThreshold: Double get() = quotaPressurePercent / 100.0
}

/** Team-wide controller settings; authorization and updates must use current shared membership. */
interface PacingSettingsStore {
    suspend fun get(actor: Principal): PacingSettings
    suspend fun save(actor: Principal, settings: PacingSettings): PacingSettings
    /** Merge under the same authority as [save]; a null [quotaPressurePercent] keeps the stored value. Implementations should do it atomically. */
    suspend fun update(actor: Principal, observationOnly: Boolean, quotaPressurePercent: Int?): PacingSettings =
        get(actor).let { save(actor, it.copy(observationOnly = observationOnly, quotaPressurePercent = quotaPressurePercent ?: it.quotaPressurePercent)) }
    /** Turns the automatic home pool size on or off, keeping every other setting and the manual budget. */
    suspend fun setAutoPoolSize(actor: Principal, enabled: Boolean): PacingSettings = save(actor, get(actor).copy(autoPoolSize = enabled))
}
