package me.nathanfallet.teamcode.presentation

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import me.nathanfallet.teamcode.domain.capacity.*

private fun decimal(value: Double) = String.format(Locale.ROOT, "%.1f", value)
private fun timestamp(value: Long?) = value?.let { Instant.ofEpochMilli(it).toString() } ?: "Unknown"

/** Options are rendered once by the server and reused by its authenticated live snapshot stream. */
internal fun forecastAccountOptions(snapshot: ForecastSnapshot) = snapshot.accounts.joinToString("") {
    """<option value="${escape(it.id)}" ${if (it.id == snapshot.account?.id) "selected" else ""}>${escape(it.displayName)} · ${escape(it.provider)}</option>"""
}

/** Progressive enhancement preserves manual refresh when scripting is disabled or unavailable. */
internal fun forecastDashboard(snapshot: ForecastSnapshot, live: Boolean = false): String {
    val controls = """<form method="get"><label>Subscription account <select id="forecast-account" name="account" ${if (snapshot.accounts.isEmpty()) "disabled" else ""}>${forecastAccountOptions(snapshot)}</select></label><button>Show account / refresh</button></form>"""
    if (!live) return controls + forecastDashboardContent(snapshot)
    return controls + """<p><span id="forecast-live-status" role="status" aria-live="polite" data-state="connecting">Live updates are connecting. Manual refresh remains available.</span> <button type="button" id="forecast-live-toggle">Pause live updates</button></p><div id="forecast-content" data-account="${escape(snapshot.account?.id.orEmpty())}" data-snapshot="${snapshot.generatedAtMillis}">${forecastDashboardContent(snapshot)}</div><script defer src="/dashboard/assets/forecasts.js"></script><noscript><p>Live updates require JavaScript. Use Show account / refresh to update this snapshot.</p></noscript>"""
}

/** The same escaped content feeds initial HTML and live frames, with no duplicate chart implementation. */
internal fun forecastDashboardContent(snapshot: ForecastSnapshot): String = with(snapshot) {
    val selected = account ?: return "<div class='empty'><strong>No subscription accounts yet. Add or import an account from the TeamCode CLI.</strong></div>"
    val cards = windows.mapIndexed { index, series ->
        val status = when (series.forecast.state) {
            QuotaForecast.State.UNKNOWN -> "Rate unknown"
            QuotaForecast.State.STALE -> "Stale observation"
            QuotaForecast.State.EXHAUSTED -> "Pressure threshold reached"
            QuotaForecast.State.IDLE -> "No measured burn"
            QuotaForecast.State.RESET_FIRST -> "Reset before pressure threshold"
            QuotaForecast.State.EXHAUSTS_FIRST -> "Pressure threshold before reset"
        }
        val eta = when (series.forecast.state) {
            QuotaForecast.State.RESET_FIRST -> "Reset occurs first"
            QuotaForecast.State.EXHAUSTED -> "Already at threshold"
            QuotaForecast.State.EXHAUSTS_FIRST -> series.forecast.reachesThresholdAtMillis?.let {
                "Around " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(it))
            } ?: "Unknown"
            else -> "Unknown"
        }
        """<section class="forecast-card"><h2>${escape(series.quota.window)}</h2><p><strong>$status</strong> · ${decimal(series.quota.utilization * 100)}% observed</p>${forecastChart(series, generatedAtMillis, pressureThreshold, "forecast-$index")}<dl><dt>Latest observation (UTC)</dt><dd>${timestamp(series.quota.observedAtMillis)}</dd><dt>Reset (UTC)</dt><dd>${timestamp(series.quota.resetsAtMillis)}</dd><dt>Recent speed</dt><dd>${series.forecast.pointsPerHour?.let { "${decimal(it)} percentage points / hour" } ?: "Unknown"}</dd><dt>Pressure ETA (UTC)</dt><dd>$eta</dd><dt>Latest continuous segment</dt><dd>${series.learningSamples} measurements over ${series.learningSpanMillis / 1000} seconds</dd></dl></section>"""
    }.joinToString("")
    """<style>.forecast-card{margin:0 0 18px;padding:18px 20px;background:var(--panel);border:1px solid var(--line);border-radius:var(--radius)}.forecast-card h2{margin-top:0}.forecast-card svg{display:block;width:100%;max-width:900px;height:auto}.forecast-card dl{display:grid;grid-template-columns:minmax(180px,1fr) 2fr;gap:8px}.forecast-card dd{margin:0;overflow-wrap:anywhere}.forecast-card dt{color:var(--muted)}</style><p>Snapshot: ${timestamp(generatedAtMillis)}. ${escape(selected.provider)} · ${escape(selected.state)}${if (selected.disabled) " · Disabled" else ""} · Pool: ${escape(selected.poolId ?: "shared")}.</p><details class='help'><summary>How to read these charts</summary><p>Each curve is one observed window on this account. Solid lines are measurements; dashed lines are conditional projections, clipped at reset or one hour ahead. Hover a point for its value and time. Gaps, resets and decreases start separate segments. Percentages from different subscriptions are never added.</p><p>The pressure threshold is ${decimal(pressureThreshold * 100)}%. A rate needs at least three continuous measurements spanning a minute and a known reset; fresh data is at most five minutes old. Unknown data is shown explicitly. These window estimates do not grant account/model access or describe the member's current controller decision; see <a href="/dashboard/agent-guidance">Agent guidance</a>.</p></details>${if (truncated) "<p class='notice'>Only a bounded subset of accounts, windows or history is shown. Missing history may prevent learning a rate.</p>" else ""}${if (windows.isEmpty()) "<div class='empty'><strong>No provider windows have been observed for this account. No capacity estimate is available.</strong></div>" else cards}"""
}

