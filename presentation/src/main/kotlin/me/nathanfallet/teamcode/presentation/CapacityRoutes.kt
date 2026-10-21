package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.formatUnits
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*

private fun MemberCapacity.view() = CapacityView(policy.memberId, dailyAllowanceUnits, policy.maxConcurrent, policy.poolId, chargedAndReservedUnits, activeRequests, resetsAtMillis, policy.dailyUnits, policy.weight, poolTotalWeight, poolDailyUnits, queuedRequests, groups.map { GroupAllowanceView(it.group.id, it.group.name, it.group.dailyUnits, it.group.maxConcurrent, it.paused, it.usage.chargedAndReservedUnits, it.usage.activeRequests) }, budgetOverride?.let { BudgetOverrideSummaryView(it.id, it.dailyUnits, it.expiresAtMillis) }, baseDailyAllowanceUnits, policy.allowSharedOverflow, reservedUnits)
private fun PoolAccountStatus.view() = PoolAccountStatusView(name?.let { accountLabel(it, email) } ?: label, provider, state, disabled, usable,
    unavailable?.name?.lowercase(), cooldownUntilMillis, windows.map { AccountWindowView(it.window, it.utilization, it.observedAtMillis, it.resetsAtMillis) },
    poolId, accountId, name, email)
internal fun Route.capacityRoutes(admission: AdmissionStore, team: TeamService, origin: String, budgetOverrides: Boolean = false, groups: GroupService? = null) {
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
    get("/api/v1/pools/accounts") { call.respond(admission.poolAccounts(call.principal).map { it.view() }) }
    get("/api/v1/quotas") { call.respond(admission.quotas(call.principal).map { QuotaView(it.accountId, it.window, it.utilization, it.observedAtMillis, it.resetsAtMillis) }) }
    get("/dashboard/capacity") {
        val actor = call.principal
        val admin = actor.member.role.isAdministrator
        val people = if (admin) team.members(actor) else listOf(actor.member)
        val current = people.map { it to admission.capacity(actor, it.id) }
        val rows = current.joinToString("") { (member, capacity) -> memberRow(actor, member, capacity, budgetOverrides) }
        val members = """<h2>Members</h2><table class="stack"><thead><tr><th class="col-member">Member</th><th class="col-today">Today</th><th>${if (admin) "Settings" else "Policy"}</th></tr></thead><tbody>$rows</tbody></table>"""
        val pools = if (admin) {
            val budgets = admission.poolBudgets(actor)
            val byPool = current.filterNot { it.first.disabled }.groupBy { it.second.policy.poolId.orEmpty() }
            val forms = (budgets.map { it to true } + (PoolBudget("", 1_000_000) to false)).joinToString("") { (budget, saved) ->
                val inPool = byPool[budget.poolId].orEmpty()
                val weight = inPool.firstOrNull()?.second?.poolTotalWeight ?: 0
                val effect = when {
                    !saved -> "New pool: name it and give it a daily budget"
                    inPool.isEmpty() -> "No primary members"
                    else -> "${inPool.size} member${if (inPool.size == 1) "" else "s"}, total weight $weight: <strong>${formatUnits(if (weight == 0L) 0 else budget.dailyUnits / weight)}</strong> credits / day per weight point"
                }
                """<form method="post" action="/dashboard/capacity/pool">${csrf(actor)}<p class="card-title">${if (saved) escape(poolName(budget.poolId.ifEmpty { null })) else "Add or update a pool"}</p><label>Pool (empty = shared home pool) <input name="pool" maxlength="36" value="${escape(budget.poolId)}">${hint("Members and accounts with this pool name share its budget and accounts.")}</label><label>Total daily credits <input type="number" name="daily" min="1" max="100000000000" value="${budget.dailyUnits}" required>${hint("${formatUnits(budget.dailyUnits)} credits / day · $effect")}</label><label><input type="checkbox" name="overflow" ${if (budget.sharedOverflow) "checked" else ""}>Shared overflow reserve (named pool with no primary members)</label><button>Save pool budget</button></form>"""
            }
            "<h2>Pool budgets</h2><div class=\"pool-forms\">$forms</div>"
        } else ""
        val groupList = groups?.list(actor)
        val groupSection = if (groupList == null) "" else "<h2>Groups</h2>" + (if (groupList.isEmpty()) empty("No group yet", if (admin) "<p><a href=\"/dashboard/groups\">Create a group</a> to cap a set of members together.</p>" else "") else
            groupList.joinToString("", "<table><thead><tr><th>Group</th><th>Credits today / cap</th><th class=\"num\">Active / cap</th></tr></thead><tbody>", "</tbody></table>") { summary ->
                val group = summary.group
                """<tr><td><a href="/dashboard/groups/${escape(group.id)}">${escape(group.name)}</a>${if (group.archived) " <span class=\"chip\">archived</span>" else ""}</td><td>${if (group.dailyUnits == 0L) "<span class=\"meter-label\"><strong>${formatUnits(summary.usage.chargedAndReservedUnits)}</strong> · no extra cap</span>" else meter(summary.usage.chargedAndReservedUnits, group.dailyUnits)}</td><td class="num">${summary.usage.activeRequests} / ${if (group.maxConcurrent == 0) "no cap" else group.maxConcurrent.toString()}</td></tr>"""
            } + if (admin) "<p><a href=\"/dashboard/groups\">Manage groups</a></p>" else "")
        val quotas = if (admin) {
            val windows = admission.quotas(actor)
            "<h2 id=\"windows\">Observed provider windows</h2>" + if (windows.isEmpty()) empty("No provider window observed yet") else
                windows.sortedByDescending { it.utilization }.joinToString("", "<table><thead><tr><th>Account</th><th>Window</th><th>Utilization</th><th>Resets (UTC)</th></tr></thead><tbody>", "</tbody></table>") { q ->
                    "<tr><td><code>${escape(q.accountId)}</code></td><td>${escape(q.window)}</td><td>${ratioMeter(q.utilization, "<strong>${"%.1f".format(q.utilization * 100)}%</strong>")}</td><td><small>${q.resetsAtMillis?.let(java.time.Instant::ofEpochMilli) ?: "unknown"}</small></td></tr>"
                }
        } else ""
        val explanation = help("How allowances are computed", """<p>Daily credits reset at midnight UTC. Each active member receives pool budget × their weight / the total active weight in their pool, optionally capped individually. Two developers weighted 5 and one support member weighted 1 receive 5/11, 5/11 and 1/11. Weight changes never erase existing spend; the total pool budget is also enforced. Credits count input, cache reads and cache creation at 1 each, and output at 5 each. They are an internal fairness budget, not Claude quota percentages.</p><p>Requests reserve a conservative upper estimate before dispatch. Missing usage keeps that reservation. Assign the same named pool to a member and dedicated accounts to protect their subscription capacity; other pools cannot use those accounts unless their pool is explicitly marked as shared overflow. Overflow requires member opt-in, has no primary members, and consumes both the home and source budgets. It never increases a member, group or launch allowance.</p>""")
        call.respondText(call.page("Capacity and allowances", explanation + pools + members + groupSection + quotas), ContentType.Text.Html)
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


/** One member: today's consumption, and for administrators the policy form with each field's effective value. */
private fun memberRow(actor: Principal, member: Member, current: MemberCapacity, budgetOverrides: Boolean): String {
    val policy = current.policy
    val admin = actor.member.role.isAdministrator
    val share = current.poolShare()
    val temporary = current.budgetOverride?.let { "<br><span class=\"chip warn\">override</span> <small>${formatUnits(it.dailyUnits)} until ${java.time.Instant.ofEpochMilli(it.expiresAtMillis)} · normal allocation ${formatUnits(current.baseDailyAllowanceUnits)}</small>" }.orEmpty()
    val groupLimits = current.groups.joinToString("") { group -> "<br><small><a href='/dashboard/groups/${escape(group.group.id)}'>${escape(group.group.name)}</a>: ${formatUnits(group.usage.chargedAndReservedUnits)} / ${if (group.group.dailyUnits == 0L) "no extra cap" else formatUnits(group.group.dailyUnits)} group credits · ${group.usage.activeRequests} / ${if (group.group.maxConcurrent == 0) "no extra cap" else group.group.maxConcurrent.toString()} active${if (group.paused) " · admission paused" else ""}</small>" }
    val usage = meter(current.chargedAndReservedUnits, current.dailyAllowanceUnits) + "<br><small>${current.activeRequests} active requests · ${current.queuedRequests} waiting</small>$groupLimits$temporary"
    val pool = escape(poolName(policy.poolId))
    val temporaryLink = if (budgetOverrides && admin) "<p><a href='/dashboard/capacity/${escape(member.id)}/overrides'>Temporary quota overrides</a></p>" else ""
    val edit = if (admin) """<form class="fields" method="post" action="/dashboard/capacity/${escape(member.id)}">${csrf(actor)}<label>Weight (0 = no allocation) <input type="number" name="weight" value="${policy.weight}" min="0" max="1000" required>${hint("Share of the pool relative to its other members. " + if (member.disabled) "Disabled members receive no allocation." else "Pool share: <strong>${formatUnits(share)}/day</strong> (${policy.weight} of ${current.poolTotalWeight} × ${formatUnits(current.poolDailyUnits)})")}</label><label>Optional daily cap (0 = none) <input type="number" name="daily" value="${policy.dailyUnits}" min="0" max="100000000000" required>${hint("Upper limit on the pool share. Effective today: <strong>${formatUnits(current.dailyAllowanceUnits)}</strong>${if (current.budgetOverride != null) " (temporary override)" else ""}")}</label><label>Concurrent requests <input type="number" name="concurrent" value="${policy.maxConcurrent}" min="1" max="32" required>${hint("Requests that may run at once; more requests wait in the queue.")}</label><label>Pool (empty = shared) <input name="pool" value="${escape(policy.poolId.orEmpty())}" maxlength="36">${hint("Currently $pool · ${formatUnits(current.poolDailyUnits)} credits / day")}</label><label><input type="checkbox" name="overflow" ${if (policy.allowSharedOverflow) "checked" else ""}>Allow shared overflow accounts</label><button>Save limits</button></form>"""
        else "<span class=\"meter-label\">$pool · weight ${policy.weight} / ${current.poolTotalWeight}${if (member.disabled) "" else " · pool share ${formatUnits(share)}"}</span><br><small>${if (policy.dailyUnits == 0L) "No personal cap" else "Personal cap ${formatUnits(policy.dailyUnits)}"} · ${policy.maxConcurrent} concurrent requests</small>"
    return """<tr id="member-${escape(member.id)}"><td><strong>${escape(member.username)}</strong>${if (member.disabled) " <span class=\"chip danger\">disabled</span>" else ""}<br><small>$pool · weight ${policy.weight} / ${current.poolTotalWeight}</small></td><td>$usage</td><td>$edit$temporaryLink</td></tr>"""
}
