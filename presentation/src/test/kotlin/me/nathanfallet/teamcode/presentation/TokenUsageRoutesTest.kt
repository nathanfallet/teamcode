package me.nathanfallet.teamcode.presentation

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import java.time.ZoneOffset
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class TokenUsageRoutesTest {
    @Test fun rawTokenUsageIsReadWithUsageReadByDateRangeAndPagedByCursor() = testApplication {
        val team = object : TeamService by FakeTeam() {
            override suspend fun authenticate(token: String): Principal? = when (token) {
                "usage-key" -> FakeTeam().authenticate("member")!!.copy(serviceScopes = setOf(ServiceScope.USAGE_READ))
                "inference-key" -> FakeTeam().authenticate("member")!!.copy(serviceScopes = setOf(ServiceScope.INFERENCE))
                "team-key" -> FakeTeam().authenticate("admin")!!.copy(serviceScopes = setOf(ServiceScope.TEAM_READ))
                else -> FakeTeam().authenticate(token)
            }
        }
        val gateway = object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("No inference")
        }
        val admission = LedgerAdmission()
        application { configureGateway(gateway, team = team, admission = admission, publicOrigin = "http://localhost") }
        fun sdk(token: String) = TeamCodeClient("http://localhost", { token }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }

        val cursor = "20000:${"a".repeat(8)}-0000-0000-0000-000000000000::anthropic:claude-opus-test"
        sdk("usage-key").use { key ->
            val page = key.tokenUsage(from = "2024-10-01", to = "2024-10-31", after = cursor)
            val row = page.rows.single()
            assertEquals("2024-10-21", row.day)
            assertEquals(listOf(5L, 1L, 10L, 20L, 30L, 40L), listOf(row.requests, row.unmeteredRequests, row.inputTokens, row.cacheCreationTokens, row.cacheReadTokens, row.outputTokens))
            assertEquals("next", page.nextAfter)
            val query = admission.queries.last()
            assertEquals(LocalDate.of(2024, 10, 1).toEpochDay(), query.fromDay); assertEquals(LocalDate.of(2024, 10, 31).toEpochDay(), query.toDay)
            assertEquals(TokenUsageCursor.decode(cursor), query.after)
        }
        val today = LocalDate.now(ZoneOffset.UTC).toEpochDay()
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/usage/tokens") { bearerAuth("member") }.status)
        assertEquals(today - 6 to today, admission.queries.last().let { it.fromDay to it.toDay }, "The last seven days by default")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/usage/tokens?all=true") { bearerAuth("member") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/usage/tokens?all=true") { bearerAuth("team-key") }.status, "team:read reads the team")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/usage/tokens?all=true") { bearerAuth("usage-key") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/usage/tokens") { bearerAuth("inference-key") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/usage/tokens").status)
        for ((query, code) in listOf("from=2024-13-01" to "invalid_window", "from=21/10/2024" to "invalid_window", "from=2024-10-02&to=2024-10-01" to "invalid_window",
            "from=2023-01-01&to=2024-10-01" to "invalid_window", "all=yes" to "invalid_usage_query", "after=garbage" to "invalid_cursor")) {
            val response = client.get("/api/v1/usage/tokens?$query") { bearerAuth("admin") }
            assertEquals(HttpStatusCode.BadRequest, response.status, query)
            assertTrue(response.bodyAsText().contains(code), query)
        }
        val discovered = client.get("/api/v1/capabilities") { bearerAuth("member") }.bodyAsText()
        assertTrue(discovered.contains("usage.tokens.v1"), discovered)
    }
}

private class LedgerAdmission : AdmissionStore {
    val queries = mutableListOf<TokenUsageQuery>()
    override suspend fun tokenUsage(actor: Principal, query: TokenUsageQuery): TokenUsagePage {
        actor.requireUsageRead()
        if (query.all || query.memberId != null) actor.requireTeamRead()
        queries += TokenUsageLedger.validate(query)
        return TokenUsagePage(listOf(TokenUsageRow(LocalDate.of(2024, 10, 21).toEpochDay(), actor.member.id, actor.member.username, null, "anthropic", "claude-opus-test",
            5, 1, 10, 20, 30, 40)), "next")
    }
    override suspend fun preferredTarget(actor: Principal, affinity: RequestAffinity): RouteTarget? = error("unused")
    override suspend fun statistics(): AdmissionStatistics = AdmissionStatistics(0, 0)
    override suspend fun reserve(actor: Principal, units: Long, model: String, provider: String, excludedAccountIds: Set<String>, affinity: RequestAffinity?): Reservation = error("unused")
    override suspend fun settle(reservation: Reservation, actualUnits: Long?, completed: Boolean) = error("unused")
    override suspend fun capacity(actor: Principal, memberId: String): MemberCapacity = error("unused")
    override suspend fun configure(actor: Principal, policy: MemberPolicy): MemberPolicy = error("unused")
    override suspend fun poolBudgets(actor: Principal): List<PoolBudget> = error("unused")
    override suspend fun configurePool(actor: Principal, budget: PoolBudget): PoolBudget = error("unused")
    override suspend fun quotas(actor: Principal): List<AccountQuota> = error("unused")
    override suspend fun observe(accountId: String, observations: List<AccountQuota>) = error("unused")
    override suspend fun coolDown(accountId: String, untilMillis: Long, reconnect: Boolean) = error("unused")
}
