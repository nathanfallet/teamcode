package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.math.BigDecimal
import java.time.LocalDate
import me.nathanfallet.teamcode.api.formatUnits
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.accounts.AccountManagement
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*

/**
 * The automatic home pool size and the API price table (ADR 0002). The setting itself is stored with the controller
 * settings (`autoPoolSize` on `PUT /api/v1/policy/pacing`); every rule is in [PoolSizingService] and the stores.
 */
internal fun Route.poolSizeRoutes(sizing: PoolSizingService, prices: ApiPriceStore, settings: PacingSettingsStore, accounts: AccountManagement?, origin: String) {
    get("/api/v1/pools/size") { call.respond(sizing.report(call.principal).view()) }
    get("/api/v1/policy/api-prices") { call.respond(ApiPriceTableView(prices.list(call.principal).map { it.view() })) }
    put("/api/v1/policy/api-prices") {
        val input = call.jsonBody<ApiPriceTableView>()
        call.respond(ApiPriceTableView(prices.replace(call.principal, input.prices.map { it.price() }).map { it.view() }))
    }
    post("/api/v1/policy/api-prices/load-defaults") { call.respond(ApiPriceTableView(prices.loadDefaults(call.principal).map { it.view() })) }
    get("/dashboard/capacity/pool-size") {
        val actor = call.principal
        actor.requireAdmin()
        val report = sizing.report(actor)
        val table = prices.list(actor)
        val names = accounts?.list(actor)?.associate { it.id to accountCell(it.displayName, it.email) }.orEmpty()
        call.respondText(call.page("Automatic pool size", poolSizeContent(actor, report, table, names),
            "Size the shared home pool from the connected subscriptions. Members keep their weighted share of it."), ContentType.Text.Html)
    }
    post("/dashboard/capacity/pool-size") {
        val input = call.form(origin.trimEnd('/'))
        settings.setAutoPoolSize(call.principal, input["autoPoolSize"] == "on")
        call.respondRedirect("/dashboard/capacity/pool-size")
    }
    post("/dashboard/capacity/api-prices") {
        val input = call.form(origin.trimEnd('/'))
        val actor = call.principal
        val row = ApiPriceView(input["provider"].orEmpty().trim(), input["model"].orEmpty().trim().lowercase(), input["effectiveFrom"].orEmpty().trim(),
            dollars(input["input"]), dollars(input["cacheWrite"]), dollars(input["cacheRead"]), dollars(input["output"])).price()
        if (input["remove"] == "on") prices.remove(actor, row.provider, row.model, row.effectiveFromDay) else prices.put(actor, row)
        call.respondRedirect("/dashboard/capacity/pool-size#prices")
    }
    post("/dashboard/capacity/api-prices/load-defaults") {
        call.form(origin.trimEnd('/'))
        prices.loadDefaults(call.principal)
        call.respondRedirect("/dashboard/capacity/pool-size#prices")
    }
}

/** Dollars per million tokens typed in the dashboard, stored as micro-dollars per million tokens. */
private fun dollars(value: String?): Long {
    val text = value?.trim().orEmpty().ifEmpty { "0" }
    val amount = text.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it.scale() <= 6 }
        ?: throw TeamFailure("invalid_api_price", 400, "Type prices in dollars per million tokens, such as 3 or 0.30.")
    return try { amount.multiply(BigDecimal(1_000_000)).longValueExact() }
    catch (_: ArithmeticException) { throw TeamFailure("invalid_api_price", 400, "That price is too large.") }
}

private val PRICE_DAY = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
internal fun ApiPriceView.price(): ApiPrice {
    val day = effectiveFrom.takeIf { PRICE_DAY.matches(it) }?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() }
        ?: throw TeamFailure("invalid_api_price", 400, "Use an effective date written YYYY-MM-DD.")
    return ApiPrice(provider, model, day, inputMicros, cacheWriteMicros, cacheReadMicros, outputMicros)
}
internal fun ApiPrice.view() = ApiPriceView(provider, model, LocalDate.ofEpochDay(effectiveFromDay).toString(), inputMicros, cacheWriteMicros, cacheReadMicros, outputMicros)

