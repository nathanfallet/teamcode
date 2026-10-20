package me.nathanfallet.teamcode.presentation

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class BudgetOverrideRoutesTest {
    @Test fun sharedOverflowRoundTripsThroughSdkAndCsrfProtectedForms() = testApplication {
        val admission = CapacityOnlyAdmission(BudgetOverrideSummary("fixture", 500, 3_600_000))
        val team = object : TeamService by FakeTeam() {
            override suspend fun authenticate(token: String): Principal? = if (token == "service") FakeTeam().authenticate("admin")!!.copy(serviceScopes = ServiceScope.entries.toSet()) else FakeTeam().authenticate(token)
        }
        val gateway = object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("No inference")
        }
        application { configureGateway(gateway, team = team, admission = admission, publicOrigin = "http://localhost") }
        TeamCodeClient("http://localhost", { "admin" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }.use { sdk ->
            assertTrue(sdk.capabilities().features.contains("capacity.shared-overflow.v1"))
            assertTrue(sdk.saveMemberPolicy("member-id", PolicyRequest(100, 2, "home", allowSharedOverflow = true)).allowSharedOverflow)
            assertEquals(PoolBudgetView("reserve", 1000, true), sdk.savePoolBudget(PoolBudgetView("reserve", 1000, true)))
            assertEquals(listOf(PoolBudgetView("reserve", 1000, true)), sdk.poolBudgets())
            assertFailsWith<IllegalArgumentException> { sdk.saveMemberPolicy("../escape", PolicyRequest(0, 2)) }
        }
        val html = client.get("/dashboard/capacity") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertTrue(html.contains("Allow shared overflow accounts")); assertTrue(html.contains("Shared overflow reserve"))
        assertTrue(html.contains("name=\"overflow\" checked"))
        for (path in listOf("/dashboard/capacity/member-id", "/dashboard/capacity/pool")) {
            assertEquals(HttpStatusCode.Forbidden, client.post(path) {
                header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "https://attacker.test")
                contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session&daily=100&concurrent=2&weight=1&pool=home&overflow=on")
            }.status)
        }
        assertTrue(admission.policy.allowSharedOverflow)
        assertEquals(HttpStatusCode.Found, client.post("/dashboard/capacity/member-id") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session&daily=100&concurrent=2&weight=1&pool=home")
        }.status)
        assertFalse(admission.policy.allowSharedOverflow)
        for (token in listOf("member", "service")) {
            assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/pools/budgets") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"poolId":"reserve","dailyUnits":1000,"sharedOverflow":true}""")
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.patch("/api/v1/members/member-id/policy") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"dailyUnits":100,"maxConcurrent":2,"allowSharedOverflow":true}""")
            }.status)
        }
    }

    @Test fun typedSdkHistoryCapacityAndBrowserFormsPreserveScopeAndEscapeReasons() = testApplication {
        val calls = mutableListOf<String>()
        val record = BudgetOverride("override-id", "member-id", null, 500, "admin-id", "<img src=x onerror=alert(1)>", 1, 3_600_000)
        val overrides = object : BudgetOverrideService {
            override suspend fun history(actor: Principal, memberId: String, beforeId: String?): BudgetOverridePage {
                actor.requireAdmin(); assertEquals("member-id", memberId); assertTrue(beforeId in listOf(null, "cursor-id")); calls += "history"
                return BudgetOverridePage(listOf(record), record.id, "cursor-id")
            }
            override suspend fun grant(actor: Principal, memberId: String, change: BudgetOverrideChange): BudgetOverride {
                actor.requireAdmin(); CheckBudgetOverrideUseCase().expiresAt(change, 1)
                assertEquals("member-id", memberId); assertEquals(BudgetOverrideChange(700, 60, "Incident", "override-id"), change)
                calls += "grant"; return record.copy(dailyUnits = change.dailyUnits, reason = change.reason)
            }
            override suspend fun revoke(actor: Principal, memberId: String, id: String, reason: String): BudgetOverride {
                actor.requireAdmin(); assertEquals("member-id", memberId); assertEquals("override-id", id); assertEquals("Resolved", reason)
                calls += "revoke"; return record.copy(revokedAtMillis = 10, revokedBy = actor.member.id, revocationReason = reason)
            }
        }
        val team = object : TeamService by FakeTeam() {
            override suspend fun authenticate(token: String): Principal? = if (token == "service") FakeTeam().authenticate("admin")!!.copy(serviceScopes = ServiceScope.entries.toSet()) else FakeTeam().authenticate(token)
        }
        val gateway = object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("No inference expected")
        }
        application { configureGateway(gateway, team = team, admission = CapacityOnlyAdmission(record.summary()), budgetOverrides = overrides, publicOrigin = "http://localhost") }
        TeamCodeClient("http://localhost", { "admin" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }.use { sdk ->
            assertTrue(sdk.capabilities().features.contains("capacity.budget-overrides.v1"))
            assertEquals(record.id, sdk.budgetOverrides("member-id", "cursor-id").activeOverrideId)
            assertEquals(700, sdk.grantBudgetOverride("member-id", BudgetOverrideRequest(700, 60, "Incident", "override-id")).dailyUnits)
            assertEquals("Resolved", sdk.revokeBudgetOverride("member-id", "override-id", "Resolved").revocationReason)
            assertEquals(listOf("history", "grant", "revoke"), calls)
            val current = sdk.capacity()
            assertEquals(100, current.baseDailyUnits); assertEquals(500, current.dailyUnits)
            assertEquals(BudgetOverrideSummaryView(record.id, 500, 3_600_000), current.budgetOverride)
            val count = calls.size
            assertFailsWith<IllegalArgumentException> { sdk.budgetOverrides("../audit") }
            assertFailsWith<IllegalArgumentException> { sdk.grantBudgetOverride("member-id", BudgetOverrideRequest(1, 60, "Incident", "../escape")) }
            assertEquals(count, calls.size)
        }
        val page = client.get("/dashboard/capacity/member-id/overrides") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;")); assertFalse(html.contains("<img"))
        assertTrue(html.contains("Revoke override")); assertTrue(html.contains("Replace temporary override")); assertTrue(html.contains("?before=cursor-id"))
        assertEquals("no-store", page.headers[HttpHeaders.CacheControl])
        assertTrue(page.headers["Content-Security-Policy"]!!.contains("default-src 'none'"))
        val capacityPage = client.get("/dashboard/capacity") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        assertTrue(capacityPage.bodyAsText().contains("Temporary quota overrides"))
        for ((origin, csrf) in listOf("https://attacker.test" to "session", "http://localhost" to "wrong")) {
            val count = calls.size
            assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/capacity/member-id/overrides") {
                header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, origin)
                contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=$csrf&daily=700&minutes=60&reason=Incident&replacesId=override-id")
            }.status)
            assertEquals(count, calls.size)
        }
        for ((suffix, body) in listOf("" to "daily=700&minutes=60&reason=Incident&replacesId=override-id", "/override-id/revoke" to "reason=Resolved")) {
            assertEquals(HttpStatusCode.Found, client.post("/dashboard/capacity/member-id/overrides$suffix") {
                header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
                contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session&$body")
            }.status)
        }
        val count = calls.size
        for (token in listOf("member", "service")) for ((method, path, body) in listOf(
            Triple(HttpMethod.Get, "/api/v1/members/member-id/budget-overrides", ""),
            Triple(HttpMethod.Post, "/api/v1/members/member-id/budget-overrides", """{"dailyUnits":700,"durationMinutes":60,"reason":"Incident","replacesId":"override-id"}"""),
            Triple(HttpMethod.Post, "/api/v1/members/member-id/budget-overrides/override-id/revoke", """{"reason":"Resolved"}"""))) {
            assertEquals(HttpStatusCode.Forbidden, client.request(path) {
                this.method = method; bearerAuth(token); contentType(ContentType.Application.Json); if (body.isNotEmpty()) setBody(body)
            }.status)
        }
        assertEquals(count, calls.size)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/members/member-id/budget-overrides") {
            bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"dailyUnits":"many","durationMinutes":60,"reason":"Incident"}""")
        }.status)
        val own = client.get("/api/v1/capacity") { bearerAuth("service") }
        assertEquals(HttpStatusCode.OK, own.status); assertFalse(own.bodyAsText().contains("onerror"))
    }
}

