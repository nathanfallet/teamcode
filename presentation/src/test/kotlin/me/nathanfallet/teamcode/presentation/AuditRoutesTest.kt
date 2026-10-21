package me.nathanfallet.teamcode.presentation

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class AuditRoutesTest {
    @Test fun auditApiAndDashboardAreAdminOnlyBoundedAndEscaped() = testApplication {
        val team = FakeTeam()
        var reads = 0
        val audit = object : AuditService {
            override suspend fun list(actor: Principal, beforeId: Long?): AuditPage {
                actor.requireAdmin(); AuditService.validateCursor(beforeId); reads++
                return if (beforeId == null) AuditPage(listOf(AuditEvent(100, "<actor>", "account.<changed>", "<subject>", 1000)), 100)
                else { assertEquals(100, beforeId); AuditPage(emptyList(), null) }
            }
        }
        val auth = object : TeamService by team {
            override suspend fun authenticate(token: String) = if (token == "service") team.authenticate("admin")!!.copy(serviceScopes = setOf(ServiceScope.USAGE_READ)) else team.authenticate(token)
        }
        val gateway = object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("unused")
        }
        application { configureGateway(gateway, team = auth, audit = audit) }
        for (token in listOf("member", "service", "temporary")) {
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/audit") { bearerAuth(token) }.status)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/audit").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/audit") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/dashboard/audit") { header(HttpHeaders.Cookie, "teamcode_session=member") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/dashboard/audit") { header(HttpHeaders.Cookie, "teamcode_session=service") }.status)
        assertEquals(0, reads)
        val sdk = TeamCodeClient("http://localhost", { "admin" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        try {
            val page = sdk.audit()
            assertEquals("account.<changed>", page.events.single().action); assertEquals(100, page.nextBeforeId)
            assertTrue(sdk.audit(page.nextBeforeId).events.isEmpty())
            assertFailsWith<IllegalArgumentException> { sdk.audit(0) }
        } finally { sdk.close() }
        val response = client.get("/dashboard/audit") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store, no-transform", response.headers[HttpHeaders.CacheControl])
        val html = response.bodyAsText()
        assertTrue(html.contains("&lt;actor&gt;")); assertFalse(html.contains("<actor>"))
        assertTrue(html.contains("account.&lt;changed&gt;")); assertTrue(html.contains("&lt;subject&gt;"))
        assertTrue(html.contains("/dashboard/audit?before=100"))
        val empty = client.get("/dashboard/audit?before=100") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertTrue(empty.contains("No events on this page.")); assertFalse(empty.contains("Older events</a>"))
        val validReads = reads
        for (query in listOf("before=0", "before=-1", "before=invalid", "before=9223372036854775808", "before=1&before=2", "before=")) {
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/audit?$query") { bearerAuth("admin") }.status, query)
        }
        assertEquals(validReads, reads)
        val metadata = client.get("/api/v1/audit") { bearerAuth("admin") }.bodyAsText()
        assertFalse(metadata.contains("accessToken")); assertFalse(metadata.contains("tokenHash")); assertFalse(metadata.contains("credentialEnvelope"))
    }
}
