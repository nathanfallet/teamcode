package me.nathanfallet.teamcode.presentation

import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.PacingSettingsView
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** Management endpoints are interactive-only; the store checks current administrator authority. */
internal fun Route.pacingSettingsRoutes(settings: PacingSettingsStore, origin: String) {
    get("/api/v1/policy/pacing") { call.respond(settings.get(call.principal).view()) }
    put("/api/v1/policy/pacing") {
        val input = call.jsonBody<PacingSettingsView>()
        call.respond(settings.update(call.principal, input.observationOnly, input.quotaPressurePercent, input.autoPoolSize).view())
    }
    post("/dashboard/agent-guidance/pacing") {
        val input = call.form(origin.trimEnd('/'))
        val percent = input["quotaPressurePercent"]?.trim()?.let { it.toIntOrNull() ?: throw TeamFailure("invalid_quota_pressure", 400,
            "The quota pressure threshold must be a whole percentage from $MIN_QUOTA_PRESSURE_PERCENT to $MAX_QUOTA_PRESSURE_PERCENT.") }
        settings.update(call.principal, input["observationOnly"] == "on", percent)
        call.respondRedirect("/dashboard/agent-guidance")
    }
}

internal fun PacingSettings.view() = PacingSettingsView(observationOnly, quotaPressurePercent, autoPoolSize)
