package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.team.*

private fun ServiceKey.view() = ServiceKeyView(id, memberId, name, scopes.map { it.wire }.sorted(), createdAt, expiresAt, neverExpires, createdBy, createdByName)
private fun CreatedServiceKey.view() = CreatedServiceKeyResponse(metadata.view(), accessToken)
private fun scopes(values: List<String>): Set<ServiceScope> {
    if (values.size !in 1..ServiceScope.entries.size) throw TeamFailure("invalid_service_key", 400, "Choose supported service-key scopes.")
    return values.map { value -> ServiceScope.entries.find { it.wire == value }
        ?: throw TeamFailure("invalid_service_key", 400, "Choose supported service-key scopes.") }.toSet()
}
private fun keysPath(actor: Principal, member: String) = if (actor.member.id == member) "/dashboard/service-keys" else "/dashboard/members/$member/service-keys"

internal fun Route.serviceKeyRoutes(keys: ServiceKeyService, origin: String) {
    for (base in listOf("/api/v1", "/api/v1/members/{memberId}")) {
        get("$base/service-keys") { call.respond(keys.list(call.principal, call.parameters["memberId"] ?: call.principal.member.id).map { it.view() }) }
        post("$base/service-keys") {
            val input = call.jsonBody<CreateServiceKeyRequest>()
            call.respond(HttpStatusCode.Created, keys.create(call.principal, call.parameters["memberId"] ?: call.principal.member.id, input.name, scopes(input.scopes), input.validDays).view())
        }
    }
    delete("/api/v1/service-keys/{id}") { keys.revoke(call.principal, call.parameters["id"]!!); call.respond(HttpStatusCode.NoContent) }
    for (base in listOf("/dashboard/service-keys", "/dashboard/members/{memberId}/service-keys")) {
        get(base) {
            val actor = call.principal
            val member = call.parameters["memberId"] ?: actor.member.id
            val path = keysPath(actor, member)
            val rows = keys.list(actor, member).joinToString("") { key ->
                """<tr><td>${escape(key.name)}<br><small>${escape(key.scopes.map { it.wire }.sorted().joinToString(", "))}${key.createdByName?.let { by -> "; created by ${escape(by)}" }.orEmpty()}</small></td><td>${if (key.neverExpires) "never" else Instant.ofEpochMilli(key.expiresAt).toString()}</td><td><form method="post" action="$path/${escape(key.id)}/revoke">${csrf(actor)}<button>Revoke</button></form></td></tr>"""
            }
            // skills:publish, skills:pin, skills:assign, hooks:publish, admin:cloud and team:read are only valid on a key an administrator creates for themselves; hide them elsewhere (the server refuses them too).
            val offered = ServiceScope.entries.filter { it != ServiceScope.WAITS_SESSION && it != ServiceScope.WORKFLOW_SESSION }.filter { it !in ServiceKeyService.ADMINISTRATOR_SCOPES || (actor.member.role.isAdministrator && member == actor.member.id) }
            val neverOption = if (actor.member.role.isAdministrator) """<label><input type="checkbox" name="never" value="1"> Never expires (administrators only; revoke it to end it)</label>""" else ""
            val choices = offered.joinToString("") { scope -> """<label><input type="checkbox" name="scope" value="${scope.wire}" ${if (scope == ServiceScope.INFERENCE) "checked" else ""}> ${scope.wire}</label>""" }
            call.respondText(call.page("Service and CI keys", """<details class='help'><summary>How this works</summary><p>Keys share their owner's quotas and current model/pool policy. They cannot administer the server or manage credentials, even when their owner is an administrator. Only selected scopes are granted. Inference includes cancellation, usage:read exposes the owner's capacity, guidance permits pacing directives, skills:read downloads the owner's delivered skills, skills:publish (administrator keys only) publishes skill versions, skills:pin (administrator keys only, with skills:publish) also pins them, which chooses the version delivered to members, skills:assign (administrator keys only, with skills:publish) also lets a publication deliver the skill to all members, hooks:read downloads the owner's delivered team hooks, hooks:publish (administrator keys only) publishes hook versions without delivering them (an administrator pins each one here), mcp permits the MCP gateway with the owner's MCP assignments, cloud:read and cloud:write read and manage the owner's cloud sessions, and admin:cloud (administrator keys only) acts on every team cloud session and on behalf of a member, and team:read (administrator keys only) reads the whole team's launches, capacity, usage, accounts, quota windows and cloud sessions without any write, artifacts:read lists and reads the artifacts the owner can see, and artifacts:write also publishes versions and invites members, never making an artifact public or deleting one. Keys expire after at most 90 days, except a key an administrator creates with "never expires" (for themselves or another member; the table shows who created each key). Revocation is shared across replicas and stops active work through the existing session monitor.</p></details><table><thead><tr><th>Key</th><th>Expires (UTC)</th><th>Manage</th></tr></thead><tbody>$rows</tbody></table><h2>Create a key</h2><form method="post" action="$path">${csrf(actor)}<label>Name <input name="name" maxlength="120" required></label>$choices<label>Valid days <input name="days" type="number" min="1" max="90" value="30"></label>$neverOption<button>Create key</button></form>"""), ContentType.Text.Html)
        }
        post(base) {
            val input = call.form(origin.trimEnd('/'))
            val member = call.parameters["memberId"] ?: call.principal.member.id
            val created = keys.create(call.principal, member, input["name"].orEmpty(), scopes(input.getAll("scope").orEmpty()), if (input["never"] != null) null else if (input["days"].isNullOrBlank()) 30 else input["days"]?.toIntOrNull() ?: 0)
            call.respondText(call.page("Save your service key", """<p>This credential is shown only now. Save it in your CI secret store. It cannot be recovered later.</p><pre>${escape(created.accessToken)}</pre><a href="${keysPath(call.principal, member)}">Back to key metadata</a>"""), ContentType.Text.Html)
        }
        post("$base/{id}/revoke") {
            call.form(origin.trimEnd('/'))
            val member = keys.revoke(call.principal, call.parameters["id"]!!)
            call.respondRedirect(keysPath(call.principal, member))
        }
    }
}