private class CapacityOnlyAdmission(private val temporary: BudgetOverrideSummary) : AdmissionStore {
    var policy = MemberPolicy("member-id", 100)
    private val budgets = mutableMapOf<String, PoolBudget>()
    override suspend fun capacity(actor: Principal, memberId: String): MemberCapacity {
        actor.requireScope(ServiceScope.USAGE_READ); if (memberId != actor.member.id) actor.requireAdmin()
        return MemberCapacity(policy.copy(memberId = memberId), 50, 0, 86_400_000, 500, budgetOverride = temporary, baseDailyAllowanceUnits = 100)
    }
    override suspend fun preferredTarget(actor: Principal, affinity: RequestAffinity): RouteTarget? = error("unused")
    override suspend fun statistics(): AdmissionStatistics = error("unused")
    override suspend fun reserve(actor: Principal, units: Long, model: String, provider: String, excludedAccountIds: Set<String>, affinity: RequestAffinity?): Reservation = error("unused")
    override suspend fun settle(reservation: Reservation, actualUnits: Long?, completed: Boolean) = error("unused")
    override suspend fun configure(actor: Principal, policy: MemberPolicy): MemberPolicy { actor.requireAdmin(); this.policy = policy; return policy }
    override suspend fun poolBudgets(actor: Principal): List<PoolBudget> { actor.requireAdmin(); return budgets.values.toList() }
    override suspend fun configurePool(actor: Principal, budget: PoolBudget): PoolBudget { actor.requireAdmin(); budgets[budget.poolId] = budget; return budget }
    override suspend fun quotas(actor: Principal): List<AccountQuota> = emptyList()
    override suspend fun observe(accountId: String, observations: List<AccountQuota>) = error("unused")
    override suspend fun coolDown(accountId: String, untilMillis: Long, reconnect: Boolean) = error("unused")
}
