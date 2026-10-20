package me.nathanfallet.teamcode.presentation

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class ForecastRoutesTest {
    @Test fun sdkAndEscapedDashboardExposeBoundedAdminSnapshotsWithExplicitUnknowns() = testApplication {
        val now = 2_000_000L
        val account = ForecastAccount("fixture-id", "anthropic", "<img src=x onerror=alert(1)>", "ACCESS_ONLY", false, null)
        fun series(window: String, reset: Long?, values: List<Double>) = BuildForecastSeriesUseCase().let { builder ->
            val samples = values.mapIndexed { index, used -> AccountQuota(account.id, window, used, now - 120_000 + index * 60_000, reset) }
            builder(samples.last(), samples, now)
        }
        val snapshot = ForecastSnapshot(now, listOf(account), account, listOf(
            series("five_hour</title><script>alert(1)</script>", now + 3_600_000, listOf(.1, .2, .3)),
            series("model:unknown", null, listOf(.1, .1, .1))), true)
        var loads = 0
        val forecasts = object : ForecastService {
            override suspend fun load(actor: Principal, accountId: String?): ForecastSnapshot {
                actor.requireAdmin()
                if (accountId != null && !Regex("[a-zA-Z0-9-]{1,36}").matches(accountId)) throw TeamFailure("invalid_account", 400, "Invalid account")
                if (accountId != null && accountId != account.id) throw TeamFailure("not_found", 404, "Missing account")
                loads++; return snapshot
            }
        }
        val interactive = FakeTeam()
        val team = object : TeamService by interactive {
            override suspend fun authenticate(token: String) = if (token == "service") interactive.authenticate("admin")!!.copy(serviceScopes = ServiceScope.entries.toSet()) else interactive.authenticate(token)
        }
        val gateway = object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("No inference expected")
        }
        application { configureGateway(gateway, team = team, forecasts = forecasts) }
        val sdk = TeamCodeClient("http://localhost", { "admin" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        try {
            val response = sdk.forecasts(account.id)
            assertEquals(account.displayName, response.account!!.displayName)
            assertEquals("EXHAUSTS_FIRST", response.windows.first().state)
            assertEquals(3, response.windows.first().learningSamples)
            assertTrue(response.truncated)
            val unknown = response.windows.last()
            assertNull(unknown.resetsAtMillis); assertNull(unknown.pointsPerHour); assertNull(unknown.reachesThresholdAtMillis)
            assertEquals("UNKNOWN", unknown.state)
            assertTrue("capacity.forecasts.v1" in sdk.capabilities().features)
            val html = client.get("/dashboard/forecasts?account=${account.id}") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
            assertEquals(HttpStatusCode.OK, html.status)
            assertEquals("no-store", html.headers[HttpHeaders.CacheControl])
            assertTrue(html.headers["Content-Security-Policy"]!!.contains("default-src 'none'"))
            val text = html.bodyAsText()
            assertTrue(text.contains("&lt;img")); assertFalse(text.contains("<img"))
            assertTrue(text.contains("&lt;/title&gt;&lt;script&gt;")); assertFalse(text.contains("<script>"))
            assertTrue(text.contains("role=\"img\"")); assertTrue(text.contains("quota-projection"))
            assertTrue(text.contains("Rate unknown")); assertTrue(text.contains("bounded subset"))
            val before = loads
            for (token in listOf("member", "service")) {
                assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/forecasts") { bearerAuth(token) }.status)
                assertEquals(HttpStatusCode.Forbidden, client.get("/dashboard/forecasts") { header(HttpHeaders.Cookie, "teamcode_session=$token") }.status)
            }
            assertEquals(before, loads)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/forecasts").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/forecasts?account=a&account=b") { bearerAuth("admin") }.status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/forecasts?account=bad_value") { bearerAuth("admin") }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/forecasts?account=missing") { bearerAuth("admin") }.status)
        } finally { sdk.close() }
    }
}
