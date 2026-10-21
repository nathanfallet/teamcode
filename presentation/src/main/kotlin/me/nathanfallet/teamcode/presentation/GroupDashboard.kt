package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.domain.team.*

private const val GROUP_HELP = "Group caps apply in addition to individual weighted allowances and pool limits. Every active group applies. Zero means no additional cap. Daily credits reset at midnight UTC. Removing a member or archiving a group never refunds earlier reservations. Pausing stops new admissions; active requests finish normally."

internal fun Route.groupDashboardRoutes(groups: GroupService, team: TeamService, origin: String) {
    get("/dashboard/groups") {
        val actor = call.principal
        val rows = groups.list(actor).joinToString("") { group ->
            """<tr><td><a href="/dashboard/groups/${escape(group.group.id)}">${escape(group.group.name)}</a>${if (group.group.archived) " · archived" else ""}</td><td>${if (group.group.dailyUnits == 0L) "<span class='meter-label'><strong>${units(group.usage.chargedAndReservedUnits)}</strong> · no extra cap</span>" else meter(group.usage.chargedAndReservedUnits, group.group.dailyUnits)}</td><td>${group.usage.activeRequests} / ${cap(group.group.maxConcurrent.toLong())} active requests</td><td>${if (group.canManage) "Manager access" else "Member access"}</td></tr>"""
        }
        val create = if (actor.member.role.isAdministrator) "<h2>Create a group</h2>" + groupPolicyForm(actor, "/dashboard/groups", null) else ""
        call.respondText(call.page("Groups", """<details class='help'><summary>How group caps work</summary><p>$GROUP_HELP</p></details><table><thead><tr><th>Group</th><th>Budget</th><th>Concurrency</th><th>Access</th></tr></thead><tbody>$rows</tbody></table>$create"""), ContentType.Text.Html)
    }
    post("/dashboard/groups") {
        val input = call.form(origin.trimEnd('/'))
        val group = groups.create(call.principal, input.groupPolicy())
        call.respondRedirect("/dashboard/groups/${group.summary.group.id}")
    }
    get("/dashboard/groups/{id}") {
        val actor = call.principal
        val detail = groups.get(actor, call.parameters["id"].orEmpty())
        val summary = detail.summary
        val group = summary.group
        val base = "/dashboard/groups/${escape(group.id)}"
        val rows = detail.members.joinToString("") { member ->
            val memberBase = "$base/members/${escape(member.memberId)}"
            val edit = if (actor.member.role.isAdministrator && !group.archived) """<form method="post" action="$memberBase">${csrf(actor)}${groupRoleSelect(member.role)}<label><input type="checkbox" name="paused" ${if (member.paused) "checked" else ""}> Admission paused</label><button>Save assignment</button></form>""" else escape(member.role.name)
            val pause = if (summary.canManage) """<form method="post" action="$memberBase/pause">${csrf(actor)}<input type="hidden" name="paused" value="${!member.paused}"><button>${if (member.paused) "Resume" else "Pause"} admission</button></form>""" else ""
            val remove = if (actor.member.role.isAdministrator) """<form method="post" action="$memberBase/remove">${csrf(actor)}<button>Remove assignment</button></form>""" else ""
            """<tr><td>${escape(member.username)}${if (member.disabled) " · globally disabled" else ""}<br><small>${if (member.paused) "Admission paused" else "Admission enabled"}</small></td><td>$edit</td><td>$pause$remove</td></tr>"""
        }
        val policy = if (actor.member.role.isAdministrator) "<h2>Group policy</h2>" + groupPolicyForm(actor, base, group) else ""
        val assign = if (actor.member.role.isAdministrator && !group.archived) {
            val options = team.members(actor).joinToString("") { "<option value='${escape(it.id)}'>${escape(it.username)}</option>" }
            """<h2>Assign a member</h2><form method="post" action="$base/members">${csrf(actor)}<label>Member <select name="memberId">$options</select></label>${groupRoleSelect(GroupRole.MEMBER)}<button>Assign member</button></form>"""
        } else ""
        call.respondText(call.page(group.name, """<details class='help'><summary>How group caps work</summary><p>$GROUP_HELP</p></details><div class="notice">${if (group.archived) "Archived · caps are inactive. " else ""}${units(summary.usage.chargedAndReservedUnits)} / ${cap(group.dailyUnits)} daily credits · ${summary.usage.activeRequests} / ${cap(group.maxConcurrent.toLong())} active requests</div><p>Managers can view their group's roster and pause or resume admission. Only administrators can change budgets, assignments or roles. Members see their own assignment.</p><table><thead><tr><th>Member</th><th>Group role</th><th>Admission</th></tr></thead><tbody>$rows</tbody></table>$assign$policy"""), ContentType.Text.Html)
    }
    post("/dashboard/groups/{id}") {
        val input = call.form(origin.trimEnd('/')); val id = call.parameters["id"].orEmpty()
        groups.update(call.principal, id, input.groupPolicy()); call.respondRedirect("/dashboard/groups/$id")
    }
    post("/dashboard/groups/{id}/members") {
        val input = call.form(origin.trimEnd('/')); val id = call.parameters["id"].orEmpty()
        groups.assign(call.principal, id, input["memberId"].orEmpty(), groupRole(input["role"].orEmpty()))
        call.respondRedirect("/dashboard/groups/$id")
    }
    post("/dashboard/groups/{id}/members/{memberId}") {
        val input = call.form(origin.trimEnd('/')); val id = call.parameters["id"].orEmpty()
        groups.assign(call.principal, id, call.parameters["memberId"].orEmpty(), groupRole(input["role"].orEmpty()), input["paused"] == "on")
        call.respondRedirect("/dashboard/groups/$id")
    }
    post("/dashboard/groups/{id}/members/{memberId}/pause") {
        val input = call.form(origin.trimEnd('/')); val id = call.parameters["id"].orEmpty()
        val paused = input["paused"]?.toBooleanStrictOrNull() ?: throw TeamFailure("invalid_group", 400, "Provide the admission pause state.")
        groups.pause(call.principal, id, call.parameters["memberId"].orEmpty(), paused)
        call.respondRedirect("/dashboard/groups/$id")
    }
    post("/dashboard/groups/{id}/members/{memberId}/remove") {
        call.form(origin.trimEnd('/')); val id = call.parameters["id"].orEmpty()
        groups.remove(call.principal, id, call.parameters["memberId"].orEmpty()); call.respondRedirect("/dashboard/groups/$id")
    }
}

