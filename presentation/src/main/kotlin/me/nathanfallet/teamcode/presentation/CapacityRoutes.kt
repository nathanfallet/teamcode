package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*

private fun MemberCapacity.view() = CapacityView(policy.memberId, dailyAllowanceUnits, policy.maxConcurrent, policy.poolId, chargedAndReservedUnits, activeRequests, resetsAtMillis, policy.dailyUnits, policy.weight, poolTotalWeight, poolDailyUnits, queuedRequests, groups.map { GroupAllowanceView(it.group.id, it.group.name, it.group.dailyUnits, it.group.maxConcurrent, it.paused, it.usage.chargedAndReservedUnits, it.usage.activeRequests) }, budgetOverride?.let { BudgetOverrideSummaryView(it.id, it.dailyUnits, it.expiresAtMillis) }, baseDailyAllowanceUnits, policy.allowSharedOverflow)
internal fun Route.capacityRoutes(admission: AdmissionStore, team: TeamService, origin: String, budgetOverrides: Boolean = false) {
    get("/api/v1/capacity") { call.respond(admission.capacity(call.principal).view()) }
    get("/api/v1/members/{id}/capacity") { call.respond(admission.capacity(call.principal, call.parameters["id"]!!).view()) }
    patch("/api/v1/members/{id}/policy") {
        val input = call.jsonBody<PolicyRequest>()
        val policy = admission.configure(call.principal, MemberPolicy(call.parameters["id"]!!, input.dailyUnits, input.maxConcurrent, input.poolId, input.weight, input.allowSharedOverflow))
        call.respond(admission.capacity(call.principal, policy.memberId).view())
    }
    get("/api/v1/pools/budgets") { call.respond(admission.poolBudgets(call.principal).map { PoolBudgetView(it.poolId, it.dailyUnits, it.sharedOverflow) }) }
    put("/api/v1/pools/budgets") {
        val input = call.jsonBody<PoolBudgetView>()
        val saved = admission.configurePool(call.principal, PoolBudget(input.poolId, input.dailyUnits, input.sharedOverflow))
        call.respond(PoolBudgetView(saved.poolId, saved.dailyUnits, saved.sharedOverflow))
    }
    get("/api/v1/quotas") { call.respond(admission.quotas(call.principal).map { QuotaView(it.accountId, it.window, it.utilization, it.observedAtMillis, it.resetsAtMillis) }) }
    get("/dashboard/capacity") {
        val actor = call.principal
        val people = if (actor.member.role.isAdministrator) team.members(actor) else listOf(actor.member)
        val rows = people.map { member ->
            val current = admission.capacity(actor, member.id)
            val policy = current.policy
            val temporary = current.budgetOverride?.let { "<br>Temporary allocation ${it.dailyUnits} until ${java.time.Instant.ofEpochMilli(it.expiresAtMillis)} · normal allocation ${current.baseDailyAllowanceUnits}" }.orEmpty()
            val temporaryLink = if (budgetOverrides && actor.member.role.isAdministrator) "<p><a href='/dashboard/capacity/${escape(member.id)}/overrides'>Temporary quota overrides</a></p>" else ""
            val groupLimits = current.groups.joinToString("<br>") { group -> "<a href='/dashboard/groups/${escape(group.group.id)}'>${escape(group.group.name)}</a>: ${group.usage.chargedAndReservedUnits} / ${if (group.group.dailyUnits == 0L) "no extra cap" else group.group.dailyUnits.toString()} group credits · ${group.usage.activeRequests} / ${if (group.group.maxConcurrent == 0) "no extra cap" else group.group.maxConcurrent.toString()} active${if (group.paused) " · admission paused" else ""}" }
            val edit = if (actor.member.role.isAdministrator) """<form method="post" action="/dashboard/capacity/${escape(member.id)}">${csrf(actor)}<label>Weight (0 = no allocation) <input type="number" name="weight" value="${policy.weight}" min="0" max="1000" required></label><label>Optional daily cap (0 = none) <input type="number" name="daily" value="${policy.dailyUnits}" min="0" max="100000000000" required></label><label>Concurrent requests <input type="number" name="concurrent" value="${policy.maxConcurrent}" min="1" max="32" required></label><label>Pool (empty = shared) <input name="pool" value="${escape(policy.poolId.orEmpty())}" maxlength="36"></label><label><input type="checkbox" name="overflow" ${if (policy.allowSharedOverflow) "checked" else ""}>Allow shared overflow accounts</label><button>Save limits</button></form>""" else "${policy.dailyUnits} daily credits · ${policy.maxConcurrent} concurrent requests"
            """<tr><td>${escape(member.username)}<br><small>${current.activeRequests} active requests · ${current.queuedRequests} waiting<br>${current.chargedAndReservedUnits} / ${current.dailyAllowanceUnits} charged/reserved credits<br>Weight ${policy.weight} / ${current.poolTotalWeight}<br>$groupLimits$temporary</small></td><td>$edit$temporaryLink</td></tr>"""
        }
        .joinToString("")
        val quotas = if (actor.member.role.isAdministrator) admission.quotas(actor).joinToString("") { q -> "<li>${escape(q.accountId)} · ${escape(q.window)}: ${"%.1f".format(q.utilization * 100)}% · resets ${q.resetsAtMillis?.let(java.time.Instant::ofEpochMilli) ?: "unknown"}</li>" } else ""
        val poolForm = if (actor.member.role.isAdministrator) {
            val budgets = admission.poolBudgets(actor)
            "<h2>Pool budgets</h2>" + (budgets + PoolBudget("", 1_000_000)).joinToString("") { budget ->
                """<form method="post" action="/dashboard/capacity/pool">${csrf(actor)}<label>Pool (empty = shared home pool) <input name="pool" maxlength="36" value="${escape(budget.poolId)}"></label><label>Total daily credits <input type="number" name="daily" min="1" max="100000000000" value="${budget.dailyUnits}" required></label><label><input type="checkbox" name="overflow" ${if (budget.sharedOverflow) "checked" else ""}>Shared overflow reserve (named pool with no primary members)</label><button>Save pool budget</button></form>"""
            }
        } else ""
        call.respondText(page("Capacity and allowances", """<p>Daily credits reset at midnight UTC. Each active member receives pool budget × their weight / the total active weight in their pool, optionally capped individually. Two developers weighted 5 and one support member weighted 1 receive 5/11, 5/11 and 1/11. Weight changes never erase existing spend; the total pool budget is also enforced. Credits count input, cache reads and cache creation at 1 each, and output at 5 each. They are an internal fairness budget, not Claude quota percentages.</p><p>Requests reserve a conservative upper estimate before dispatch. Missing usage keeps that reservation. Assign the same named pool to a member and dedicated accounts to protect their subscription capacity; other pools cannot use those accounts unless their pool is explicitly marked as shared overflow. Overflow requires member opt-in, has no primary members, and consumes both the home and source budgets. It never increases a member, group or launch allowance.</p><table><thead><tr><th>Member usage</th><th>Policy</th></tr></thead><tbody>$rows</tbody></table>$poolForm<h2>Observed provider windows</h2><ul>$quotas</ul>"""), ContentType.Text.Html)
    }
    post("/dashboard/capacity/pool") {
        val input = call.form(origin.trimEnd('/'))
        admission.configurePool(call.principal, PoolBudget(input["pool"].orEmpty(), input["daily"]?.toLongOrNull() ?: 0, input["overflow"] == "on"))
        call.respondRedirect("/dashboard/capacity")
    }
    post("/dashboard/capacity/{id}") {
        val input = call.form(origin.trimEnd('/'))
        val daily = input["daily"]?.toLongOrNull() ?: throw TeamFailure("invalid_policy", 400, "Daily allowance must be an integer.")
        val concurrent = input["concurrent"]?.toIntOrNull() ?: throw TeamFailure("invalid_policy", 400, "Concurrency must be an integer.")
        admission.configure(call.principal, MemberPolicy(call.parameters["id"]!!, daily, concurrent, input["pool"]?.takeIf { it.isNotBlank() }, input["weight"]?.toIntOrNull() ?: -1, input["overflow"] == "on"))
        call.respondRedirect("/dashboard/capacity")
    }
}
