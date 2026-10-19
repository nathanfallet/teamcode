package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.team.*

private fun ServiceKey.view() = ServiceKeyView(id, memberId, name, scopes.map { it.wire }.sorted(), createdAt, expiresAt)
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
                """<tr><td>${escape(key.name)}<br><small>${escape(key.scopes.map { it.wire }.sorted().joinToString(", "))}</small></td><td>${Instant.ofEpochMilli(key.expiresAt)}</td><td><form method="post" action="$path/${escape(key.id)}/revoke">${csrf(actor)}<button>Revoke</button></form></td></tr>"""
            }
            val choices = ServiceScope.entries.joinToString("") { scope -> """<label><input type="checkbox" name="scope" value="${scope.wire}" ${if (scope == ServiceScope.INFERENCE) "checked" else ""}> ${scope.wire}</label>""" }
            call.respondText(page("Service and CI keys", """<p>Keys share their owner's quotas and current model/pool policy. They cannot administer the server or manage credentials, even when their owner is an administrator. Only selected scopes are granted. Inference includes cancellation, usage:read exposes the owner's capacity, guidance permits pacing directives, skills:read downloads the owner's delivered skills and skills:publish (administrator keys only) publishes skill versions. Keys expire after at most 90 days. Revocation is shared across replicas and stops active work through the existing session monitor.</p><table><thead><tr><th>Key</th><th>Expires (UTC)</th><th>Manage</th></tr></thead><tbody>$rows</tbody></table><h2>Create a key</h2><form method="post" action="$path">${csrf(actor)}<label>Name <input name="name" maxlength="120" required></label>$choices<label>Valid days <input name="days" type="number" min="1" max="90" value="30" required></label><button>Create key</button></form>"""), ContentType.Text.Html)
        }
        post(base) {
            val input = call.form(origin.trimEnd('/'))
            val member = call.parameters["memberId"] ?: call.principal.member.id
            val created = keys.create(call.principal, member, input["name"].orEmpty(), scopes(input.getAll("scope").orEmpty()), input["days"]?.toIntOrNull() ?: 0)
            call.respondText(page("Save your service key", """<p>This credential is shown only now. Save it in your CI secret store. It cannot be recovered later.</p><pre>${escape(created.accessToken)}</pre><a href="${keysPath(call.principal, member)}">Back to key metadata</a>"""), ContentType.Text.Html)
        }
        post("$base/{id}/revoke") {
            call.form(origin.trimEnd('/'))
            val member = keys.revoke(call.principal, call.parameters["id"]!!)
            call.respondRedirect(keysPath(call.principal, member))
        }
    }
}
