package me.nathanfallet.teamcode.presentation

import me.nathanfallet.teamcode.api.formatUnits
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import java.util.Locale
import me.nathanfallet.teamcode.domain.team.Principal

/** Dashboard areas whose routes are installed; the menu never links to a page this server does not serve. */
internal enum class DashboardFeature { GROUPS, RUNS, ACCOUNTS, CAPACITY, USAGE_REVIEW, GUIDANCE, FORECASTS, MODEL_POLICY, ROUTES, DEVICES, SERVICE_KEYS, AUDIT, SKILLS, VARIANTS, MCP, CLOUD, GITHUB, WEBHOOKS,
    /** Not a route set: cloud sessions are enabled in the stored runtime right now. */
    CLOUD_RUNNING }

internal val DASHBOARD_FEATURES = AttributeKey<Set<DashboardFeature>>("teamcode.dashboard.features")
/** Whether cloud sessions are enabled, read on every page so a dashboard change shows at once. */
internal val CLOUD_ENABLED = AttributeKey<() -> Boolean>("teamcode.cloud.enabled")

/**
 * One menu entry. [description] is the one-sentence purpose shown under the page title. Administrator-only entries mirror
 * the authorization the routes and services already enforce; hiding a link never replaces that enforcement.
 */
internal data class DashboardLink(val path: String, val label: String, val description: String, val feature: DashboardFeature? = null, val adminOnly: Boolean = false,
    /** A feature members (not administrators) also need to see the link. */
    val memberFeature: DashboardFeature? = null)
internal data class DashboardSection(val label: String, val links: List<DashboardLink>)

internal val OVERVIEW_LINK = DashboardLink("/dashboard", "Overview", "Today's consumption, remaining pool budgets, accounts close to their limits and running sessions at a glance.")
internal val SETTINGS_LINK = DashboardLink("/dashboard/settings", "All settings", "Every setting of this server in one list, team-wide first, then your own account.")

internal val DASHBOARD_MENU = listOf(
    DashboardSection("Team", listOf(
        DashboardLink("/dashboard/members", "Members", "Create accounts, change roles, disable access and reset passwords.", adminOnly = true),
        DashboardLink("/dashboard/groups", "Groups", "Shared caps for a set of members, on top of their individual allowances.", DashboardFeature.GROUPS),
        DashboardLink("/dashboard/webhooks", "Webhooks", "Send the team's session and run events to your own services, signed and retried.", DashboardFeature.WEBHOOKS, adminOnly = true),
        DashboardLink("/dashboard/audit", "Audit log", "Administrative and sign-in events, newest first.", DashboardFeature.AUDIT, adminOnly = true),
    )),
    DashboardSection("Quotas and capacity", listOf(
        DashboardLink("/dashboard/capacity", "Quotas and pools", "Who may consume how much: pool budgets, weights, personal caps, concurrency, overrides and groups in one place.", DashboardFeature.CAPACITY),
        DashboardLink("/dashboard/usage", "Usage review", "Interrupted requests whose real usage is unknown, and their corrections.", DashboardFeature.USAGE_REVIEW, adminOnly = true),
        DashboardLink("/dashboard/forecasts", "Forecasts", "How fast each subscription window fills up, and when it would reach the pressure threshold.", DashboardFeature.FORECASTS, adminOnly = true),
    )),
    DashboardSection("Accounts and models", listOf(
        DashboardLink("/dashboard/accounts", "Subscription accounts", "The provider subscriptions the team shares: names, priorities, pools and availability.", DashboardFeature.ACCOUNTS, adminOnly = true),
        DashboardLink("/dashboard/routes", "Model routes", "Which provider and model serve each requested model, in order of preference.", DashboardFeature.ROUTES, adminOnly = true),
        DashboardLink("/dashboard/models", "Model policy", "Which models and reasoning efforts members may request.", DashboardFeature.MODEL_POLICY, adminOnly = true),
    )),
    DashboardSection("Agents and MCP", listOf(
        DashboardLink("/dashboard/sessions", "Agent sessions", "Agent launches, their activity and their optional per-launch limits.", DashboardFeature.RUNS),
        DashboardLink("/dashboard/agent-guidance", "Agent guidance", "The pacing advice the server currently gives agents, and the controller mode.", DashboardFeature.GUIDANCE),
        DashboardLink("/dashboard/skills", "Skills", "Published skills, pinned versions and who receives them.", DashboardFeature.SKILLS),
        DashboardLink("/dashboard/variants", "Variants", "Environment variables and MCP servers, and which members receive them.", DashboardFeature.VARIANTS, adminOnly = true),
        DashboardLink("/dashboard/mcp", "MCP gateway", "Tool policies, usage and the call journal of the MCP servers agents reach through TeamCode.", DashboardFeature.MCP, adminOnly = true),
    )),
    DashboardSection("Settings", listOf(
        SETTINGS_LINK,
        DashboardLink("/dashboard/cloud", "Cloud sessions", "Turn cloud sessions on, choose the runner image, connect Kubernetes clusters, and set retention, limits and transfers.", DashboardFeature.CLOUD, adminOnly = true),
        DashboardLink("/dashboard/github", "GitHub", "The team's GitHub App, and the GitHub account your cloud sessions use.", DashboardFeature.GITHUB),
        DashboardLink("/dashboard/devices", "Devices", "The computers signed in to your account, and their sessions.", DashboardFeature.DEVICES),
        DashboardLink("/dashboard/service-keys", "Service keys", "Scoped keys for CI and services, sharing your quotas.", DashboardFeature.SERVICE_KEYS),
        DashboardLink("/dashboard/password", "Password", "Change your password; every device is signed out."),
    )),
)