private fun cap(value: Long) = if (value == 0L) "no extra cap" else units(value)
private fun groupRoleSelect(role: GroupRole) = """<label>Group role <select name="role"><option ${if (role == GroupRole.MEMBER) "selected" else ""}>MEMBER</option><option ${if (role == GroupRole.MANAGER) "selected" else ""}>MANAGER</option></select></label>"""
private fun groupPolicyForm(actor: Principal, action: String, group: TeamGroup?) = """<form method="post" action="$action">${csrf(actor)}<label>Name <input name="name" value="${escape(group?.name.orEmpty())}" maxlength="100" required></label><label>Daily credit cap (0 = no extra cap) <input type="number" name="daily" value="${group?.dailyUnits ?: 0}" min="0" max="100000000000" required></label><label>Concurrent request cap (0 = no extra cap) <input type="number" name="concurrent" value="${group?.maxConcurrent ?: 0}" min="0" max="128" required></label><label><input type="checkbox" name="archived" ${if (group?.archived == true) "checked" else ""}> Archived</label><button>Save group</button></form>"""
private fun Parameters.groupPolicy(): GroupPolicy = GroupPolicy(get("name").orEmpty(),
    get("daily")?.toLongOrNull() ?: throw TeamFailure("invalid_group", 400, "Daily credits must be an integer."),
    get("concurrent")?.toIntOrNull() ?: throw TeamFailure("invalid_group", 400, "Concurrency must be an integer."), get("archived") == "on")
