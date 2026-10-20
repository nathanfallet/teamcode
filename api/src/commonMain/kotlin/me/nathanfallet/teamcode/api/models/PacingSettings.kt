package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** Observation mode changes predictive control only; hard quota and authorization limits remain active. */
@Serializable
data class PacingSettingsView(val observationOnly: Boolean = false)

/** An informational forecast, never an instruction for automatic emission to an agent. */
@Serializable
data class PacingPreviewResponse(val settings: PacingSettingsView, val directive: DirectiveView?)
