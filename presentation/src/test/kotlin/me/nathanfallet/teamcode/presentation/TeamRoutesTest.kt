package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*
import me.nathanfallet.teamcode.domain.routing.*

class TeamRoutesTest {
    @Test fun sessionGuidanceUsesAuthenticatedMemberAndExplicitClaims() = testApplication {
        var claimed = false
        var acknowledged = false
        val directives = object : me.nathanfallet.teamcode.domain.capacity.DirectiveService {
            override suspend fun current(actor: Principal, sessionId: String?) = null
            override suspend fun claim(actor: Principal, sessionId: String, stage: String): me.nathanfallet.teamcode.domain.capacity.DirectiveDelivery? {
                assertEquals(Role.MEMBER, actor.member.role)
                assertEquals("test-session", sessionId)
                assertEquals("EMITTED_TO_HOOK", stage)
                claimed = true
                return me.nathanfallet.teamcode.domain.capacity.DirectiveDelivery(
                    me.nathanfallet.teamcode.domain.capacity.AgentDirective("directive", actor.member.id,
                        me.nathanfallet.teamcode.domain.capacity.PacingAdvice(me.nathanfallet.teamcode.domain.capacity.Pace.SLOW_DOWN, 1, 10000, "Slow optional work."), 1, Long.MAX_VALUE), "claim")
            }
            override suspend fun acknowledge(actor: Principal, id: String, stage: String, sessionId: String?, claimId: String?) {
                assertEquals(listOf("directive", "EMITTED_TO_HOOK", "test-session", "claim"), listOf(id, stage, sessionId, claimId)); acknowledged = true
            }
        }
        application { configureGateway(NoGateway(), team = FakeTeam(), directives = directives) }
        val body = """{"sessionId":"test-session","stage":"EMITTED_TO_HOOK"}"""
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/directives/claim") { contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/directives/claim") { header(HttpHeaders.Cookie, "teamcode_session=member"); contentType(ContentType.Application.Json); setBody(body) }.status)
        val response = client.post("/api/v1/directives/claim") { bearerAuth("member"); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Slow optional work."))
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/directives/receipt") {
            bearerAuth("member"); contentType(ContentType.Application.Json)
            setBody("""{"id":"directive","sessionId":"test-session","stage":"EMITTED_TO_HOOK","claimId":"claim"}""")
        }.status)
        assertTrue(claimed && acknowledged)
    }
    @Test fun registrationIsAbsentAndTemporaryPasswordCannotReachInference() = testApplication {
        val team = FakeTeam()
        application { configureGateway(NoGateway(), team = team, publicOrigin = "http://localhost") }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/register").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/status") { bearerAuth("temporary") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/auth/me") { bearerAuth("temporary") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/members") { bearerAuth("member") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/members") { bearerAuth("admin") }.status)
    }
    @Test fun routeManagementRequiresAdminAndBrowserCsrf() = testApplication {
        var saved = 0
        val store = object : RoutingStore {
            override suspend fun list(teamId: String) = emptyList<RouteRule>()
            override suspend fun save(actor: Principal, rule: RouteRule): RouteRule {
                actor.requireAdmin(); ResolveRouteUseCase().validate(rule); saved++; return rule.copy(version = 1)
            }
            override suspend fun delete(actor: Principal, id: String, version: Long) { actor.requireAdmin(); saved-- }
        }
        application { configureGateway(NoGateway(), team = FakeTeam(), publicOrigin = "http://localhost", routes = store) }
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/routes") { bearerAuth("member") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/dashboard/routes") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.status)
        val body = """{"id":"opus","pattern":"claude-opus-*","targets":[{"provider":"codex","model":"gpt-6-astra"}]}"""
        assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/routes/opus") { bearerAuth("member"); contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/routes/opus") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/routes/wrong") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/routes") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "https://attacker.test")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session&action=delete&id=opus&version=1")
        }.status)
        assertEquals(1, saved)
    }
    @Test fun browserActionsRequireOriginAndCsrfAndApiIgnoresCookies() = testApplication {
        val team = FakeTeam()
        application { configureGateway(NoGateway(), team = team, publicOrigin = "http://localhost") }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/members") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.status)
        val page = client.get("/dashboard/members") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("name='csrf'"))
        assertTrue(page.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'none'"))
        suspend fun submit(origin: String, body: String) = client.post("/dashboard/members") {
            header(HttpHeaders.Cookie, "teamcode_session=admin")
            header(HttpHeaders.Origin, origin)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Forbidden, submit("https://attacker.test", "csrf=session&username=alice&role=MEMBER").status)
        assertEquals(HttpStatusCode.Forbidden, submit("http://localhost", "csrf=wrong&username=alice&role=MEMBER").status)
        assertEquals(0, team.created)
        val created = submit("http://localhost", "csrf=session&username=alice&role=MEMBER")
        assertEquals(HttpStatusCode.OK, created.status)
        assertEquals(1, team.created)
        assertTrue(created.bodyAsText().contains("synthetic-temporary-password"))
        assertEquals("no-store", created.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/login") {
            header(HttpHeaders.Origin, "https://attacker.test")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("username=admin&password=password")
        }.status)
    }
    @Test fun dashboardPagesLetBrowsersSendTheirOriginOnForms() = testApplication {
        application { configureGateway(NoGateway(), team = FakeTeam()) }
        val page = client.get("/dashboard/login")
        assertEquals(HttpStatusCode.OK, page.status)
        // Under no-referrer a browser submits forms with `Origin: null`, which the dashboard always rejects.
        assertEquals("same-origin", page.headers["Referrer-Policy"])
        assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/login") {
            header(HttpHeaders.Origin, "null")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("username=admin&password=password")
        }.status)
    }
}
private class NoGateway : GatewayService {
    override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) { error("No inference expected") }
    override suspend fun status() = GatewaySnapshot("test", 0, 0)
}
internal class FakeTeam : TeamService {
    var created = 0
    private val admin = Principal(Member("admin-id", "admin", Role.ADMIN, false, false), "session", "default")
    override suspend fun authenticate(token: String) = when (token) {
        "admin" -> admin
        "temporary" -> admin.copy(member = admin.member.copy(passwordChangeRequired = true))
        "member" -> admin.copy(member = admin.member.copy(role = Role.MEMBER))
        else -> null
    }
    override suspend fun login(username: String, password: String, device: String, installationId: String?) = LoginSession("admin", admin, Long.MAX_VALUE)
    override suspend fun logout(principal: Principal) = Unit
    override suspend fun changePassword(principal: Principal, current: String, replacement: String) = Unit
    override suspend fun members(principal: Principal): List<Member> { principal.requireAdmin(); return listOf(admin.member) }
    override suspend fun createMember(principal: Principal, username: String, role: Role): NewMember { principal.requireAdmin(); created++; return NewMember(Member("new-id", username, role, false, true), "synthetic-temporary-password") }
    override suspend fun updateMember(principal: Principal, id: String, role: Role, disabled: Boolean) = error("unused")
    override suspend fun resetPassword(principal: Principal, id: String) = error("unused")
}
