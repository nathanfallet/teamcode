package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure

private fun AccountRecord.view() = AccountView(id, identity.provider, displayName, credentialVersion, disabled, priority, state.name, poolId, email)
/** "Name (email)", the label every dashboard page uses for a pool account. */
internal val AccountRecord.label: String get() = accountLabel(displayName, email)
internal fun Route.accountRoutes(accounts: AccountManagement, publicOrigin: String) {
    get("/api/v1/accounts") { call.respond(accounts.list(call.principal).map { it.view() }) }
    post("/api/v1/accounts") {
        val input = call.jsonBody<EnrollAccountRequest>()
        val credentials = try { ProviderCredentials(input.accessToken, input.refreshToken, input.expiresAtMillis) }
            catch (_: IllegalArgumentException) { throw TeamFailure("invalid_credentials", 400, "Invalid provider credentials.") }
        when (val result = accounts.enroll(call.principal, credentials, input.operationId, input.provider, if (input.freshSignIn) EnrollmentOrigin.SIGN_IN else EnrollmentOrigin.IMPORT)) {
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
        call.respondText(call.page("Subscription accounts", accountsContent(actor, accounts.list(actor))), ContentType.Text.Html)
    }
    post("/dashboard/accounts/{id}/update") {
        val input = call.form(publicOrigin.trimEnd('/'))
        accounts.configure(call.principal, call.parameters["id"]!!, input["name"].orEmpty(), input["disabled"] == "on", input["priority"]?.toIntOrNull() ?: 0, input["pool"]?.takeIf { it.isNotBlank() })
        call.respondRedirect("/dashboard/accounts")
    }
    // Same transition as `teamcode accounts handover <id> --confirm`; the checkbox plays the role of --confirm. Only a
    // pending account is accepted, so a stale page cannot turn an access-only or reconnect-required account into READY.
    post("/dashboard/accounts/{id}/handover") {
        val input = call.form(publicOrigin.trimEnd('/'))
        if (input["confirm"] != "on") throw TeamFailure("handover_unconfirmed", 400, "Confirm that no other tool uses this account before the handover.")
        val account = accounts.handover(call.principal, call.parameters["id"]!!, requirePending = true)
        call.respondRedirect("/dashboard/accounts#account-${account.id.encodeURLPathPart()}")
    }
}

/** The subscription accounts page body; the route supplies the administrator-only list. */
internal fun accountsContent(actor: Principal, list: List<AccountRecord>): String {
    val rows = list.joinToString("") { a -> """<tr id="account-${escape(a.id)}"><td><strong>${escape(a.label)}</strong> ${stateChip(a.state)}${if (a.disabled) " <span class=\"chip danger\">disabled</span>" else ""}<br><small>${escape(a.identity.provider)} · credential version ${a.credentialVersion}</small>${if (a.state == AccountState.PENDING_HANDOVER) handoverControl(actor, a) else ""}</td><td><form action="/dashboard/accounts/${escape(a.id)}/update" method="post">${csrf(actor)}<label>Name <input name="name" value="${escape(a.displayName)}" maxlength="120" required></label><label>Priority <input type="number" name="priority" value="${a.priority}" min="-100" max="100"></label><label>Pool (empty = shared) <input name="pool" value="${escape(a.poolId.orEmpty())}" maxlength="36"></label><label><input type="checkbox" name="disabled" ${if (a.disabled) "checked" else ""}> Disabled</label><button>Save account</button></form></td></tr>""" }
    val table = if (list.isEmpty()) empty("No subscription account yet", "<p>Add one from your computer with <code>teamcode accounts add</code>.</p>") else """<table><thead><tr><th>Account</th><th>Settings</th></tr></thead><tbody>$rows</tbody></table>"""
    return handoverBanner(list, onAccountsPage = true) + """<p>Add or reconnect accounts on your computer with <code>teamcode accounts add</code>, or import with <code>teamcode accounts import --from teamclaude</code>.</p><p>An account added with <code>teamcode accounts add</code> serves requests at once. Imported accounts wait for an explicit refresh-ownership handover: use the Handover button below, or <code>teamcode accounts handover &lt;id&gt; --confirm</code>. No provider credential is displayed here.</p>$table"""
}

private fun stateChip(state: AccountState) = when (state) {
    AccountState.PENDING_HANDOVER -> """<span class="chip warn">pending handover</span>"""
    AccountState.READY -> """<span class="chip ok">ready</span>"""
    AccountState.ACCESS_ONLY -> """<span class="chip">access only</span>"""
    AccountState.RECONNECT_REQUIRED -> """<span class="chip danger">reconnect required</span>"""
}

/** An in-page confirmation step: the form stays folded until the administrator opens it and ticks the confirmation. */
private fun handoverControl(actor: Principal, account: AccountRecord) = """<details class="confirm"><summary>Handover</summary><form action="/dashboard/accounts/${escape(account.id)}/handover" method="post">${csrf(actor)}<p><strong>Give TeamCode refresh ownership of ${escape(account.label)}.</strong></p><p>First remove this account from every other tool that uses it (TeamClaude, another TeamCode server, a script). Otherwise both refresh the same token and fight over the session, and the account ends up needing to be reconnected.</p><label><input type="checkbox" name="confirm" required> No other tool uses this account any more</label><button class="danger">Confirm handover</button></form></details>"""

/**
 * Shown while at least one account waits for its refresh-ownership handover. A pending account serves no request, so a
 * pool left without a usable account answers every request with 429 pool_unavailable, which agents only show as an API error.
 */
internal fun handoverBanner(accounts: List<AccountRecord>, onAccountsPage: Boolean): String {
    val pending = accounts.filter { it.state == AccountState.PENDING_HANDOVER }
    if (pending.isEmpty()) return ""
    val serving = accounts.filter { !it.disabled && it.state.canServe }
    val blockedPools = accounts.groupBy { it.poolId }.filter { (_, members) -> members.none { !it.disabled && it.state.canServe } && members.any { it.state == AccountState.PENDING_HANDOVER } }.keys
    val names = pending.joinToString(", ") { a -> if (onAccountsPage) """<a href="#account-${escape(a.id)}">${escape(a.label)}</a>""" else escape(a.label) }
    val count = if (pending.size == 1) "1 account waits" else "${pending.size} accounts wait"
    val (tone, impact) = if (serving.isEmpty()) "danger" to "<strong>The pool is blocked.</strong> No account can serve requests, so every request through TeamCode returns <code>429 pool_unavailable</code> and agents only show “API Error”."
        else "warn" to "Pending accounts serve no request.${if (blockedPools.isEmpty()) "" else " Requests limited to ${blockedPools.sortedBy { it.orEmpty() }.joinToString(", ") { escape(poolName(it)) }} return <code>429 pool_unavailable</code> because no other account there can serve them."}"
    val action = if (onAccountsPage) "<p>Use the Handover button of each account once no other tool uses it.</p>" else """<p><a href="/dashboard/accounts">Open subscription accounts</a> to hand them over.</p>"""
    return """<div class="notice $tone" role="alert"><strong>$count for a refresh-ownership handover: $names.</strong><p>$impact</p>$action</div>"""
}
