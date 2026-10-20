package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class OwnerRoutesTest {
    @Test fun dashboardAndApiExposeOwnershipWithoutGrantingItToAdministratorsOrServiceKeys() = testApplication {
        val owner = Principal(Member("owner-id", "owner", Role.OWNER, false, false), "owner-csrf", "default")
        val admin = Principal(Member("admin-id", "admin", Role.ADMIN, false, false), "admin-csrf", "default")
        val access = CheckMemberAccessUseCase()
        var created = 0
        val team = object : TeamService by FakeTeam() {
            override suspend fun authenticate(token: String) = when (token) {
                "owner" -> owner; "admin" -> admin; "service" -> owner.copy(serviceScopes = ServiceScope.entries.toSet()); else -> null
            }
            override suspend fun members(principal: Principal): List<Member> { principal.requireAdmin(); return listOf(owner.member, admin.member) }
            override suspend fun createMember(principal: Principal, username: String, role: Role): NewMember {
                access.create(principal, role); created++; return NewMember(Member("new-id", username, role, false, true), "synthetic-temporary")
            }
            override suspend fun updateMember(principal: Principal, id: String, role: Role, disabled: Boolean): Member {
                access.change(principal, owner.member, role, disabled, false); return owner.member
            }
            override suspend fun resetPassword(principal: Principal, id: String): NewMember {
                access.resetPassword(principal, owner.member, false); error("Last owner recovery must fail")
            }
        }
        val gateway = object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("No inference expected")
        }
        application { configureGateway(gateway, team = team, publicOrigin = "http://localhost") }
        val ownerPage = client.get("/dashboard/members") { header(HttpHeaders.Cookie, "teamcode_session=owner") }
        assertEquals(HttpStatusCode.OK, ownerPage.status)
        assertTrue(ownerPage.bodyAsText().contains("<option selected>OWNER</option>"))
        assertTrue(ownerPage.bodyAsText().contains("/dashboard/members/owner-id/update"))
        val adminPage = client.get("/dashboard/members") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertTrue(adminPage.contains("only an owner can change this account"))
        assertFalse(adminPage.contains(">OWNER</option>"))
        assertFalse(adminPage.contains("/dashboard/members/owner-id/update"))
        assertFalse(adminPage.contains("/dashboard/members/owner-id/reset"))
        assertTrue(adminPage.contains("/dashboard/members/admin-id/update"))
        for (token in listOf("admin", "service")) {
            assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/members") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"username":"new-owner","role":"OWNER"}""")
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.patch("/api/v1/members/owner-id") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"role":"MEMBER","disabled":true}""")
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/members/owner-id/reset-password") { bearerAuth(token) }.status)
        }
        assertEquals(0, created)
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/members") {
            bearerAuth("owner"); contentType(ContentType.Application.Json); setBody("""{"username":"new-owner","role":"OWNER"}""")
        }.status)
        assertEquals(1, created)
        assertEquals(HttpStatusCode.Conflict, client.patch("/api/v1/members/owner-id") {
            bearerAuth("owner"); contentType(ContentType.Application.Json); setBody("""{"role":"ADMIN","disabled":false}""")
        }.status)
        assertEquals(HttpStatusCode.Conflict, client.post("/api/v1/members/owner-id/reset-password") { bearerAuth("owner") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/members") {
            header(HttpHeaders.Cookie, "teamcode_session=owner"); header(HttpHeaders.Origin, "https://attacker.test")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=owner-csrf&username=another-owner&role=OWNER")
        }.status)
        assertEquals(1, created)
    }
}