/** Draw only supplied segments; presentation never joins discontinuities or invents missing values. */
internal fun forecastChart(series: ForecastSeries, now: Long, threshold: Double, id: String): String {
    if (series.segments.isEmpty()) return "<div class='empty'><strong>No observations in the past hour.</strong></div>"
    val from = now - 3_600_000
    val until = now + 3_600_000
    fun x(at: Long) = 48 + (at.toDouble() - from) / (until - from) * 572
    fun y(utilization: Double) = 180 - utilization.coerceIn(0.0, 1.0) * 156
    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)
    val curves = series.segments.joinToString("") { segment ->
        val points = segment.joinToString(" ") { "${decimal(x(it.observedAtMillis))},${decimal(y(it.utilization))}" }
        val dots = segment.joinToString("") { """<circle cx="${decimal(x(it.observedAtMillis))}" cy="${decimal(y(it.utilization))}" r="3" fill="#3ddc97"><title>${decimal(it.utilization * 100)}% · ${timestamp(it.observedAtMillis)}</title></circle>""" }
        """<polyline class="quota-history" points="$points" fill="none" stroke="#3ddc97" stroke-width="2"/>$dots"""
    }
    val rate = series.forecast.pointsPerHour
    val at = series.forecast.reachesThresholdAtMillis
    val reset = series.quota.resetsAtMillis
    val projectTo = if (at != null && reset != null) minOf(at, reset, until) else null
    val projection = if (rate != null && rate.isFinite() && rate > 0 && projectTo != null && projectTo > series.quota.observedAtMillis &&
        series.forecast.state in setOf(QuotaForecast.State.RESET_FIRST, QuotaForecast.State.EXHAUSTS_FIRST)) {
        val projected = series.quota.utilization + rate / 100 * (projectTo - series.quota.observedAtMillis) / 3_600_000
        """<line class="quota-projection" x1="${decimal(x(series.quota.observedAtMillis))}" y1="${decimal(y(series.quota.utilization))}" x2="${decimal(x(projectTo))}" y2="${decimal(y(projected))}" stroke="#f6c453" stroke-width="2" stroke-dasharray="7 5"/>"""
    } else ""
    val grid = listOf(0.0, .5, 1.0).joinToString("") { value ->
        """<line x1="48" y1="${decimal(y(value))}" x2="620" y2="${decimal(y(value))}" stroke="#1f2a35"/><text x="5" y="${decimal(y(value) + 5)}" fill="#8b98a5">${(value * 100).toInt()}%</text>"""
    }
    return """<svg xmlns="http://www.w3.org/2000/svg" role="img" aria-labelledby="$id" viewBox="0 0 640 220"><title id="$id">${escape(series.quota.window)} utilization: observations and conditional projection</title>$grid<line x1="48" y1="${decimal(y(threshold))}" x2="620" y2="${decimal(y(threshold))}" stroke="#c9a043" stroke-dasharray="3 5"/><line x1="334" y1="24" x2="334" y2="180" stroke="#8b98a5" stroke-dasharray="2 5"/>$curves$projection<text x="48" y="207" fill="#8b98a5">${time.format(Instant.ofEpochMilli(from))} UTC</text><text x="334" y="207" text-anchor="middle" fill="#8b98a5">Now</text><text x="620" y="207" text-anchor="end" fill="#8b98a5">${time.format(Instant.ofEpochMilli(until))} UTC</text></svg>"""
}
