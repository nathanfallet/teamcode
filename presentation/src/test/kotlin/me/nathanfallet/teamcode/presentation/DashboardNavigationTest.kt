package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.routing.*
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
        for (path in listOf("/dashboard/groups", "/dashboard/capacity", "/dashboard/sessions", "/dashboard/agent-guidance", "/dashboard/devices", "/dashboard/service-keys", "/dashboard/github", "/dashboard/password", "/dashboard/settings", "/dashboard/skills")) assertTrue(path in memberPaths, path)
        for (path in listOf("/dashboard/mcp", "/dashboard/cloud", "/dashboard/variants", "/dashboard/members", "/dashboard/accounts", "/dashboard/usage", "/dashboard/forecasts", "/dashboard/models", "/dashboard/routes", "/dashboard/audit")) assertFalse(path in memberPaths, path)
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
        assertEquals("1M", compactUnits(999_999))
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

    @Test fun overviewSummarizesEnabledMembersPoolsAndFreshProviderWindows() = testApplication {
        val disabled = Member("gone-id", "gone", Role.MEMBER, true, false)
        val worker = Member("worker-id", "worker", Role.MEMBER, false, false)
        val team = object : TeamService by FakeTeam() {
            override suspend fun members(principal: Principal): List<Member> { principal.requireAdmin(); return listOf(admin.member, worker, disabled) }
        }
        val now = System.currentTimeMillis()
        val admission = OverviewAdmission(mapOf("admin-id" to 100L, "worker-id" to 900L, "gone-id" to 5_000L), listOf(
            AccountQuota("hot", "five_hour", .92, now, now + 3_600_000), AccountQuota("expired", "five_hour", .99, now - 7_200_000, now - 3_600_000)))
        application { configureGateway(NoInference(), team = team, publicOrigin = "http://localhost", admission = admission) }
        val html = client.get("/dashboard") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertTrue(html.contains("Who consumes what today"))
        assertTrue(html.contains("of <strong>2,000</strong> credits allocated"), "Disabled members count neither in usage nor in allocation")
        assertTrue(html.contains("<div class=\"big\">1k</div>"))
        assertTrue(html.contains("<div class=\"big\">1</div><span class=\"meter-label\">of 1 observed"), "A window whose reset passed is not pressure")
        assertTrue(html.contains("<strong>92%</strong>"))
        assertTrue(html.contains("What remains per pool"))
        assertTrue(html.contains(">1,000</td>"), "Shared pool: 2,000 budget minus 1,000 used")
        val member = client.get("/dashboard") { header(HttpHeaders.Cookie, "teamcode_session=member") }.bodyAsText()
        assertTrue(member.contains("Your allowance today"))
        assertFalse(member.contains("What remains per pool"))
        assertFalse(member.contains("Accounts close to their limit"))
        val capacity = client.get("/dashboard/capacity") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertTrue(capacity.contains("name=\"weight\"") && capacity.contains("name=\"concurrent\"") && capacity.contains("name=\"overflow\""))
        assertTrue(capacity.contains("Pool share: <strong>1k/day</strong>"))
        assertTrue(capacity.contains("Disabled members receive no allocation."))
    }

    private class OverviewAdmission(private val used: Map<String, Long>, private val windows: List<AccountQuota>) : AdmissionStore {
        override suspend fun capacity(actor: Principal, memberId: String): MemberCapacity {
            if (memberId != actor.member.id) actor.requireAdmin()
            return MemberCapacity(MemberPolicy(memberId, weight = 1), used.getValue(memberId), 0, 86_400_000, 1_000, poolDailyUnits = 2_000, poolTotalWeight = 2)
        }
        override suspend fun poolBudgets(actor: Principal): List<PoolBudget> { actor.requireAdmin(); return listOf(PoolBudget("", 2_000)) }
        override suspend fun quotas(actor: Principal): List<AccountQuota> { actor.requireAdmin(); return windows }
        override suspend fun preferredTarget(actor: Principal, affinity: RequestAffinity): RouteTarget? = error("unused")
        override suspend fun statistics(): AdmissionStatistics = error("unused")
        override suspend fun reserve(actor: Principal, units: Long, model: String, provider: String, excludedAccountIds: Set<String>, affinity: RequestAffinity?): Reservation = error("unused")
        override suspend fun settle(reservation: Reservation, actualUnits: Long?, completed: Boolean) = error("unused")
        override suspend fun configure(actor: Principal, policy: MemberPolicy): MemberPolicy = error("unused")
        override suspend fun configurePool(actor: Principal, budget: PoolBudget): PoolBudget = error("unused")
        override suspend fun observe(accountId: String, observations: List<AccountQuota>) = error("unused")
        override suspend fun coolDown(accountId: String, untilMillis: Long, reconnect: Boolean) = error("unused")
    }

    private class NoInference : GatewayService {
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) { error("No inference expected") }
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
    }
}
