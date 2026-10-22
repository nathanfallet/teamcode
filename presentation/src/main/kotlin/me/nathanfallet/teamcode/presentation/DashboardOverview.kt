package me.nathanfallet.teamcode.presentation

import me.nathanfallet.teamcode.api.formatUnits
import me.nathanfallet.teamcode.domain.accounts.AccountManagement
import me.nathanfallet.teamcode.domain.capacity.AdmissionStore
import me.nathanfallet.teamcode.domain.capacity.MemberCapacity
import me.nathanfallet.teamcode.domain.team.*

/** Read-only services the overview summarizes; each absent service only removes its panel. */
internal data class OverviewSources(val admission: AdmissionStore? = null, val runs: AgentRunService? = null, val accounts: AccountManagement? = null)

private const val OVERVIEW_SESSIONS = 8
private const val OVERVIEW_ACCOUNTS = 6
private const val PRESSURE = .75

/**
 * Answers four questions in one screen: who consumes how much today, what remains per pool, which accounts approach their
 * limits and which agent sessions run. Members see their own figures; administrators see the team. Every read reuses an
 * existing, already-authorized service call, so the overview never shows more than the detailed pages would.
 */
internal suspend fun overviewContent(actor: Principal, team: TeamService, sources: OverviewSources, features: Set<DashboardFeature>): String {
    val admin = actor.member.role.isAdministrator
    val admission = sources.admission
    val people = if (admission == null) emptyList() else if (admin) team.members(actor) else listOf(actor.member)
    val capacities = if (admission == null) emptyList() else people.zip(admission.capacities(actor, people.map { it.id }))
    // The newest page of launches is the bounded read; a session running since before it is listed on the sessions page.
    val launches = sources.runs?.list(actor, null, all = admin)
    val runs = launches?.runs?.filter { it.state == RunState.RUNNING }
    val now = System.currentTimeMillis()
    // A window whose reset has passed no longer describes the account; it is refreshed by the next request.
    val quotas = if (admin && admission != null) admission.quotas(actor).filter { (it.resetsAtMillis ?: Long.MAX_VALUE) > now } else emptyList()
    val pool = if (admin) sources.accounts?.list(actor).orEmpty() else emptyList()
    val names = pool.associate { it.id to it.label }
    val pressured = quotas.groupBy { it.accountId }.mapValues { (_, windows) -> windows.maxBy { it.utilization } }
        .values.sortedByDescending { it.utilization }

    val cards = buildList {
        if (admission != null) {
            val enabled = capacities.filterNot { it.first.disabled }
            val used = enabled.sumOf { it.second.chargedAndReservedUnits }
            val allowed = enabled.sumOf { it.second.dailyAllowanceUnits }
            add(card(if (admin) "Team usage today" else "Your usage today", "<div class=\"big\">${formatUnits(used)}</div>" + meter(used, allowed, "of <strong>${formatUnits(allowed)}</strong> credits allocated")))
            add(card("Requests now", "<div class=\"big\">${capacities.sumOf { it.second.activeRequests }}</div><span class=\"meter-label\">active · ${capacities.sumOf { it.second.queuedRequests }} waiting</span>"))
        }
        if (runs != null) add(card("Running sessions", "<div class=\"big\">${runs.size}</div><span class=\"meter-label\">${if (admin) "across the team" else "of yours"}${if (launches.nextBeforeId != null) " · among the latest launches" else ""}</span>"))
        if (admin && admission != null) {
            val near = pressured.count { it.utilization >= PRESSURE }
            add(card("Accounts near their limit", "<div class=\"big\">$near</div><span class=\"meter-label\">of ${pressured.size} observed · ${percent(PRESSURE)} or more</span>"))
        }
    }
    val summary = if (cards.isEmpty()) "" else """<div class="cards">${cards.joinToString("")}</div>"""

    val consumption = if (admission == null) "" else section(if (admin) "Who consumes what today" else "Your allowance today",
        capacities.sortedByDescending { it.second.chargedAndReservedUnits }.joinToString("", "<table><thead><tr><th>Member</th><th>Used / allowance</th><th class=\"num\">Requests</th></tr></thead><tbody>", "</tbody></table>") { (member, capacity) ->
            """<tr><td><a href="/dashboard/capacity#member-${escape(member.id)}">${escape(member.username)}</a>${if (member.disabled) " <span class=\"chip danger\">disabled</span>" else ""}${if (capacity.budgetOverride != null) " <span class=\"chip warn\">override</span>" else ""}<br><small>${escape(poolName(capacity.policy.poolId))} · weight ${capacity.policy.weight}</small></td><td>${meter(capacity.chargedAndReservedUnits, capacity.dailyAllowanceUnits)}</td><td class="num">${capacity.activeRequests} · ${capacity.queuedRequests} waiting</td></tr>"""
        })

    val pools = if (!admin || admission == null) "" else {
        val byPool = capacities.filterNot { it.first.disabled }.groupBy { it.second.policy.poolId.orEmpty() }
        val budgets = admission.poolBudgets(actor).associateBy { it.poolId }
        val ids = (byPool.keys + budgets.keys).sortedWith(compareBy({ it.isNotEmpty() }, { it }))
        section("What remains per pool", ids.joinToString("", "<table><thead><tr><th>Pool</th><th>Used / daily budget</th><th class=\"num\">Remaining</th></tr></thead><tbody>", "</tbody></table>") { pool ->
            val members = byPool[pool].orEmpty()
            val budget = members.firstOrNull()?.second?.poolDailyUnits ?: budgets[pool]?.dailyUnits ?: 0
            val used = members.sumOf { it.second.chargedAndReservedUnits }
            val overflow = budgets[pool]?.sharedOverflow == true
            val detail = if (members.isEmpty()) "No primary members${if (overflow) " · shared overflow reserve" else ""}" else "${members.size} member${if (members.size == 1) "" else "s"}${if (overflow) " · shared overflow" else ""}"
            """<tr><td>${escape(poolName(pool.ifEmpty { null }))}<br><small>$detail</small></td><td>${if (members.isEmpty()) "<span class=\"meter-label\">${formatUnits(budget)} credits / day</span>" else meter(used, budget)}</td><td class="num">${if (members.isEmpty()) "—" else formatUnits((budget - used).coerceAtLeast(0))}</td></tr>"""
        } + help("How pool usage is counted", "<p>Used is what this pool's enabled members charged or reserved today; it resets at midnight UTC. A request that borrows a shared overflow account also counts against the overflow pool, which this table does not show.</p>"))
    }

    val accounts = if (!admin || admission == null) "" else section("Accounts close to their limit",
        if (pressured.isEmpty()) empty("No provider window observed yet", "<p>Windows appear after the first requests through each subscription account.</p>")
        else pressured.take(OVERVIEW_ACCOUNTS).joinToString("", "<table><thead><tr><th>Account</th><th>Busiest window</th></tr></thead><tbody>", "</tbody></table>") { quota ->
            val name = escape(names[quota.accountId] ?: quota.accountId)
            val link = if (DashboardFeature.FORECASTS in features) """<a href="/dashboard/forecasts?account=${escape(quota.accountId)}">$name</a>""" else name
            """<tr><td>$link</td><td>${ratioMeter(quota.utilization, "<strong>${percent(quota.utilization)}</strong> · ${escape(quota.window)}")}</td></tr>"""
        } + if (pressured.size > OVERVIEW_ACCOUNTS) "<p><a href=\"/dashboard/capacity#windows\">All ${pressured.size} accounts</a></p>" else "")

    val sessions = if (runs == null) "" else section("Running sessions",
        if (runs.isEmpty()) empty("No agent is running", "<p>Sessions appear here while <code>teamcode run claude</code> or <code>teamcode run codex</code> is active.</p>")
        else runs.take(OVERVIEW_SESSIONS).joinToString("", "<table><thead><tr><th>Launch</th><th class=\"num\">Credits</th><th class=\"num\">Requests</th></tr></thead><tbody>", "</tbody></table>") { run ->
            """<tr><td><a href="/dashboard/sessions/${escape(run.id)}">${escape(run.username)} · ${escape(run.agent)}</a><br><small>${escape(run.workload)}</small></td><td class="num">${formatUnits(run.chargedAndReservedUnits)}</td><td class="num">${run.activeRequests} · ${run.queuedRequests} waiting</td></tr>"""
        } + "<p><a href=\"/dashboard/sessions${if (admin) "?all=true" else ""}\">All sessions</a></p>")

    val left = consumption + pools
    val right = accounts + sessions
    return handoverBanner(pool, onAccountsPage = false) + summary + if (left.isEmpty() || right.isEmpty()) left + right else """<div class="grid-2"><div>$left</div><div>$right</div></div>"""
}

