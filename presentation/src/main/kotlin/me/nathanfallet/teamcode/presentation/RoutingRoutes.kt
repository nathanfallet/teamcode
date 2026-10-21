package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.*

private fun RouteRule.view() = RouteRuleView(id, pattern, targets.map { RouteTargetView(it.provider, it.model) }, priority, enabled, version)
internal fun Route.routingRoutes(routes: RoutingStore, origin: String) {
    get("/api/v1/routes") {
        call.principal.requireAdmin()
        call.respond(routes.list(call.principal.teamId).map { it.view() })
    }
    put("/api/v1/routes/{id}") {
        val value = call.jsonBody<RouteRuleView>()
        if (value.id != call.parameters["id"]) throw TeamFailure("invalid_route", 400, "Route identifiers must match.")
        call.respond(routes.save(call.principal, RouteRule(value.id, value.pattern, value.targets.map { RouteTarget(it.provider, it.model) }, value.priority, value.enabled, value.version)).view())
    }
    delete("/api/v1/routes/{id}") {
        routes.delete(call.principal, call.parameters["id"].orEmpty(), call.jsonBody<RouteDeleteRequest>().version)
        call.respond(SuccessResponse())
    }
    get("/dashboard/routes") {
        val actor = call.principal; actor.requireAdmin()
        val existing = routes.list(actor.teamId)
        fun editor(rule: RouteRule): String = """<form method="post" action="/dashboard/routes">${csrf(actor)}
<input type="hidden" name="version" value="${rule.version}">
<label>Route ID <input name="id" value="${escape(rule.id)}" ${if (rule.version > 0) "readonly" else ""} required></label>
<label>Requested model pattern <input name="pattern" value="${escape(rule.pattern)}" required></label>
<label>Priority <input name="priority" type="number" min="-100" max="100" value="${rule.priority}" required></label>
<label><input name="enabled" type="checkbox" ${if (rule.enabled) "checked" else ""}> Enabled</label>
<label>Ordered targets (provider:model, comma separated; omit :model to retain the requested model)<input name="targets" size="90" value="${escape(rule.targets.joinToString(", ") { it.provider + (it.model?.let { m -> ":$m" } ?: "") })}" required></label>
<button name="action" value="save">Save route</button>${if (rule.version > 0) "<button name=\"action\" value=\"delete\">Delete route</button>" else ""}</form>"""
        call.respondText(call.page("Model routes", """<details class='help'><summary>How this works</summary><p>Rules are matched by descending priority, then route ID. The first matching enabled rule supplies the ordered targets. Without a match, requests use their original provider and model.</p></details><p>Example: <code>claude-opus-*</code> with <code>anthropic, codex:gpt-6-astra</code>. Configure exact model IDs supported by your accounts. Both requested and resolved models must pass the model policy. Fallback only follows unavailable capacity or a definite pre-stream rejection; ambiguous failures and committed streams are never replayed.</p><p>Cross-provider translation is experimental and disabled by default. An administrator can enable it in <a href="/dashboard/models">model settings</a>. While disabled, cross-provider targets are skipped; native targets remain eligible. When enabled, cross-provider routes support portable text, images and function tools. Signed/encrypted reasoning, built-in tools and unsupported controls return an explicit compatibility error.</p>${existing.joinToString("") { "<h2>${escape(it.id)}</h2>" + editor(it) }}<h2>Add a route</h2>${editor(RouteRule("", "", listOf(RouteTarget("anthropic"))))}"""), ContentType.Text.Html)
    }
    post("/dashboard/routes") {
        val input = call.form(origin.trimEnd('/'))
        val id = input["id"].orEmpty()
        val version = input["version"]?.toLongOrNull() ?: -1
        if (input["action"] == "delete") routes.delete(call.principal, id, version)
        else {
            val targets = input["targets"].orEmpty().split(',').map { raw ->
                val pieces = raw.trim().split(':', limit = 2)
                RouteTarget(pieces[0], pieces.getOrNull(1))
            }
            routes.save(call.principal, RouteRule(id, input["pattern"].orEmpty(), targets, input["priority"]?.toIntOrNull() ?: 101, input["enabled"] == "on", version))
        }
        call.respondRedirect("/dashboard/routes")
    }
}