internal fun DashboardLink.visibleTo(principal: Principal, features: Set<DashboardFeature>) =
    (feature == null || feature in features) && (!adminOnly || principal.member.role.isAdministrator) &&
        (memberFeature == null || memberFeature in features || principal.member.role.isAdministrator)

internal fun ApplicationCall.dashboardFeatures(): Set<DashboardFeature> {
    val installed = application.attributes.getOrNull(DASHBOARD_FEATURES).orEmpty()
    return if (cloudEnabled()) installed + DashboardFeature.CLOUD_RUNNING else installed
}
internal fun ApplicationCall.cloudEnabled(): Boolean = application.attributes.getOrNull(CLOUD_ENABLED)?.invoke() != false

/** The menu entry a path belongs to: the longest matching prefix, so detail pages keep their section highlighted. */
internal fun activeLink(path: String): DashboardLink? = (listOf(OVERVIEW_LINK) + DASHBOARD_MENU.flatMap { it.links })
    .filter { path == it.path || (it != OVERVIEW_LINK && path.startsWith(it.path + "/")) }
    .maxByOrNull { it.path.length }

/** Renders a dashboard page with the menu of the signed-in member; [lede] defaults to the purpose of the page's menu entry. */
internal fun ApplicationCall.page(title: String, content: String, lede: String? = null): String {
    val principal = attributes.getOrNull(PRINCIPAL) ?: return pageShell(title, content)
    val path = request.path()
    val active = activeLink(path)
    val features = dashboardFeatures()
    fun item(link: DashboardLink) = """<a href="${link.path}"${if (link == active) " class=\"active\" aria-current=\"page\"" else ""}>${escape(link.label)}</a>"""
    val sections = DASHBOARD_MENU.mapNotNull { section ->
        val links = section.links.filter { it.visibleTo(principal, features) }
        if (links.isEmpty()) null else """<div class="nav-section"><p class="nav-title">${escape(section.label)}</p>${links.joinToString("") { item(it) }}</div>"""
    }.joinToString("")
    val member = principal.member
    val sidebar = """<aside class="sidebar"><a class="brand" href="/dashboard"><span class="glyph" aria-hidden="true"></span><span class="brand-name">TeamCode</span></a><nav class="nav" aria-label="Dashboard">${item(OVERVIEW_LINK)}$sections</nav><div class="whoami"><span class="who">${escape(member.username)}</span><span class="chip">${escape(member.role.name.lowercase())}</span><form action="/dashboard/logout" method="post">${csrf(principal)}<button class="link">Sign out</button></form></div></aside>"""
    val description = (lede ?: active?.takeIf { it.path == path }?.description)?.takeIf { it.isNotEmpty() }
    return pageShell(title, content, sidebar, description)
}