internal fun PoolSizeReport.view() = PoolSizeView(enabled, manualDailyUnits, automaticDailyUnits, automaticComputedAtMillis, effectiveDailyUnits,
    if (usesAutomatic) "automatic" else "manual",
    estimate.dailyMicros, estimate.dailyUnits, estimate.reason, creditsPerMicro, unpricedModels,
    estimate.accounts.map { share ->
        PoolSizeAccountView(share.accountId, share.status.name.lowercase(), share.dailyMicros, share.binding,
            capacities[share.accountId].orEmpty().map { WindowCapacityView(it.window, it.fullWindowMicros, it.samples) })
    })

private fun money(micros: Long?): String = micros?.let { "$" + BigDecimal(it).movePointLeft(6).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() } ?: "unknown"
private fun perMillion(micros: Long): String = BigDecimal(micros).movePointLeft(6).stripTrailingZeros().toPlainString()

private fun poolSizeContent(actor: Principal, report: PoolSizeReport, prices: List<ApiPrice>, names: Map<String, String>): String {
    val automatic = report.usesAutomatic
    val reasonText = when (report.estimate.reason) {
        PoolSizeEstimate.NO_ACCOUNTS -> "No subscription account in the home pool."
        PoolSizeEstimate.NO_ESTIMATE -> "No account has an estimate yet: each needs three samples of a five-point rise on its windows, with priced traffic between them."
        PoolSizeEstimate.NO_PRICED_USAGE -> "No priced traffic in the last seven days, so capacity cannot be converted to credits. Add prices for the models in use."
        else -> null
    }
    val status = """<div class="notice"><strong>Today's home pool budget: ${formatUnits(report.effectiveDailyUnits)} credits (${if (automatic) "automatic" else "manual"})</strong>
        <p>Manual budget: ${formatUnits(report.manualDailyUnits)} credits. Automatic value: ${report.automaticDailyUnits?.let { "${formatUnits(it)} credits, computed ${java.time.Instant.ofEpochMilli(report.automaticComputedAtMillis!!)}" } ?: "none yet"}.
        Current estimate: ${report.estimate.dailyUnits?.let { formatUnits(it) + " credits" } ?: "none"} (${money(report.estimate.dailyMicros)} a day in API-equivalent${report.creditsPerMicro?.let { ", %.4f credits per micro-dollar".format(it) }.orEmpty()}).</p>
        ${reasonText?.let { "<p>${escape(it)} The manual budget applies.</p>" }.orEmpty()}
        ${if (report.enabled && !automatic && reasonText == null) "<p>The automatic value is missing or older than an hour; the manual budget applies until the next computation.</p>" else ""}</div>"""
    val toggle = """<form method="post" action="/dashboard/capacity/pool-size">${csrf(actor)}<label><input type="checkbox" name="autoPoolSize" ${if (report.enabled) "checked" else ""}> Automatic shared pool size</label>
        <p>Computes the home pool's daily budget from the remaining capacity of its subscription accounts every ten minutes, moving at most 20% an hour. Each member still receives the pool budget × their weight / the total weight, capped by their personal cap. Unchecked, the hand-typed budget on the Capacity page applies, unchanged. Named pools and shared overflow reserves stay manual.</p><button>Save</button></form>"""
    val rows = report.estimate.accounts.joinToString("") { share ->
        val windows = report.capacities[share.accountId].orEmpty().joinToString("<br>") { "${escape(it.window)}: ${money(it.fullWindowMicros)} per full window · ${it.samples} sample${if (it.samples == 1) "" else "s"}" }
        val label = when (share.status) {
            PoolContribution.COUNTED -> "${money(share.dailyMicros)} a day" + (share.binding?.let { " · bound by ${escape(it)}" }.orEmpty())
            PoolContribution.UNAVAILABLE -> "Nothing: disabled, pending handover, reconnect required or cooling down"
            PoolContribution.NO_FRESH_OBSERVATION -> "Left out: no fresh five-hour or weekly observation"
            PoolContribution.NO_ESTIMATE -> "Left out until estimated: an observed window has fewer than three samples"
        }
        "<tr><td>${names[share.accountId] ?: "<code>${escape(share.accountId)}</code>"}</td><td>$label</td><td><small>$windows</small></td></tr>"
    }
    val accountsTable = "<h2>Accounts</h2>" + if (rows.isEmpty()) empty("No account in the home pool") else
        "<table><thead><tr><th>Account</th><th>Daily capacity</th><th>Learned windows</th></tr></thead><tbody>$rows</tbody></table>"
    val unpriced = if (report.unpricedModels.isEmpty()) "" else "<p class=\"notice\">Models without a price (excluded from the ratio and from samples): ${report.unpricedModels.joinToString(", ") { "<code>${escape(it)}</code>" }}</p>"
    val priceRows = prices.joinToString("") { p ->
        """<tr><td>${escape(p.provider)}</td><td><code>${escape(p.model)}</code></td><td>${LocalDate.ofEpochDay(p.effectiveFromDay)}</td><td class="num">${perMillion(p.inputMicros)}</td><td class="num">${perMillion(p.cacheWriteMicros)}</td><td class="num">${perMillion(p.cacheReadMicros)}</td><td class="num">${perMillion(p.outputMicros)}</td>
        <td><form method="post" action="/dashboard/capacity/api-prices">${csrf(actor)}<input type="hidden" name="remove" value="on"><input type="hidden" name="provider" value="${escape(p.provider)}"><input type="hidden" name="model" value="${escape(p.model)}"><input type="hidden" name="effectiveFrom" value="${LocalDate.ofEpochDay(p.effectiveFromDay)}"><button>Remove</button></form></td></tr>"""
    }
    val priceTable = (if (prices.isEmpty()) empty("No price yet", "<p>No price applies until you enter one or load Anthropic's list prices below.</p>") else
        "<table><thead><tr><th>Provider</th><th>Model or prefix</th><th>From</th><th class=\"num\">Input</th><th class=\"num\">Cache write</th><th class=\"num\">Cache read</th><th class=\"num\">Output</th><th></th></tr></thead><tbody>$priceRows</tbody></table>")
    val addForm = """<form class="fields" method="post" action="/dashboard/capacity/api-prices">${csrf(actor)}<p class="card-title">Add or replace a price</p>
        <label>Provider <input name="provider" value="anthropic" maxlength="32" required></label><label>Model or family prefix <input name="model" maxlength="128" placeholder="claude-sonnet-4" required></label>
        <label>Effective from (UTC) <input type="date" name="effectiveFrom" value="${LocalDate.now(java.time.ZoneOffset.UTC)}" required></label>
        <label>Input $ / million tokens <input name="input" inputmode="decimal" required></label><label>Cache write $ / million <input name="cacheWrite" inputmode="decimal" required></label>
        <label>Cache read $ / million <input name="cacheRead" inputmode="decimal" required></label><label>Output $ / million <input name="output" inputmode="decimal" required></label><button>Save price</button></form>"""
    val loadForm = """<form method="post" action="/dashboard/capacity/api-prices/load-defaults">${csrf(actor)}<p class="card-title">Load current Anthropic prices</p>
        <p>Adds Anthropic's API list prices for the current Claude models, read on ${LocalDate.ofEpochDay(AnthropicListPrices.CHECKED_ON_DAY)} from <a href="${AnthropicListPrices.SOURCE}">${AnthropicListPrices.SOURCE}</a>, dated that day (cache writes at the five-minute rate). Rows you already have for the same model and date are kept. Check them against the current list before relying on them.</p><button>Load current Anthropic prices</button></form>"""
    val explanation = help("How the automatic size is computed", """<p>Each account's windows are learned in <strong>API-equivalent</strong>: the dollars the provider's pay-as-you-go API would charge for the same tokens, priced with the table below. A sample pairs two readings of the same window at least five points apart and divides the priced tokens settled between them by the rise; the median of the last seven days, with at least three samples, is the window's full capacity.</p>
        <p>An account's daily capacity is the smaller of its five-hour rate (five-hour capacity × quota pressure threshold × 24 / 5) and its weekly allowance (weekly points left before the threshold × weekly capacity / days left before the reset). Accounts that cannot serve (disabled, reconnect required, cooling down) count for nothing, and accounts without an estimate yet are left out of the sum (with no estimate at all, the manual budget applies); an account whose weekly window is at the threshold counts for nothing until its reset comes within the day. The pool total in credits is that sum × the credits charged per API-equivalent micro-dollar over the last seven days.</p>""")
    return explanation + status + toggle + accountsTable + unpriced + "<h2 id=\"prices\">API prices</h2><p>Dollars per million tokens. A model row applies from its date until a later row for the same model; an exact model wins over the longest prefix.</p>" + priceTable + addForm + loadForm
}
