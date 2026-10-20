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
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class UsageReconciliationRoutesTest {
    @Test fun typedSdkAndEscapedReviewFormsRequireCurrentInteractiveAdministration() = testApplication {
        val calls = mutableListOf<String>()
        val pending = PendingUsage("request-id", "member-id", "<member>", "account-id", "<img src=x onerror=alert(1)>", "anthropic",
            "req_fixture", "", "run-id", 1, 100, "UNCERTAIN", 1000, 2000)
        val record = ReconciledUsage(pending.id, pending.memberId, pending.accountId, "", pending.runId, 1, "req_fixture", "UNCERTAIN", 100, 40,
            UsageEvidence.PROVIDER_RECORD, "<record>", "<verified>", "admin-id", 3000, listOf("original-group"))
        val service = object : UsageReconciliationService {
            override suspend fun pending(actor: Principal, memberId: String?, beforeId: String?): PendingUsagePage {
                actor.requireAdmin(); assertTrue(memberId in listOf(null, "member-id")); assertTrue(beforeId in listOf(null, "cursor-id")); calls += "pending"
                return PendingUsagePage(listOf(pending), "cursor-id")
            }
            override suspend fun history(actor: Principal, memberId: String?, beforeId: String?): ReconciledUsagePage {
                actor.requireAdmin(); calls += "history"; return ReconciledUsagePage(listOf(record), "cursor-id")
            }
            override suspend fun get(actor: Principal, id: String): UsageReview {
                actor.requireAdmin(); assertEquals("request-id", id); calls += "get"; return UsageReview(pending = pending)
            }
            override suspend fun reconcile(actor: Principal, id: String, change: UsageReconciliationChange): ReconciledUsage {
                actor.requireAdmin(); assertEquals("request-id", id)
                assertEquals(UsageReconciliationChange(100, 40, UsageEvidence.PROVIDER_RECORD, "<record>", "<verified>", "req_fixture"), change)
                calls += "reconcile"; return record
            }
        }
        val team = object : TeamService by FakeTeam() {
            override suspend fun authenticate(token: String): Principal? = if (token == "service") FakeTeam().authenticate("admin")!!.copy(serviceScopes = ServiceScope.entries.toSet()) else FakeTeam().authenticate(token)
        }
        val gateway = object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("No inference expected")
        }
        application { configureGateway(gateway, team = team, reconciliation = service, publicOrigin = "http://localhost") }
        TeamCodeClient("http://localhost", { "admin" }) { configure -> createClient { configure(this); install(HttpTimeout) { socketTimeoutMillis = null } } }.use { sdk ->
            assertTrue(sdk.capabilities().features.contains("capacity.usage-reconciliation.v1"))
            assertEquals("req_fixture", sdk.pendingUsage("member-id", "cursor-id").requests.single().providerRequestId)
            val retained = sdk.reconciledUsage("member-id", "cursor-id").reconciliations.single()
            assertEquals(40, retained.actualUnits); assertEquals(listOf("original-group"), retained.groupIds)
            assertEquals(100, sdk.usageReview("request-id").pending?.units)
            assertEquals(40, sdk.reconcileUsage("request-id", UsageReconciliationRequest(100, 40, "PROVIDER_RECORD", "<record>", "<verified>", "req_fixture")).actualUnits)
            assertEquals(listOf("pending", "history", "get", "reconcile"), calls)
            val count = calls.size
            assertFailsWith<IllegalArgumentException> { sdk.pendingUsage("../members") }
            assertFailsWith<IllegalArgumentException> { sdk.usageReview("../audit") }
            assertEquals(count, calls.size)
        }
        for (path in listOf("/dashboard/usage", "/dashboard/usage/request-id", "/dashboard/usage/history")) {
            val page = client.get(path) { header(HttpHeaders.Cookie, "teamcode_session=admin") }
            assertEquals(HttpStatusCode.OK, page.status)
            val html = page.bodyAsText()
            assertFalse(html.contains("<img")); assertFalse(html.contains("<verified>"))
            if (path != "/dashboard/usage/history") assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
            else assertTrue(html.contains("&lt;verified&gt;"))
            assertEquals("no-store", page.headers[HttpHeaders.CacheControl])
            assertTrue(page.headers["Content-Security-Policy"]!!.contains("default-src 'none'"))
        }
        val form = "expectedUnits=100&actualUnits=40&source=PROVIDER_RECORD&reference=<record>&reason=<verified>&expectedProviderRequestId=req_fixture"
        for ((origin, csrf) in listOf("https://attacker.test" to "session", "http://localhost" to "wrong")) {
            val count = calls.size
            assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/usage/request-id") {
                header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, origin)
                contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=$csrf&$form")
            }.status)
            assertEquals(count, calls.size)
        }
        assertEquals(HttpStatusCode.Found, client.post("/dashboard/usage/request-id") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session&$form")
        }.status)
        val count = calls.size
        for (token in listOf("member", "service")) for ((method, path, body) in listOf(
            Triple(HttpMethod.Get, "/api/v1/usage/pending", ""), Triple(HttpMethod.Get, "/api/v1/usage/reconciliations", ""),
            Triple(HttpMethod.Get, "/api/v1/usage/reservations/request-id", ""),
            Triple(HttpMethod.Post, "/api/v1/usage/reservations/request-id/reconcile", """{"expectedUnits":100,"actualUnits":40,"source":"PROVIDER_RECORD","reference":"<record>","reason":"<verified>","expectedProviderRequestId":"req_fixture"}"""))) {
            assertEquals(HttpStatusCode.Forbidden, client.request(path) {
                this.method = method; bearerAuth(token); contentType(ContentType.Application.Json); if (body.isNotEmpty()) setBody(body)
            }.status)
        }
        for (body in listOf("{}", """{"expectedUnits":100,"actualUnits":40,"source":"ESTIMATE","reference":"record","reason":"Guess"}""")) {
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/usage/reservations/request-id/reconcile") {
                bearerAuth("admin"); contentType(ContentType.Application.Json); setBody(body)
            }.status)
        }
        assertEquals(count, calls.size)
    }
}