private const val STYLE = """
:root{color-scheme:dark;--bg:#0b0f14;--panel:#121a22;--panel-2:#0f161d;--line:#1f2a35;--text:#e6edf3;--muted:#8b98a5;--accent:#3ddc97;--accent-dim:#1c6b4c;--on-accent:#06251a;--chip:#16222c;--warn:#f6c453;--danger:#ff6b6b;--radius:14px;--mono:ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,monospace;--sans:-apple-system,BlinkMacSystemFont,"Segoe UI",Inter,Roboto,Helvetica,Arial,sans-serif}
*{box-sizing:border-box}
html,body{margin:0;background:var(--bg);color:var(--text);font-family:var(--sans);font-size:15px;line-height:1.5;-webkit-font-smoothing:antialiased}
a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}
.shell{display:grid;grid-template-columns:248px minmax(0,1fr);min-height:100vh}
.sidebar{position:sticky;top:0;height:100vh;overflow-y:auto;border-right:1px solid var(--line);background:linear-gradient(180deg,#0e151c,var(--bg));padding:20px 14px;display:flex;flex-direction:column;gap:18px}
.brand{display:flex;align-items:center;gap:10px;color:var(--text);padding:0 8px}.brand:hover{text-decoration:none}
.brand-name{font-weight:700;font-size:18px;letter-spacing:-.01em}
.glyph{width:22px;height:22px;border-radius:7px;border:1.5px solid var(--accent-dim);background:radial-gradient(circle,var(--accent) 0 3px,transparent 3.5px),linear-gradient(135deg,var(--chip),var(--panel))}
.nav{display:flex;flex-direction:column;gap:2px}
.nav a{display:block;color:var(--muted);font-size:14px;padding:6px 10px;border-radius:8px}
.nav a:hover{color:var(--text);background:var(--panel);text-decoration:none}
.nav a.active{color:var(--text);background:var(--panel);box-shadow:inset 2px 0 0 var(--accent)}
.nav-section{margin-top:12px}
.nav-title{margin:0 0 4px;padding:0 10px;font-family:var(--mono);font-size:10.5px;text-transform:uppercase;letter-spacing:.12em;color:var(--muted);opacity:.8}
.whoami{margin-top:auto;border-top:1px solid var(--line);padding:14px 8px 0;display:flex;flex-wrap:wrap;align-items:center;gap:8px}
.whoami .who{font-weight:600}.whoami form{margin:0 0 0 auto;padding:0;background:none;border:0}
main{width:100%;max-width:1200px;padding:36px 36px 64px;min-width:0}
main.solo{max-width:440px;margin:10vh auto 0;padding:24px 16px}
.solo-brand{display:flex;justify-content:center;margin-bottom:22px}
h1{margin:0 0 6px;font-size:26px;letter-spacing:-.02em}
.lede{margin:0 0 24px;color:var(--muted);max-width:90ch}
h2{margin:34px 0 12px;font-size:13px;text-transform:uppercase;letter-spacing:.08em;color:var(--muted);font-weight:600}
h3{margin:0 0 8px;font-size:16px}
p{max-width:100ch}
small,.muted{color:var(--muted)}small{font-size:12.5px}
code,pre{font-family:var(--mono);font-size:13px;color:var(--accent);background:var(--panel-2);border:1px solid var(--line);border-radius:8px;padding:2px 7px;overflow-wrap:break-word}
pre{display:block;padding:14px 16px;color:var(--text);white-space:pre-wrap;word-break:break-all}
.notice{padding:16px 18px;background:var(--panel);border:1px solid var(--line);border-left:3px solid var(--accent);border-radius:var(--radius);margin:16px 0}
.notice p{margin:6px 0 0}
.notice.warn{border-left-color:var(--warn)}.notice.danger{border-left-color:var(--danger);background:linear-gradient(90deg,rgba(255,107,107,.08),var(--panel) 40%)}
details.confirm{margin-top:10px}
details.confirm>summary{display:inline-block;cursor:pointer;list-style:none;font-weight:600;font-size:13px;padding:7px 12px;border-radius:10px;background:var(--warn);color:#2a2000}
details.confirm>summary::-webkit-details-marker{display:none}
details.confirm[open]>summary{margin-bottom:10px;filter:brightness(.9)}
details.confirm form{max-width:520px;margin:0;padding:12px 14px;background:var(--panel-2);border:1px solid var(--line);border-left:3px solid var(--warn);border-radius:12px}
details.confirm p{margin:0 0 8px;font-size:13px}
details.confirm label{display:flex;flex-direction:row;max-width:none}
button.danger,td button.danger{background:var(--danger);color:#fff;border-color:transparent}
table{width:100%;border-collapse:separate;border-spacing:0;background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);overflow:hidden;margin:0 0 18px}
thead th{text-align:left;font-size:11px;text-transform:uppercase;letter-spacing:.1em;color:var(--muted);font-weight:600;padding:12px 16px;background:var(--panel-2);border-bottom:1px solid var(--line)}
tbody td{padding:14px 16px;border-bottom:1px solid var(--line);vertical-align:top}
tbody tr:last-child td{border-bottom:none}
tbody tr:hover{background:rgba(255,255,255,.015)}
td.num,th.num{text-align:right;font-family:var(--mono);font-variant-numeric:tabular-nums;white-space:nowrap}
.table-wrap{overflow-x:auto;margin:0 0 18px}.table-wrap table{margin:0}
form{margin:0 0 18px;padding:18px;background:var(--panel);border:1px solid var(--line);border-radius:var(--radius)}
label{display:block;margin:0 0 14px;font-size:13px;font-weight:600;color:var(--text)}
label>input:not([type=checkbox]):not([type=radio]),label>select,label>textarea{display:block;margin-top:6px}
label:has(>input[type=checkbox]){display:flex;align-items:center;gap:8px;font-weight:500}
input,select,textarea{font:inherit;font-size:14px;background:var(--panel-2);border:1px solid var(--line);color:var(--text);border-radius:10px;padding:9px 12px;width:100%;max-width:440px}
input[type=number]{max-width:200px;font-family:var(--mono)}
input[type=checkbox],input[type=radio]{width:16px;height:16px;accent-color:var(--accent);margin:0}
input:focus,select:focus,textarea:focus{outline:none;border-color:var(--accent-dim);box-shadow:0 0 0 3px rgba(61,220,151,.12)}
input[readonly]{color:var(--muted)}
.drop{border:1.5px dashed var(--line);border-radius:12px;padding:18px;margin:0 0 14px;text-align:center;color:var(--muted)}
.drop.over{border-color:var(--accent);background:var(--panel-2)}
.drop .pick{display:inline;color:var(--accent);cursor:pointer;text-decoration:underline;font-weight:500}
.choice{display:flex;align-items:flex-start;gap:10px}.choice>span{font-weight:400}
[hidden]{display:none!important}
.hint{display:block;margin-top:5px;font-size:12.5px;font-weight:400;color:var(--muted)}
.hint strong{color:var(--text);font-weight:600}
fieldset{border:1px solid var(--line);border-radius:12px;padding:14px 16px;margin:0 0 14px}legend{padding:0 6px;color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.08em}
button{font:inherit;font-size:14px;font-weight:600;background:var(--accent);color:var(--on-accent);border:1px solid transparent;border-radius:10px;padding:9px 16px;cursor:pointer;line-height:1.25}
button:hover{filter:brightness(1.08)}
button+button{margin-left:8px}
button.link{background:none;border:0;padding:0;color:var(--muted);font-weight:500;font-size:13px}button.link:hover{color:var(--text);filter:none}
td form{background:none;border:0;padding:0;margin:0 0 10px}
td form:last-child{margin-bottom:0}
td label{display:inline-flex;flex-direction:column;margin:0 12px 10px 0;vertical-align:top;font-size:12px}
td label:has(>input[type=checkbox]){flex-direction:row;display:flex}
td label{max-width:280px}td input:not([type=checkbox]),td select,td textarea{max-width:240px}
form.fields{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:2px 18px;align-items:start}
form.fields label{display:flex;flex-direction:column;margin:0 0 10px;max-width:none}
form.fields label:has(>input[type=checkbox]){flex-direction:row;grid-column:1/-1}
form.fields input:not([type=checkbox]){max-width:none}
form.fields button{grid-column:1/-1;justify-self:start}
th.col-member{width:190px}th.col-today{width:250px}
.card .meter-label{white-space:normal}
td.num{font-size:13px}
.pool-forms{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:16px;margin:0 0 18px}.pool-forms form{margin:0}
td button{background:var(--panel-2);color:var(--text);border-color:var(--line);font-size:13px;padding:7px 12px}
td button:hover{border-color:var(--muted);filter:none}
details.help{background:var(--panel-2);border:1px solid var(--line);border-radius:12px;margin:0 0 20px;padding:0 16px;color:var(--muted);font-size:14px}
details.help>summary{cursor:pointer;padding:11px 0;color:var(--text);font-weight:600;font-size:13.5px;list-style:none}
details.help>summary::-webkit-details-marker{display:none}
details.help>summary::before{content:"?";display:inline-flex;align-items:center;justify-content:center;width:18px;height:18px;margin-right:8px;border-radius:50%;background:var(--chip);border:1px solid var(--line);color:var(--accent);font-family:var(--mono);font-size:11px}
details.help[open]>summary{border-bottom:1px solid var(--line);margin-bottom:10px}
details.help p{margin:0 0 10px}
.chip{display:inline-block;font-family:var(--mono);font-size:11px;letter-spacing:.04em;color:#bfe9d5;background:var(--chip);border:1px solid var(--line);padding:2px 8px;border-radius:999px;white-space:nowrap}
.chip.warn{color:#2a2000;background:var(--warn);border-color:transparent}
.chip.danger{color:#fff;background:var(--danger);border-color:transparent}
.chip.ok{color:var(--on-accent);background:var(--accent);border-color:transparent}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:16px;margin:0 0 22px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);padding:16px 18px;min-width:0}
.card-title{font-size:12px;text-transform:uppercase;letter-spacing:.08em;color:var(--muted);font-weight:600;margin:0 0 10px}
.big{font-size:28px;font-weight:700;letter-spacing:-.02em;font-variant-numeric:tabular-nums}
.grid-2{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}
.grid-2>section h2{margin-top:8px}
.meter{position:relative;height:8px;border-radius:999px;background:var(--panel-2);border:1px solid var(--line);overflow:hidden;margin:6px 0 4px;min-width:120px}
.meter>span{position:absolute;inset:0 auto 0 0;background:linear-gradient(90deg,var(--accent-dim),var(--accent));border-radius:999px}
.meter.warn>span{background:var(--warn)}.meter.danger>span{background:var(--danger)}
.meter-label{font-family:var(--mono);font-size:12px;color:var(--muted);font-variant-numeric:tabular-nums;white-space:nowrap}
.window-line+.window-line{margin-top:10px}.window-head{display:flex;flex-wrap:wrap;gap:2px 8px;align-items:baseline;margin-bottom:4px}
.meter-label strong{color:var(--text);font-weight:600}
.empty{padding:36px 24px;text-align:center;background:var(--panel);border:1px dashed var(--line);border-radius:var(--radius);color:var(--muted);margin:0 0 18px}
.empty strong{display:block;color:var(--text);margin-bottom:4px}
.link-list{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:14px;margin:0 0 22px}
.link-list a{display:block;background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);padding:14px 16px;color:var(--text)}
.link-list a:hover{border-color:var(--accent-dim);text-decoration:none}
.link-list a span{display:block;color:var(--muted);font-size:13px;margin-top:4px}
.forecast-card svg text{font-family:var(--mono);font-size:11px}
@media (max-width:900px){
.shell{grid-template-columns:minmax(0,1fr)}
.sidebar{position:static;height:auto;border-right:0;border-bottom:1px solid var(--line);padding:14px 16px}
.nav{flex-direction:row;flex-wrap:wrap;gap:4px 6px}.nav-section{margin-top:6px;display:flex;flex-wrap:wrap;align-items:center;gap:2px 4px;width:100%}.nav-title{width:100%;padding:0 4px}
.nav a{padding:5px 9px}
.whoami{margin-top:0;padding-top:10px}
main{padding:22px 16px 48px}
.grid-2{grid-template-columns:minmax(0,1fr)}
.cards{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.big{font-size:22px}
table{display:block;overflow-x:auto}
table.stack{overflow:hidden}table.stack thead{display:none}table.stack tbody,table.stack tr,table.stack td{display:block;width:100%}table.stack td{border-bottom:0;padding:10px 16px}table.stack tr{border-bottom:1px solid var(--line);padding:6px 0}table.stack tr:last-child{border-bottom:0}
}
@media (prefers-reduced-motion:reduce){*{transition:none!important}}
"""

