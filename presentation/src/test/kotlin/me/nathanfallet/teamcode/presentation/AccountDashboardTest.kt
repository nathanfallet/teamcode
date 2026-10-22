package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class AccountDashboardTest {
    private fun record(id: String, name: String, state: AccountState, email: String? = null, pool: String? = null, disabled: Boolean = false) =
        AccountRecord(id, AccountIdentity("default", "anthropic", id, "org"), name, 1, disabled, pool, 0, state, email)

    /** In-memory store; [handover] reproduces the transition the SQL store performs for the CLI command. */
    private class MemoryAccounts(vararg initial: AccountRecord) : AccountStore {
        val accounts = initial.associateBy { it.id }.toMutableMap()
        val handovers = mutableListOf<String>()
        var lastOrigin: EnrollmentOrigin? = null
        override suspend fun list(teamId: String) = accounts.values.sortedBy { it.displayName }
        override suspend fun configure(author: AccountMutationActor, teamId: String, id: String, displayName: String, disabled: Boolean, priority: Int, poolId: String?) = error("unused")
        override suspend fun handover(author: AccountMutationActor, teamId: String, id: String, accessOnly: Boolean, requirePending: Boolean): AccountRecord {
            if (requirePending && accounts.getValue(id).state != AccountState.PENDING_HANDOVER) throw TeamFailure("handover_not_pending", 409, "stale")
            handovers += id
            return accounts.getValue(id).copy(state = if (accessOnly) AccountState.ACCESS_ONLY else AccountState.READY).also { accounts[id] = it }
        }
        override suspend fun credentials(teamId: String, id: String) = error("unused")
        var policy = AccountEnrollmentPolicy.ADMINISTRATORS
        override suspend fun enrollmentPolicy(teamId: String) = policy
        override suspend fun setEnrollmentPolicy(author: AccountMutationActor, teamId: String, policy: AccountEnrollmentPolicy) = policy.also { this.policy = it }
        override suspend fun find(identity: AccountIdentity): AccountRecord? = null
        override suspend fun upsert(author: AccountMutationActor, verified: VerifiedProviderAccount, credentials: ProviderCredentials, expectedVersion: Long?, operationId: String, origin: EnrollmentOrigin): EnrollmentResult {
            lastOrigin = origin
            return EnrollmentResult.Created(AccountRecord("new", verified.identity, verified.displayName, 1, false, null, 0, MergeAccountUseCase().initialState(origin), verified.email))
        }
    }
    private val verifier = object : AccountVerifier {
        override suspend fun verify(teamId: String, credentials: ProviderCredentials) = VerifiedProviderAccount(AccountIdentity(teamId, "anthropic", "fresh", "org"), "Fresh", "fresh@example.com")
    }
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.get(path: String, session: String = "admin") = client.get(path) { header(HttpHeaders.Cookie, "teamcode_session=$session") }
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.submit(id: String, body: String, origin: String = "http://localhost", session: String = "admin") = client.post("/dashboard/accounts/$id/handover") {
        header(HttpHeaders.Cookie, "teamcode_session=$session"); header(HttpHeaders.Origin, origin)
        contentType(ContentType.Application.FormUrlEncoded); setBody(body)
    }

    @Test fun aBlockedPoolIsAnnouncedOnTheOverviewAndTheAccountsPage() = testApplication {
        val store = MemoryAccounts(record("a1", "Ada", AccountState.PENDING_HANDOVER, "ada@example.com"), record("a2", "Grace", AccountState.PENDING_HANDOVER))
        application { configureGateway(NoInference(), team = FakeTeam(), publicOrigin = "http://localhost", accounts = AccountManagement(store, verifier)) }
        val overview = get("/dashboard").bodyAsText()
        assertTrue(overview.contains("""<div class="notice danger" role="alert">"""))
        assertTrue(overview.contains("2 accounts wait for a refresh-ownership handover: Ada (ada@example.com), Grace."))
        assertTrue(overview.contains("<code>429 pool_unavailable</code>"))
        assertTrue(overview.contains("""<a href="/dashboard/accounts">Open subscription accounts</a>"""))
        val page = get("/dashboard/accounts").bodyAsText()
        assertTrue(page.contains("The pool is blocked."))
        assertTrue(page.contains("""<a href="#account-a1">Ada (ada@example.com)</a>"""))
        assertTrue(page.contains("""<strong>Ada</strong> <span class="chip warn">pending handover</span><br><small class="muted">ada@example.com</small>"""), "The list puts the email on a muted second line")
        assertTrue(page.contains("""<strong>Grace</strong> <span class="chip warn">pending handover</span><br><small>anthropic"""), "No email: the name alone")
        assertTrue(page.contains("""<strong>Grace</strong> <span class="chip warn">pending handover</span>"""), "Without a known email the name stands alone")
        assertEquals(2, Regex("""action="/dashboard/accounts/a[12]/handover"""").findAll(page).count())
        assertTrue(page.contains("refresh ownership of Ada (ada@example.com)."))
        assertTrue(page.contains("First remove this account from every other tool that uses it"))
        assertTrue(page.contains("""<input type="checkbox" name="confirm" required>"""))
        assertFalse(page.contains("<script"), "The confirmation step needs no script and no window.confirm")
        val member = get("/dashboard", session = "member").bodyAsText()
        assertFalse(member.contains("refresh-ownership handover"), "Members see neither the banner nor account names")
    }

    @Test fun onlyPendingAccountsGetTheButtonAndAServingPoolIsOnlyWarned() = testApplication {
        val store = MemoryAccounts(record("a1", "Ada", AccountState.READY), record("a2", "Grace", AccountState.PENDING_HANDOVER, pool = "night"), record("a3", "Hopper", AccountState.RECONNECT_REQUIRED))
        application { configureGateway(NoInference(), team = FakeTeam(), publicOrigin = "http://localhost", accounts = AccountManagement(store, verifier)) }
        val page = get("/dashboard/accounts").bodyAsText()
        assertTrue(page.contains("""<div class="notice warn" role="alert"><strong>1 account waits"""))
        assertTrue(page.contains("Requests limited to Pool night return <code>429 pool_unavailable</code>"))
        assertTrue(page.contains("""<span class="chip ok">ready</span>""") && page.contains("""<span class="chip danger">reconnect required</span>"""))
        assertTrue(page.contains("""action="/dashboard/accounts/a2/handover""""))
        assertFalse(page.contains("""action="/dashboard/accounts/a1/handover"""") || page.contains("""action="/dashboard/accounts/a3/handover""""))
        store.handover(AccountMutationActor.System, "default", "a2", false)
        assertFalse(get("/dashboard/accounts").bodyAsText().contains("role=\"alert\""), "No banner once every account is handed over")
        assertFalse(get("/dashboard").bodyAsText().contains("role=\"alert\""))
    }

    @Test fun theHandoverButtonRunsTheCliTransitionBehindOriginCsrfConfirmationAndAdministratorChecks() = testApplication {
        val store = MemoryAccounts(record("a1", "Ada", AccountState.PENDING_HANDOVER))
        application { configureGateway(NoInference(), team = FakeTeam(), publicOrigin = "http://localhost", accounts = AccountManagement(store, verifier)) }
        assertEquals(HttpStatusCode.Forbidden, submit("a1", "csrf=session&confirm=on", origin = "https://attacker.test").status)
        assertEquals(HttpStatusCode.Forbidden, submit("a1", "csrf=wrong&confirm=on").status)
        assertEquals(HttpStatusCode.BadRequest, submit("a1", "csrf=session").status, "The checkbox plays the role of --confirm")
        assertEquals(HttpStatusCode.Forbidden, submit("a1", "csrf=session&confirm=on", session = "member").status)
        assertTrue(store.handovers.isEmpty())
        val done = submit("a1", "csrf=session&confirm=on")
        assertEquals(HttpStatusCode.Found, done.status)
        assertEquals("/dashboard/accounts#account-a1", done.headers[HttpHeaders.Location])
        assertEquals(listOf("a1"), store.handovers)
        assertEquals(AccountState.READY, store.accounts.getValue("a1").state)
        assertEquals(HttpStatusCode.Conflict, submit("a1", "csrf=session&confirm=on").status, "A stale page cannot hand over an account twice")
    }

    @Test fun aFreshSignInIsEnrolledActiveWhileAnImportStaysPending() = testApplication {
        val store = MemoryAccounts()
        application { configureGateway(NoInference(), team = FakeTeam(), publicOrigin = "http://localhost", accounts = AccountManagement(store, verifier)) }
        suspend fun enroll(fresh: Boolean?) = client.post("/api/v1/accounts") {
            header(HttpHeaders.Authorization, "Bearer admin"); contentType(ContentType.Application.Json)
            setBody("""{"accessToken":"synthetic-access","refreshToken":"synthetic-refresh","expiresAtMillis":${Long.MAX_VALUE},"operationId":"operation-identifier-01"${if (fresh == null) "" else ""","freshSignIn":$fresh"""}}""")
        }.bodyAsText()
        val signedIn = enroll(true)
        assertEquals(EnrollmentOrigin.SIGN_IN, store.lastOrigin)
        assertTrue(signedIn.contains("\"state\":\"READY\"") && signedIn.contains("\"email\":\"fresh@example.com\""), signedIn)
        assertTrue(enroll(null).contains("\"state\":\"PENDING_HANDOVER\""), "Older clients and imports keep the handover")
        assertEquals(EnrollmentOrigin.IMPORT, store.lastOrigin)
    }

    @Test fun whoCanAddAccountsIsReadableByMembersAndChangedByAdministrators() = testApplication {
        val store = MemoryAccounts()
        application { configureGateway(NoInference(), team = FakeTeam(), publicOrigin = "http://localhost", accounts = AccountManagement(store, verifier)) }
        suspend fun policy(token: String) = client.get("/api/v1/policy/account-enrollment") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText()
        suspend fun save(token: String, value: String) = client.put("/api/v1/policy/account-enrollment") {
            header(HttpHeaders.Authorization, "Bearer $token"); contentType(ContentType.Application.Json); setBody("""{"policy":"$value"}""")
        }
        suspend fun enroll(token: String) = client.post("/api/v1/accounts") {
            header(HttpHeaders.Authorization, "Bearer $token"); contentType(ContentType.Application.Json)
            setBody("""{"accessToken":"synthetic-access","refreshToken":"synthetic-refresh","expiresAtMillis":${Long.MAX_VALUE},"operationId":"operation-identifier-02","freshSignIn":true}""")
        }
        assertTrue(client.get("/api/v1/capabilities") { header(HttpHeaders.Authorization, "Bearer admin") }.bodyAsText().contains("accounts.enrollment-policy.v1"))
        assertEquals("""{"policy":"administrators","allowed":false}""", policy("member"), "The default keeps the previous behavior")
        assertEquals("""{"policy":"administrators","allowed":true}""", policy("admin"))
        val refused = enroll("member")
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertTrue(refused.bodyAsText().contains("account_enrollment_forbidden"), "The CLI keys its message on this code")
        assertNull(store.lastOrigin, "Nothing reaches the store")
        assertEquals(HttpStatusCode.Forbidden, save("member", "members").status)
        assertEquals(HttpStatusCode.BadRequest, save("admin", "everyone").status)
        assertEquals("""{"policy":"members","allowed":true}""", save("admin", "members").bodyAsText())
        assertEquals("""{"policy":"members","allowed":true}""", policy("member"))
        assertEquals(HttpStatusCode.Created, enroll("member").status)
        // The dashboard shows and changes the same setting behind the origin and CSRF checks.
        val page = get("/dashboard/accounts").bodyAsText()
        assertTrue(page.contains("""<h2 id="who-can-add">Who can add accounts</h2>"""))
        assertTrue(page.contains("""value="members" checked"""))
        suspend fun form(body: String, session: String = "admin") = client.post("/dashboard/accounts/enrollment-policy") {
            header(HttpHeaders.Cookie, "teamcode_session=$session"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody(body)
        }
        assertEquals(HttpStatusCode.Forbidden, form("csrf=wrong&policy=administrators").status)
        assertEquals(HttpStatusCode.Forbidden, form("csrf=session&policy=administrators", session = "member").status)
        assertEquals(AccountEnrollmentPolicy.MEMBERS, store.policy)
        assertEquals(HttpStatusCode.Found, form("csrf=session&policy=administrators").status)
        assertEquals(AccountEnrollmentPolicy.ADMINISTRATORS, store.policy)
    }

    private class NoInference : GatewayService {
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) { error("No inference expected") }
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
    }
}
