package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.resources.get
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.api.resources.ForecastsResource
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** Read-only administration routes; the shared service checks current role and account ownership. */
internal fun Route.forecastRoutes(forecasts: ForecastService) {
    get<ForecastsResource> { resource ->
        call.singleForecastAccount()
        call.respond(forecasts.load(call.principal, resource.account).view())
    }
    get("/dashboard/forecasts") {
        val snapshot = forecasts.load(call.principal, call.singleForecastAccount())
        call.respondText(page("Quota forecasts", forecastDashboard(snapshot, live = true)), ContentType.Text.Html)
    }
}

internal fun ApplicationCall.singleForecastAccount(): String? {
    val accounts = request.queryParameters.getAll("account") ?: return null
    if (accounts.size != 1) throw TeamFailure("invalid_account", 400, "Choose one account.")
    return accounts.single()
}

private fun ForecastAccount.view() = ForecastAccountView(id, provider, displayName, state, disabled, poolId)
private fun ForecastSnapshot.view() = ForecastSnapshotView(generatedAtMillis, accounts.map { it.view() }, account?.view(), windows.map { series ->
    with(series) { ForecastWindowView(quota.window, quota.utilization, quota.observedAtMillis, quota.resetsAtMillis, forecast.state.name,
        forecast.pointsPerHour, forecast.reachesThresholdAtMillis,
        segments.map { segment -> segment.map { ForecastSampleView(it.utilization, it.observedAtMillis, it.resetsAtMillis) } }, learningSamples, learningSpanMillis) }
}, truncated, pressureThreshold)