/** A page without the member menu, for standalone previews. Routes use [ApplicationCall.page]. */
internal fun page(title: String, content: String): String = pageShell(title, content)

/** Page shell. Without [sidebar] (sign-in, errors before authentication) the content is centred on its own. */
private fun pageShell(title: String, content: String, sidebar: String = "", lede: String? = null): String {
    val head = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(title)} · TeamCode</title><style>$STYLE</style></head><body>"""
    val heading = "<h1>${escape(title)}</h1>" + (lede?.let { """<p class="lede">${escape(it)}</p>""" } ?: "")
    return if (sidebar.isEmpty()) "$head<main class=\"solo\"><div class=\"solo-brand\"><span class=\"brand\"><span class=\"glyph\" aria-hidden=\"true\"></span><span class=\"brand-name\">TeamCode</span></span></div>$heading$content</main></body></html>"
    else "$head<div class=\"shell\">$sidebar<main>$heading$content</main></div></body></html>"
}

/** A collapsed explanation: the rules stay one click away instead of pushing the controls down. */
internal fun help(summary: String, body: String) = """<details class="help"><summary>${escape(summary)}</summary>$body</details>"""

/** An explicit empty state instead of a table with no rows. */
internal fun empty(title: String, text: String = "") = """<div class="empty"><strong>${escape(title)}</strong>$text</div>"""

/** A field explanation rendered inside its label; [html] may carry an escaped effective value. */
internal fun hint(html: String) = """<span class="hint">$html</span>"""

/**
 * A pool account where a table or list has room: the name on the first line ([afterName] follows it, e.g. state chips),
 * the email on a second line in the theme's secondary text color. No email, or a name that already is the email: the
 * name alone. Inline text, banners, confirmations and selectors keep the one-line "Name (email)" of `accountLabel`.
 */
internal fun accountCell(name: String, email: String?, strong: Boolean = false, afterName: String = ""): String {
    val title = if (strong) "<strong>${escape(name)}</strong>" else escape(name)
    val second = email?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
    return title + afterName + (second?.let { "<br><small class=\"muted\">${escape(it)}</small>" } ?: "")
}

/** Consumed / allocated bar; it turns amber from 75 % and red from 90 %. A zero allocation renders as full when anything is used. */
internal fun meter(used: Long, total: Long, label: String = "<strong>${formatUnits(used)}</strong> / ${formatUnits(total)}"): String {
    val ratio = when { total > 0 -> used.toDouble() / total; used > 0 -> 1.0; else -> 0.0 }
    return ratioMeter(ratio, label)
}

internal fun ratioMeter(ratio: Double, label: String): String {
    val bounded = ratio.coerceIn(0.0, 1.0)
    val tone = when { ratio >= .9 -> " danger"; ratio >= .75 -> " warn"; else -> "" }
    return """<div class="meter$tone" role="presentation"><span style="width:${String.format(Locale.ROOT, "%.1f", bounded * 100)}%"></span></div><span class="meter-label">$label</span>"""
}

/** Time left until [resetsAtMillis] as `2d 4h 12m`: leading zero units are dropped, minutes always show, a past instant reads `0m`. */
internal fun remainingLabel(resetsAtMillis: Long, nowMillis: Long): String {
    val minutes = ((resetsAtMillis - nowMillis).coerceAtLeast(0)) / 60_000
    val days = minutes / 1_440
    val hours = minutes % 1_440 / 60
    return buildList {
        if (days > 0) add("${days}d")
        if (days > 0 || hours > 0) add("${hours}h")
        add("${minutes % 60}m")
    }.joinToString(" ")
}

internal fun percent(ratio: Double) = String.format(Locale.ROOT, "%.0f%%", ratio * 100)
