package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class ForecastLiveRoutesTest {
    private fun gateway() = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("No inference expected")
    }
    private fun snapshot(sequence: Int): ForecastSnapshot {
        val at = 2_000_000L + sequence
        val account = ForecastAccount("fixture-id", "anthropic", "<script>not executable</script>", "READY", false, null)
        val quota = AccountQuota(account.id, "five_hour", .3, at, at + 3_600_000)
        return ForecastSnapshot(at, listOf(account), account, listOf(BuildForecastSeriesUseCase()(quota, listOf(quota), at)), false)
    }

    @Test fun boundedCookieStreamRefreshesSnapshotsAndOnlyItsPageAllowsTheOwnOriginScript() = testApplication {
        var loads = 0
        val forecasts = object : ForecastService {
            override suspend fun load(actor: Principal, accountId: String?): ForecastSnapshot {
                actor.requireAdmin(); return snapshot(++loads)
            }
        }
        val interactive = FakeTeam()
        val team = object : TeamService by interactive {
            override suspend fun authenticate(token: String) = if (token == "service") interactive.authenticate("admin")!!.copy(serviceScopes = ServiceScope.entries.toSet()) else interactive.authenticate(token)
        }
        application { configureGateway(gateway(), team = team, forecasts = forecasts, forecastLivePolicy = ForecastLivePolicy(50, 600)) }
        val response = client.get("/dashboard/forecasts/stream?account=fixture-id") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith("text/event-stream"))
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("no", response.headers["X-Accel-Buffering"])
        val body = response.bodyAsText()
        assertTrue(body.startsWith("retry: 5000\n\n"))
        // EOF does not dispatch an event until its blank-line terminator has arrived.
        val frames = body.split("\n\n").dropLast(1).filter { it.startsWith("event: snapshot\n") }.map {
            Json.parseToJsonElement(it.substringAfter("data: ")).jsonObject
        }
        assertTrue(frames.size >= 2, "The same open response must contain refreshed snapshots")
        assertTrue(frames.zipWithNext().all { (a, b) -> a.getValue("generatedAtMillis").jsonPrimitive.long < b.getValue("generatedAtMillis").jsonPrimitive.long })
        assertTrue(frames.all { it.getValue("options").jsonPrimitive.content.contains("&lt;script&gt;") })
        assertTrue(frames.none { it.getValue("html").jsonPrimitive.content.contains("<script>") })
        assertFalse(body.contains("event: unavailable"))
        val page = client.get("/dashboard/forecasts") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        val csp = page.headers["Content-Security-Policy"]!!
        assertTrue(csp.contains("script-src 'self'")); assertTrue(csp.contains("connect-src 'self'"))
        assertFalse(csp.contains("script-src 'unsafe-inline'"))
        assertTrue(page.bodyAsText().contains("<noscript>"))
        val ordinary = client.get("/dashboard") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        assertFalse(ordinary.headers["Content-Security-Policy"]!!.contains("script-src"))
        val script = client.get("/dashboard/assets/forecasts.js") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        assertEquals(HttpStatusCode.OK, script.status)
        assertTrue(script.headers[HttpHeaders.ContentType]!!.startsWith("application/javascript"))
        assertTrue(script.bodyAsText().contains("new EventSource(url)"))
        for (token in listOf("member", "service")) {
            assertEquals(HttpStatusCode.Forbidden, client.get("/dashboard/forecasts/stream") { header(HttpHeaders.Cookie, "teamcode_session=$token") }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/dashboard/assets/forecasts.js") { header(HttpHeaders.Cookie, "teamcode_session=$token") }.status)
        }
        val direct = createClient { followRedirects = false }
        assertEquals(HttpStatusCode.Found, direct.get("/dashboard/forecasts/stream") { bearerAuth("admin") }.status,
            "The browser stream does not accept bearer or URL credentials instead of its dashboard cookie")
    }

    @Test fun revokedAuthorityEndsAnAlreadyCommittedStreamWithARedactedTerminalEvent() = testApplication {
        var loads = 0
        val forecasts = object : ForecastService {
            override suspend fun load(actor: Principal, accountId: String?): ForecastSnapshot {
                actor.requireAdmin()
                if (++loads > 1) throw TeamFailure("authentication_required", 401, "Private fixture reason must stay on the server")
                return snapshot(loads)
            }
        }
        application { configureGateway(gateway(), team = FakeTeam(), forecasts = forecasts, forecastLivePolicy = ForecastLivePolicy(50, 1000)) }
        val response = client.get("/dashboard/forecasts/stream") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertEquals(1, Regex("event: snapshot").findAll(body).count())
        assertTrue(body.contains("event: unavailable\ndata: {\"reason\":\"access\"}"))
        assertFalse(body.contains("Private fixture reason")); assertFalse(body.contains("authentication_required"))
        assertFalse(body.contains("<!doctype"))
        assertEquals(2, loads)
    }
}
