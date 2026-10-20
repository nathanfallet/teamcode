package me.nathanfallet.teamcode.presentation

import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.PacingSettingsView
import me.nathanfallet.teamcode.domain.capacity.PacingSettings
import me.nathanfallet.teamcode.domain.capacity.PacingSettingsStore

/** Management endpoints are interactive-only; the store checks current administrator authority. */
internal fun Route.pacingSettingsRoutes(settings: PacingSettingsStore, origin: String) {
    get("/api/v1/policy/pacing") { call.respond(settings.get(call.principal).view()) }
    put("/api/v1/policy/pacing") {
        val input = call.jsonBody<PacingSettingsView>()
        call.respond(settings.save(call.principal, PacingSettings(input.observationOnly)).view())
    }
    post("/dashboard/agent-guidance/pacing") {
        val input = call.form(origin.trimEnd('/'))
        settings.save(call.principal, PacingSettings(input["observationOnly"] == "on"))
        call.respondRedirect("/dashboard/agent-guidance")
    }
}

internal fun PacingSettings.view() = PacingSettingsView(observationOnly)
