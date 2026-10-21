package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

private fun AccountRecord.view() = AccountView(id, identity.provider, displayName, credentialVersion, disabled, priority, state.name, poolId)
internal fun Route.accountRoutes(accounts: AccountManagement, publicOrigin: String) {
    get("/api/v1/accounts") { call.respond(accounts.list(call.principal).map { it.view() }) }
    post("/api/v1/accounts") {
        val input = call.jsonBody<EnrollAccountRequest>()
        val credentials = try { ProviderCredentials(input.accessToken, input.refreshToken, input.expiresAtMillis) }
            catch (_: IllegalArgumentException) { throw TeamFailure("invalid_credentials", 400, "Invalid provider credentials.") }
        when (val result = accounts.enroll(call.principal, credentials, input.operationId, input.provider)) {
            is EnrollmentResult.Created -> call.respond(HttpStatusCode.Created, EnrollAccountResponse(true, result.account.view()))
            is EnrollmentResult.Updated -> call.respond(EnrollAccountResponse(false, result.account.view()))
            EnrollmentResult.Conflict -> throw TeamFailure("enrollment_conflict", 409, "Credentials changed during enrollment. Retry with a new operation.")
        }
    }
    patch("/api/v1/accounts/{id}") {
        val input = call.jsonBody<UpdateAccountRequest>()
        call.respond(accounts.configure(call.principal, call.parameters["id"]!!, input.displayName, input.disabled, input.priority, input.poolId).view())
    }
    post("/api/v1/accounts/{id}/handover") { call.respond(accounts.handover(call.principal, call.parameters["id"]!!).view()) }
    post("/api/v1/accounts/{id}/access-only") { call.respond(accounts.handover(call.principal, call.parameters["id"]!!, accessOnly = true).view()) }
    get("/dashboard/accounts") {
        val actor = call.principal
        val rows = accounts.list(actor).joinToString("") { a -> """<tr><td>${escape(a.displayName)}<br><small>${escape(a.state.name)} · credential version ${a.credentialVersion}</small></td><td><form action="/dashboard/accounts/${escape(a.id)}/update" method="post">${csrf(actor)}<label>Name <input name="name" value="${escape(a.displayName)}" maxlength="120" required></label><label>Priority <input type="number" name="priority" value="${a.priority}" min="-100" max="100"></label><label>Pool (empty = shared) <input name="pool" value="${escape(a.poolId.orEmpty())}" maxlength="36"></label><label><input type="checkbox" name="disabled" ${if (a.disabled) "checked" else ""}> Disabled</label><button>Save account</button></form></td></tr>""" }
        call.respondText(call.page("Subscription accounts", """<p>Add or reconnect accounts on your computer with <code>teamcode accounts add</code>, or import with <code>teamcode accounts import --from teamclaude</code>.</p><p>Imported accounts wait for an explicit refresh-ownership handover. No provider credential is displayed here.</p><table><thead><tr><th>Account</th><th>Settings</th></tr></thead><tbody>$rows</tbody></table>"""), ContentType.Text.Html)
    }
    post("/dashboard/accounts/{id}/update") {
        val input = call.form(publicOrigin.trimEnd('/'))
        accounts.configure(call.principal, call.parameters["id"]!!, input["name"].orEmpty(), input["disabled"] == "on", input["priority"]?.toIntOrNull() ?: 0, input["pool"]?.takeIf { it.isNotBlank() })
        call.respondRedirect("/dashboard/accounts")
    }
}
