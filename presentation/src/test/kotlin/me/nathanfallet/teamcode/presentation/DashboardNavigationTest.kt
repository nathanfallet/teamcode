package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class DashboardNavigationTest {
    private val admin = Principal(Member("admin-id", "admin", Role.ADMIN, false, false), "session", "default")
    private val member = admin.copy(member = admin.member.copy(role = Role.MEMBER))

    @Test fun everyDashboardPageIsInTheMenuOfTheRolesAllowedToOpenIt() {
        val all = DashboardFeature.entries.toSet()
        val links = DASHBOARD_MENU.flatMap { it.links }
        val adminPaths = links.filter { it.visibleTo(admin, all) }.map { it.path }.toSet()
        val memberPaths = links.filter { it.visibleTo(member, all) }.map { it.path }.toSet()
        for (path in listOf("/dashboard/mcp", "/dashboard/cloud", "/dashboard/variants", "/dashboard/github", "/dashboard/settings", "/dashboard/members", "/dashboard/accounts", "/dashboard/skills", "/dashboard/audit")) assertTrue(path in adminPaths, path)
        for (path in listOf("/dashboard/groups", "/dashboard/capacity", "/dashboard/sessions", "/dashboard/agent-guidance", "/dashboard/devices", "/dashboard/service-keys", "/dashboard/github", "/dashboard/password", "/dashboard/settings")) assertTrue(path in memberPaths, path)
        for (path in listOf("/dashboard/mcp", "/dashboard/cloud", "/dashboard/variants", "/dashboard/members", "/dashboard/accounts", "/dashboard/usage", "/dashboard/forecasts", "/dashboard/models", "/dashboard/routes", "/dashboard/skills", "/dashboard/audit")) assertFalse(path in memberPaths, path)
        assertTrue(DASHBOARD_MENU.size <= 5)
        assertFalse(links.first { it.path == "/dashboard/mcp" }.visibleTo(admin, all - DashboardFeature.MCP))
    }

    @Test fun detailPagesKeepTheirMenuEntryActive() {
        assertEquals("/dashboard", activeLink("/dashboard")?.path)
        assertEquals("/dashboard/groups", activeLink("/dashboard/groups/group-id")?.path)
        assertEquals("/dashboard/capacity", activeLink("/dashboard/capacity/member-id/overrides")?.path)
        assertEquals("/dashboard/members", activeLink("/dashboard/members/member-id/devices")?.path)
        assertNull(activeLink("/dashboard/unknown"))
    }

    @Test fun numbersAreGroupedAndMetersAreBounded() {
        assertEquals("1,234,567", units(1_234_567))
        assertEquals("333k", compactUnits(333_333))
        assertEquals("2.5M", compactUnits(2_500_000))
        assertEquals("950", compactUnits(950))
        assertTrue(meter(10, 0).contains("width:100.0%"))
        assertTrue(meter(0, 0).contains("width:0.0%"))
        assertTrue(meter(200, 100).contains("width:100.0%") && meter(200, 100).contains("danger"))
        assertTrue(meter(80, 100).contains("warn"))
    }

    @Test fun menuOnlyLinksInstalledPagesAndHidesAdministrationFromMembers() = testApplication {
        application { configureGateway(NoInference(), team = FakeTeam(), publicOrigin = "http://localhost") }
        val adminHtml = client.get("/dashboard") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertTrue(adminHtml.contains("""<a href="/dashboard" class="active" aria-current="page">Overview</a>"""))
        assertTrue(adminHtml.contains("href=\"/dashboard/members\""))
        assertTrue(adminHtml.contains("href=\"/dashboard/settings\""))
        assertTrue(adminHtml.contains("action=\"/dashboard/logout\""))
        assertFalse(adminHtml.contains("href=\"/dashboard/mcp\""), "MCP routes are not installed on this server")
        assertFalse(adminHtml.contains("<script"))
        val memberHtml = client.get("/dashboard") { header(HttpHeaders.Cookie, "teamcode_session=member") }.bodyAsText()
        assertFalse(memberHtml.contains("href=\"/dashboard/members\""))
        assertTrue(memberHtml.contains("href=\"/dashboard/password\""))
        val settings = client.get("/dashboard/settings") { header(HttpHeaders.Cookie, "teamcode_session=member") }
        assertEquals(HttpStatusCode.OK, settings.status)
        assertTrue(settings.bodyAsText().contains("Your account"))
        assertFalse(settings.bodyAsText().contains("Team-wide"))
        val login = client.get("/dashboard/login").bodyAsText()
        assertFalse(login.contains("class=\"sidebar\""))
        assertTrue(client.get("/dashboard/settings").bodyAsText().contains("<h1>Sign in</h1>"), "The settings page requires a session")
    }

    private class NoInference : GatewayService {
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) { error("No inference expected") }
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
    }
}
