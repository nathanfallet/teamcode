package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*

/** Transport bounds, injectable for local acceptance; callers cannot change them through HTTP. */
data class ForecastLivePolicy(val intervalMillis: Long = 15_000, val lifetimeMillis: Long = 300_000) {
    init { require(intervalMillis in 25..60_000 && lifetimeMillis in intervalMillis..300_000) }
}

@Serializable
private data class ForecastLiveFrame(val generatedAtMillis: Long, val accountId: String?, val html: String, val options: String)

/** Browser-only progressive enhancement: cookie authentication, bounded writes and fresh shared authorization. */
internal fun Route.forecastLiveRoutes(forecasts: ForecastService, policy: ForecastLivePolicy) {
    val script = requireNotNull(object {}.javaClass.getResourceAsStream("/me/nathanfallet/teamcode/presentation/forecasts.js")) {
        "Missing forecast browser resource"
    }.bufferedReader().use { it.readText() }
    get("/dashboard/assets/forecasts.js") {
        call.principal.requireAdmin()
        call.respondText(script, ContentType.parse("application/javascript"))
    }
    route("/dashboard/forecasts/stream") {
        install(io.ktor.server.http.HttpRequestLifecycle) { cancelCallOnClose = true }
        get {
            val actor = call.principal
            val account = call.singleForecastAccount()
            val initial = forecasts.load(actor, account) // Denials remain ordinary HTTP errors before headers.
            call.response.header("X-Accel-Buffering", "no")
            call.respondBytesWriter(ContentType.Text.EventStream) {
                withTimeoutOrNull(policy.lifetimeMillis) {
                    writeStringUtf8("retry: 5000\n\n"); flush()
                    var snapshot = initial
                    while (currentCoroutineContext().isActive) {
                        val frame = Json.encodeToString(ForecastLiveFrame(snapshot.generatedAtMillis, snapshot.account?.id,
                            forecastDashboardContent(snapshot), forecastAccountOptions(snapshot))).encodeToByteArray()
                        if (frame.size > 4 * 1024 * 1024) {
                            writeStringUtf8("event: unavailable\ndata: {\"reason\":\"unavailable\"}\n\n"); flush()
                            return@withTimeoutOrNull
                        }
                        writeStringUtf8("event: snapshot\ndata: "); writeFully(frame); writeStringUtf8("\n\n"); flush()
                        delay(policy.intervalMillis)
                        snapshot = try { forecasts.load(actor, account) }
                        catch (error: TeamFailure) {
                            val reason = if (error.status in setOf(401, 403)) "access" else "unavailable"
                            writeStringUtf8("event: unavailable\ndata: {\"reason\":\"$reason\"}\n\n"); flush()
                            return@withTimeoutOrNull
                        }
                    }
                }
            }
        }
    }
}
