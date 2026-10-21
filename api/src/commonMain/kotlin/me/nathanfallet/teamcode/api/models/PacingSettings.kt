package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/**
 * Observation mode changes predictive control only; hard quota and authorization limits remain active.
 * [quotaPressurePercent] (50-100, default 95) is always present in responses; omitting it in a PUT keeps the stored value.
 */
@Serializable
data class PacingSettingsView(val observationOnly: Boolean = false, val quotaPressurePercent: Int? = null)

/** An informational forecast, never an instruction for automatic emission to an agent. */
@Serializable
data class PacingPreviewResponse(val settings: PacingSettingsView, val directive: DirectiveView?)
