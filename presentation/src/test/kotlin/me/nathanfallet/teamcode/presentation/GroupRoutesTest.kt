package me.nathanfallet.teamcode.presentation

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class GroupRoutesTest {
    @Test fun typedClientAndEscapedDashboardUseInteractiveAuthenticatedCsrfProtectedManagement() = testApplication {
        val calls = mutableListOf<String>()
        val group = TeamGroup("group-id", "<img src=x onerror=alert(1)>", 1000, 2)
        val roster = listOf(GroupMembership("member-id", "<member>", GroupRole.MEMBER, false, false))
        fun detail() = GroupDetail(GroupSummary(group, GroupUsage(80, 1), true), roster)
        val groups = object : GroupService {
            override suspend fun list(actor: Principal): List<GroupSummary> { calls += "list"; return listOf(detail().summary) }
            override suspend fun get(actor: Principal, groupId: String): GroupDetail { assertEquals("group-id", groupId); calls += "get"; return detail() }
            override suspend fun create(actor: Principal, policy: GroupPolicy): GroupDetail { actor.requireAdmin(); assertEquals(GroupPolicy("Developers", 1000, 2), policy); calls += "create"; return detail() }
            override suspend fun update(actor: Principal, groupId: String, policy: GroupPolicy): GroupDetail { actor.requireAdmin(); assertEquals("group-id", groupId); assertTrue(policy.archived); calls += "update"; return detail() }
            override suspend fun assign(actor: Principal, groupId: String, memberId: String, role: GroupRole, paused: Boolean): GroupDetail {
                actor.requireAdmin(); assertEquals("group-id", groupId); assertEquals("member-id", memberId); assertEquals(GroupRole.MANAGER, role); assertTrue(paused); calls += "assign"; return detail()
            }
            override suspend fun remove(actor: Principal, groupId: String, memberId: String): GroupDetail {
                actor.requireAdmin(); assertEquals("group-id", groupId); assertEquals("member-id", memberId); calls += "remove"; return detail()
            }
            override suspend fun pause(actor: Principal, groupId: String, memberId: String, paused: Boolean): GroupDetail {
                assertEquals("group-id", groupId); assertEquals("member-id", memberId); assertTrue(paused); calls += "pause"; return detail()
            }
        }
        val gateway = object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("No inference expected")
        }
        val team = object : TeamService by FakeTeam() {
            override suspend fun authenticate(token: String): Principal? = if (token == "service") FakeTeam().authenticate("admin")!!.copy(serviceScopes = ServiceScope.entries.toSet()) else FakeTeam().authenticate(token)
        }
        application { configureGateway(gateway, team = team, groups = groups, publicOrigin = "http://localhost") }
        TeamCodeClient("http://localhost", { "admin" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }.use { sdk ->
            assertEquals("group-id", sdk.groups().single().id)
            assertEquals("<member>", sdk.group("group-id").members.single().username)
            assertEquals(80, sdk.createGroup(GroupPolicyRequest("Developers", 1000, 2)).group.chargedAndReservedUnits)
            sdk.saveGroup("group-id", GroupPolicyRequest("Archived", archived = true))
            sdk.assignGroupMember("group-id", "member-id", GroupAssignmentRequest("MANAGER", true))
            sdk.pauseGroupMember("group-id", "member-id", true)
            sdk.removeGroupMember("group-id", "member-id")
            assertEquals(listOf("list", "get", "create", "update", "assign", "pause", "remove"), calls)
            assertTrue(sdk.capabilities().features.contains("team.groups.v1"))
            val count = calls.size
            assertFailsWith<IllegalArgumentException> { sdk.group("../audit") }
            assertFailsWith<IllegalArgumentException> { sdk.pauseGroupMember("group-id", "../members", true) }
            assertEquals(count, calls.size)
        }
        val page = client.get("/dashboard/groups/group-id") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        val html = page.bodyAsText()
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;")); assertFalse(html.contains("<img"))
        assertTrue(html.contains("&lt;member&gt;")); assertTrue(html.contains("Pause admission")); assertTrue(html.contains("Save assignment"))
        assertTrue(page.headers["Content-Security-Policy"]!!.contains("default-src 'none'"))
        for ((origin, csrf) in listOf("https://attacker.test" to "session", "http://localhost" to "wrong")) {
            val before = calls.size
            assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/groups/group-id/members/member-id/pause") {
                header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, origin)
                contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=$csrf&paused=true")
            }.status)
            assertEquals(before, calls.size)
        }
        assertEquals(HttpStatusCode.Found, client.post("/dashboard/groups/group-id/members/member-id/pause") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session&paused=true")
        }.status)
        val before = calls.size
        for ((method, path, body) in listOf(
            Triple(HttpMethod.Get, "/api/v1/groups", ""), Triple(HttpMethod.Get, "/api/v1/groups/group-id", ""),
            Triple(HttpMethod.Post, "/api/v1/groups", "{}"), Triple(HttpMethod.Put, "/api/v1/groups/group-id", "{}"),
            Triple(HttpMethod.Put, "/api/v1/groups/group-id/members/member-id", "{}"),
            Triple(HttpMethod.Delete, "/api/v1/groups/group-id/members/member-id", ""),
            Triple(HttpMethod.Patch, "/api/v1/groups/group-id/members/member-id/pause", "{\"paused\":true}"))) {
            assertEquals(HttpStatusCode.Forbidden, client.request(path) {
                this.method = method; bearerAuth("service"); contentType(ContentType.Application.Json); if (body.isNotEmpty()) setBody(body)
            }.status)
        }
        assertEquals(before, calls.size)
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/groups/group-id/members/member-id") {
            bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"role":"OWNER"}""")
        }.status)
        assertEquals(HttpStatusCode.BadRequest, client.patch("/api/v1/groups/group-id/members/member-id/pause") {
            bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"paused":"sometimes"}""")
        }.status)
        assertEquals(before, calls.size)
    }
}