/** Entry point listing every setting, grouped by scope; each link opens the page that already owns the setting. */
internal fun settingsContent(actor: Principal, features: Set<DashboardFeature>): String {
    fun entries(items: List<Triple<String, String, String>>) = items.joinToString("", "<div class=\"link-list\">", "</div>") { (path, label, text) ->
        """<a href="$path">${escape(label)}<span>${escape(text)}</span></a>"""
    }
    fun on(feature: DashboardFeature) = feature in features
    val team = buildList {
        if (on(DashboardFeature.CAPACITY)) add(Triple("/dashboard/capacity", "Quotas and pools", "Pool budgets, weights, personal caps, concurrency and temporary overrides."))
        if (on(DashboardFeature.GROUPS)) add(Triple("/dashboard/groups", "Groups", "Extra caps shared by a set of members."))
        if (on(DashboardFeature.ACCOUNTS)) add(Triple("/dashboard/accounts", "Subscription accounts", "Names, priorities, pools and disabling of shared accounts."))
        if (on(DashboardFeature.MODEL_POLICY)) add(Triple("/dashboard/models", "Model policy", "Allowed and denied models, efforts and thinking budget."))
        if (on(DashboardFeature.ROUTES)) add(Triple("/dashboard/routes", "Model routes", "Provider and model fallback order per requested model."))
        if (on(DashboardFeature.GUIDANCE)) add(Triple("/dashboard/agent-guidance", "Pacing mode", "Predictive pacing or observation only."))
        if (on(DashboardFeature.CLOUD)) add(Triple("/dashboard/cloud", "Cloud sessions", "On or off, runner image, Kubernetes clusters, retention, per-member limits and transfers."))
        if (on(DashboardFeature.VARIANTS)) add(Triple("/dashboard/variants", "Variants", "Environment variables and MCP servers given to members."))
        if (on(DashboardFeature.MCP)) add(Triple("/dashboard/mcp", "MCP tool policies", "Which tools each MCP server exposes."))
        if (on(DashboardFeature.SKILLS)) add(Triple("/dashboard/skills", "Skills", "Pinned versions and assignments."))
        if (on(DashboardFeature.HOOKS)) add(Triple("/dashboard/hooks", "Team hooks", "Pinned versions and assignments."))
        if (on(DashboardFeature.GITHUB)) add(Triple("/dashboard/github", "GitHub App", "The team's GitHub App used by cloud sessions."))
    }
    val own = buildList {
        add(Triple("/dashboard/password", "Password", "Change your password; every device is signed out."))
        if (on(DashboardFeature.DEVICES)) add(Triple("/dashboard/devices", "Devices", "Rename or sign out the computers using your account."))
        if (on(DashboardFeature.SERVICE_KEYS)) add(Triple("/dashboard/service-keys", "Service keys", "Scoped keys for CI that share your quotas."))
        if (on(DashboardFeature.GITHUB)) add(Triple("/dashboard/github", "GitHub link", "The GitHub account your cloud sessions use."))
        if (on(DashboardFeature.CAPACITY) && !actor.member.role.isAdministrator) add(Triple("/dashboard/capacity", "Your quota", "Your daily allowance and concurrency, set by an administrator."))
    }
    val teamSection = if (actor.member.role.isAdministrator && team.isNotEmpty()) "<h2>Team-wide</h2>" + entries(team) else ""
    return teamSection + "<h2>Your account</h2>" + entries(own)
}

private fun card(title: String, body: String) = """<div class="card"><p class="card-title">${escape(title)}</p>$body</div>"""
private fun section(title: String, body: String) = "<section><h2>${escape(title)}</h2>$body</section>"
internal fun poolName(pool: String?) = if (pool.isNullOrEmpty()) "Shared pool" else "Pool $pool"
internal fun MemberCapacity.poolShare(): Long = if (poolTotalWeight == 0L) 0 else poolDailyUnits * policy.weight / poolTotalWeight
