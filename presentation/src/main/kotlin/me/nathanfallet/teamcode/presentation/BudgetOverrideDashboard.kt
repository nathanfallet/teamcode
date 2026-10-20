package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*

internal fun Route.budgetOverrideDashboardRoutes(overrides: BudgetOverrideService, admission: AdmissionStore, origin: String) {
    get("/dashboard/capacity/{id}/overrides") {
        val actor = call.principal; val member = call.parameters["id"].orEmpty()
        val history = overrides.history(actor, member, call.request.queryParameters["before"])
        val current = admission.capacity(actor, member)
        val path = "/dashboard/capacity/${escape(member)}/overrides"
        val rows = history.overrides.joinToString("") { item ->
            val state = when { item.revokedAtMillis != null -> "Revoked"; item.id == history.activeOverrideId -> "Active"; else -> "Expired or inactive" }
            val revoke = if (item.id == history.activeOverrideId) """<form method="post" action="$path/${escape(item.id)}/revoke">${csrf(actor)}<label>Revocation reason <input name="reason" maxlength="240" required></label><button>Revoke override</button></form>""" else ""
            """<tr><td>${escape(item.id)}<br>$state<br>Created ${Instant.ofEpochMilli(item.createdAtMillis)} by ${escape(item.createdBy)}</td><td>${item.dailyUnits}<br>Until ${Instant.ofEpochMilli(item.expiresAtMillis)}<br>Pool ${escape(item.poolId ?: "shared")}</td><td>${escape(item.reason)}${item.replacesId?.let { "<br>Replaces ${escape(it)}" }.orEmpty()}${item.revokedAtMillis?.let { "<br>Revoked ${Instant.ofEpochMilli(it)} by ${escape(item.revokedBy.orEmpty())}<br>${escape(item.revocationReason.orEmpty())}" }.orEmpty()}$revoke</td></tr>"""
        }
        val older = history.nextBeforeId?.let { "<a href='$path?before=${escape(it)}'>Older overrides</a>" }.orEmpty()
        call.respondText(page("Temporary quota overrides", """<p><a href='/dashboard/capacity'>Capacity</a> · Member ${escape(member)}</p><p>Replace this member's normal daily allocation until a deadline, at the latest midnight UTC. Zero blocks new admissions. Existing spend is retained; this is a total daily allocation, not extra credits added on each save. The shared pool, group, run, concurrency and provider restrictions still apply. An increase can consume unspent shared-pool budget; use dedicated pools for physically protected capacity.</p><p>Current effective allocation: ${current.dailyAllowanceUnits}; normal allocation: ${current.baseDailyAllowanceUnits}; charged/reserved: ${current.chargedAndReservedUnits}. Active requests finish normally when an override expires or is revoked.</p><form method="post" action="$path">${csrf(actor)}<input type="hidden" name="replacesId" value="${escape(history.activeOverrideId.orEmpty())}"><label>Total daily credits <input type="number" name="daily" min="0" max="100000000000" value="${current.dailyAllowanceUnits}" required></label><label>Duration in minutes (ends at UTC reset if earlier) <input type="number" name="minutes" min="1" max="1440" value="60" required></label><label>Reason (no secrets) <input name="reason" maxlength="240" required></label><button>${if (history.activeOverrideId == null) "Grant" else "Replace"} temporary override</button></form><h2>Grant and revocation history</h2><p>$older</p><table><thead><tr><th>Identity and status</th><th>Allocation and scope</th><th>Reasons</th></tr></thead><tbody>$rows</tbody></table>"""), ContentType.Text.Html)
    }
    post("/dashboard/capacity/{id}/overrides") {
        val input = call.form(origin.trimEnd('/')); val member = call.parameters["id"].orEmpty()
        overrides.grant(call.principal, member, BudgetOverrideChange(
            input["daily"]?.toLongOrNull() ?: throw invalidOverride(), input["minutes"]?.toIntOrNull() ?: throw invalidOverride(),
            input["reason"].orEmpty(), input["replacesId"]?.takeIf { it.isNotBlank() }))
        call.respondRedirect("/dashboard/capacity/$member/overrides")
    }
    post("/dashboard/capacity/{id}/overrides/{overrideId}/revoke") {
        val input = call.form(origin.trimEnd('/')); val member = call.parameters["id"].orEmpty()
        overrides.revoke(call.principal, member, call.parameters["overrideId"].orEmpty(), input["reason"].orEmpty())
        call.respondRedirect("/dashboard/capacity/$member/overrides")
    }
}
private fun invalidOverride() = TeamFailure("invalid_budget_override", 400, "Daily credits and duration must be integers.")
