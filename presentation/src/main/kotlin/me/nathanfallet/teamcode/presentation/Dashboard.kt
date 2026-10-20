package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.security.MessageDigest
import me.nathanfallet.teamcode.domain.team.*

internal fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
internal fun page(title: String, content: String) = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(title)} · TeamCode</title><style>
:root{color-scheme:dark;font:16px system-ui;background:#10131b;color:#eef1f8}body{max-width:1100px;margin:48px auto;padding:0 24px}header{display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #343b4c;padding-bottom:18px}a{color:#a4baff}h1{font-size:2rem}h2{margin-top:36px}form{margin:16px 0}input,select,button{font:inherit;padding:10px;border-radius:8px;border:1px solid #475066;background:#1b2231;color:inherit;margin:4px}button{background:#375bb4;cursor:pointer}label{display:block;margin:12px 0}table{border-collapse:collapse;width:100%}td,th{text-align:left;padding:12px;border-bottom:1px solid #343b4c}code{overflow-wrap:anywhere;background:#242e43;padding:8px;display:inline-block}.muted{color:#a5afc3}.notice{padding:20px;background:#212d42;border-radius:12px}nav{display:flex;gap:20px;flex-wrap:wrap}small{color:#a5afc3}</style></head><body><header><strong>TeamCode</strong><nav><a href="/dashboard">Overview</a><a href="/dashboard/members">Team</a><a href="/dashboard/groups">Groups</a><a href="/dashboard/sessions">Sessions</a><a href="/dashboard/accounts">Accounts</a><a href="/dashboard/capacity">Capacity</a><a href="/dashboard/usage">Usage review</a><a href="/dashboard/forecasts">Forecasts</a><a href="/dashboard/agent-guidance">Agents</a><a href="/dashboard/models">Models</a><a href="/dashboard/routes">Routes</a><a href="/dashboard/devices">Devices</a><a href="/dashboard/service-keys">Service keys</a><a href="/dashboard/skills">Skills</a><a href="/dashboard/audit">Audit</a><a href="/dashboard/password">Password</a></nav></header><h1>${escape(title)}</h1>$content</body></html>"""
internal fun csrf(principal: Principal) = "<input type='hidden' name='csrf' value='${escape(principal.tokenId)}'>"
internal suspend fun ApplicationCall.form(origin: String, authenticated: Boolean = true): Parameters {
    if (request.header(HttpHeaders.Origin) != origin) throw TeamFailure("origin_rejected", 403, "This form must be submitted from the TeamCode dashboard.")
    if (request.contentType().withoutParameters() != ContentType.Application.FormUrlEncoded) throw TeamFailure("invalid_form", 400, "An encoded form is required.")
    val fields = parseQueryString(receiveChannel().readBounded(16_384).decodeToString())
    if (authenticated && !MessageDigest.isEqual((fields["csrf"] ?: "").toByteArray(), principal.tokenId.toByteArray())) throw TeamFailure("csrf_rejected", 403, "This form has expired. Reload the dashboard.")
    return fields
}
internal fun ApplicationCall.sessionCookie(value: String, origin: String, maxAge: Int = 28_800) {
    response.cookies.append(Cookie("teamcode_session", value, path = "/dashboard", httpOnly = true, secure = origin.startsWith("https://"), maxAge = maxAge, extensions = mapOf("SameSite" to "Strict")))
}
internal fun Route.dashboardRoutes(team: TeamService, publicOrigin: String) {
    val origin = publicOrigin.trimEnd('/')
    get("/") { call.respondRedirect("/dashboard") }
    get("/dashboard/login") {
        call.respondText(page("Sign in", """<p>Use the account created by your administrator.</p><form method="post"><label>Username <input name="username" autocomplete="username" required maxlength="64"></label><label>Password <input name="password" type="password" autocomplete="current-password" required maxlength="1024"></label><button>Sign in</button></form>"""), ContentType.Text.Html)
    }
    post("/dashboard/login") {
        val input = call.form(origin, authenticated = false)
        val session = team.login(input["username"].orEmpty(), input["password"].orEmpty(), "Web dashboard")
        call.sessionCookie(session.token, origin)
        call.respondRedirect(if (session.principal.member.passwordChangeRequired) "/dashboard/password" else "/dashboard")
    }
    get("/dashboard") {
        val member = call.principal.member
        call.respondText(page("Welcome, ${member.username}", """<p class="muted">Your self-hosted team gateway</p><div class="notice">Signed in as ${escape(member.role.name.lowercase())}. Manage your team and connect subscription accounts from the CLI.</div><p>Local setup: <code>teamcode login --server ${escape(origin)}</code></p><form action="/dashboard/logout" method="post">${csrf(call.principal)}<button>Sign out</button></form>"""), ContentType.Text.Html)
    }
    post("/dashboard/logout") {
        call.form(origin); team.logout(call.principal); call.sessionCookie("", origin, 0); call.respondRedirect("/dashboard/login")
    }
    get("/dashboard/password") {
        call.respondText(page("Change password", """<p>Choose at least 14 characters. Changing your password signs out every device.</p><form method="post">${csrf(call.principal)}<label>Current password <input name="current" type="password" autocomplete="current-password" required></label><label>New password <input name="replacement" type="password" autocomplete="new-password" minlength="14" maxlength="1024" required></label><button>Change password</button></form>"""), ContentType.Text.Html)
    }
    post("/dashboard/password") {
        val input = call.form(origin)
        team.changePassword(call.principal, input["current"].orEmpty(), input["replacement"].orEmpty())
        call.sessionCookie("", origin, 0); call.respondRedirect("/dashboard/login")
    }
    get("/dashboard/members") {
        val actor = call.principal
        val members = team.members(actor)
        val rows = members.joinToString("") { member ->
            val protectedOwner = member.role == Role.OWNER && actor.member.role != Role.OWNER
            val access = if (protectedOwner) "OWNER · only an owner can change this account" else """<form action="/dashboard/members/${escape(member.id)}/update" method="post">${csrf(actor)}${memberRoleSelect(actor, member.role)}<label><input type="checkbox" name="disabled" ${if (member.disabled) "checked" else ""}> Disabled</label><button>Save access</button></form>"""
            val recovery = if (protectedOwner) "Owner recovery is restricted" else """<form action="/dashboard/members/${escape(member.id)}/reset" method="post">${csrf(actor)}<button>Reset password</button></form>"""
            """<tr><td>${escape(member.username)}<br><a href="/dashboard/members/${escape(member.id)}/devices">Devices and sessions</a> <a href="/dashboard/members/${escape(member.id)}/service-keys">Service keys</a><br><small>${if (member.passwordChangeRequired) "Password change required" else "Active password"}</small></td><td>$access</td><td>$recovery</td></tr>"""
        }
        call.respondText(page("Team members", """<p>Create accounts directly. There is no public registration or email delivery. Owners control ownership and owner recovery. Keep another enabled owner with a changed password before relinquishing ownership; role changes sign the affected account out.</p><table><thead><tr><th>Member</th><th>Access</th><th>Recovery</th></tr></thead><tbody>$rows</tbody></table><h2>Add a member</h2><form method="post">${csrf(actor)}<label>Username <input name="username" minlength="3" maxlength="64" required pattern="[a-zA-Z0-9][a-zA-Z0-9._-]{2,63}"></label>${memberRoleSelect(actor, Role.MEMBER)}<button>Create member</button></form>"""), ContentType.Text.Html)
    }
    post("/dashboard/members") {
        val input = call.form(origin)
        val created = team.createMember(call.principal, input["username"].orEmpty(), role(input["role"].orEmpty()))
        call.respondText(temporaryPassword(created), ContentType.Text.Html)
    }
    post("/dashboard/members/{id}/update") {
        val input = call.form(origin)
        team.updateMember(call.principal, call.parameters["id"]!!, role(input["role"].orEmpty()), input["disabled"] == "on")
        call.respondRedirect("/dashboard/members")
    }
    post("/dashboard/members/{id}/reset") {
        call.form(origin)
        call.respondText(temporaryPassword(team.resetPassword(call.principal, call.parameters["id"]!!)), ContentType.Text.Html)
    }
}
private fun temporaryPassword(created: NewMember) = page("Temporary password", """<p>Share this password with <strong>${escape(created.member.username)}</strong>. It is displayed only in this response. They must change it before using TeamCode.</p><code>${escape(created.temporaryPassword)}</code><p><a href="/dashboard/members">Return to team</a></p>""")

/** Role controls reflect authority, while the transactional service remains the enforcement boundary. */
private fun memberRoleSelect(actor: Principal, selected: Role): String {
    val roles = listOf(Role.MEMBER, Role.ADMIN) + if (actor.member.role == Role.OWNER) listOf(Role.OWNER) else emptyList()
    return "<label>Role <select name=\"role\">" + roles.joinToString("") { "<option ${if (it == selected) "selected" else ""}>${it.name}</option>" } + "</select></label>"
}
