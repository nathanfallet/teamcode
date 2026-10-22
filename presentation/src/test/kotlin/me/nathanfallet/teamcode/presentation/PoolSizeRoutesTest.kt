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
import me.nathanfallet.teamcode.domain.routing.TokenUsage
import kotlin.test.*

/** API and dashboard parity for the automatic home pool size and the API price table. */
class PoolSizeRoutesTest {
    @Test fun theCheckboxAndPricesAreAdministeredFromTheApiAndTheDashboard() = testApplication {
        var stored = PacingSettings()
        val settings = object : PacingSettingsStore {
            override suspend fun get(actor: Principal): PacingSettings { actor.requireInteractive(); actor.requireReady(); return stored }
            override suspend fun save(actor: Principal, settings: PacingSettings): PacingSettings { actor.requireAdmin(); stored = settings; return stored }
        }
        var table = emptyList<ApiPrice>()
        val prices = object : ApiPriceStore {
            override suspend fun list(actor: Principal): List<ApiPrice> { actor.requireUsageRead(); return table }
            override suspend fun replace(actor: Principal, prices: List<ApiPrice>): List<ApiPrice> { actor.requireAdmin(); table = ApiPrices.validate(prices); return table }
        }
        val sizing = object : PoolSizingService {
            override suspend fun report(actor: Principal): PoolSizeReport {
                actor.requireTeamRead()
                val estimate = PoolSizeEstimate(40_000_000, 80_000_000, listOf(AccountPoolShare("account-1", PoolContribution.COUNTED, 40_000_000, "seven_day", 28_571_429),
                    AccountPoolShare("account-2", PoolContribution.NO_ESTIMATE, 0)), null, capacityDailyMicros = 28_571_429, capacityUnknownAccounts = 1)
                val mix = TokenMix(listOf(ModelUsage("anthropic", "claude-opus-5-5", TokenUsage(1_000_000, 1_000_000, 100_000_000, 1_000_000),
                    PricedCost(4_000_000, 5_000_000, 20_000_000, 20_000_000))), 103.0 / 49)
                return PoolSizeReport(stored.autoPoolSize, 1_000_000, 80_000_000, 1_000, if (stored.autoPoolSize) 80_000_000 else 1_000_000, stored.autoPoolSize,
                    estimate, 2.0, listOf("anthropic/unpriced"), mapOf("account-1" to listOf(WindowCapacity("seven_day", 200_000_000, 4))),
                    mapOf("account-1" to listOf(WindowUsageSplit("seven_day", 0, 1_000, 2_000, .5, 60_000_000, .3, .2, 40_000_000, 100_000_000))), mix)
            }
            override suspend fun refresh() = Unit
        }
        val gateway = object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("No inference expected")
        }
        application { configureGateway(gateway, team = FakeTeam(), pacingSettings = settings, apiPrices = prices, poolSizing = sizing, publicOrigin = "http://localhost") }
        val sdk = TeamCodeClient("http://localhost", { "admin" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        try {
            assertEquals(false, sdk.pacingSettings().autoPoolSize, "Off by default")
            assertEquals("manual", sdk.poolSize().source)
            val on = sdk.savePacingSettings(PacingSettingsView(observationOnly = false, autoPoolSize = true))
            assertEquals(true, on.autoPoolSize)
            assertEquals(95, on.quotaPressurePercent)
            val size = sdk.poolSize()
            assertEquals("automatic", size.source)
            assertEquals(80_000_000, size.effectiveDailyUnits)
            assertEquals(1_000_000, size.manualDailyUnits, "The manual budget is reported unchanged")
            assertEquals(listOf("counted", "no_estimate"), size.accounts.map { it.status })
            assertEquals(200_000_000, size.accounts.first().windows.single().fullWindowApiMicros)
            assertEquals(28_571_429, size.capacityDailyApiMicros, "The capacity is reported apart from what is left today")
            assertEquals(1, size.capacityUnknownAccounts)
            assertEquals(Math.round(28_571_429 * 103.0 / 49), size.capacityDailyTokens)
            assertEquals(28_571_429, size.accounts.first().capacityDailyApiMicros)
            assertNull(size.accounts.last().capacityDailyApiMicros, "Unknown, not zero")
            val weekly = size.accounts.first().usage.single()
            assertEquals(.2, weekly.outsideUtilization); assertEquals(40_000_000, weekly.outsideApiMicros); assertEquals(100_000_000, weekly.remainingApiMicros)
            assertEquals(40_000_000, size.outsideWeeklyApiMicros)
            assertEquals(20_000_000, size.models.single().cacheReadApiMicros, "Cache reads are priced apart")
            assertEquals(49_000_000, size.models.single().apiEquivalentMicros)
            assertEquals(true, sdk.savePacingSettings(PacingSettingsView(observationOnly = true)).autoPoolSize, "Omitting autoPoolSize keeps it")
            assertEquals(false, sdk.savePacingSettings(PacingSettingsView(observationOnly = true, autoPoolSize = false)).autoPoolSize)
            assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/policy/pacing") {
                bearerAuth("member"); contentType(ContentType.Application.Json); setBody("""{"observationOnly":false,"autoPoolSize":true}""")
            }.status)
            assertFalse(stored.autoPoolSize)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/pools/size") { bearerAuth("member") }.status)

            val row = ApiPriceView("anthropic", "claude-sonnet-4", "2026-10-01", 3_000_000, 3_750_000, 300_000, 15_000_000)
            assertEquals(listOf(row), sdk.saveApiPrices(ApiPriceTableView(listOf(row))).prices)
            assertEquals(listOf(row), sdk.apiPrices().prices)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/policy/api-prices") { bearerAuth("member") }.status, "Members read the prices")
            assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/policy/api-prices") {
                bearerAuth("member"); contentType(ContentType.Application.Json); setBody("""{"prices":[]}""")
            }.status)
            assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/policy/api-prices") {
                bearerAuth("admin"); contentType(ContentType.Application.Json)
                setBody("""{"prices":[{"provider":"anthropic","model":"claude","effectiveFrom":"01/10/2026","inputMicros":1,"cacheWriteMicros":1,"cacheReadMicros":1,"outputMicros":1}]}""")
            }.status)

            val page = client.get("/dashboard/capacity/pool-size") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
            assertTrue(page.contains("name=\"autoPoolSize\" >"), "Unchecked after the last save")
            assertTrue(page.contains("<code>claude-sonnet-4</code>")); assertTrue(page.contains("<code>anthropic/unpriced</code>"))
            assertTrue(page.contains("bound by seven_day")); assertTrue(page.contains("fewer than three samples"))
            assertTrue(page.contains("Subscription capacity: $28.57 a day in API-equivalent"), "The capacity leads the page")
            assertTrue(page.contains("1 account that can serve is not learned yet"))
            assertTrue(page.contains("outside 20% ($40.00)")); assertTrue(page.contains("$40.00 in API-equivalent"))
            assertTrue(page.contains("Last seven days by model"))
            assertEquals(HttpStatusCode.Forbidden, client.get("/dashboard/capacity/pool-size") { header(HttpHeaders.Cookie, "teamcode_session=member") }.status)
            suspend fun form(path: String, body: String) = client.post(path) {
                header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
                contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session&$body")
            }.status
            assertEquals(HttpStatusCode.Found, form("/dashboard/capacity/pool-size", "autoPoolSize=on"))
            assertTrue(stored.autoPoolSize)
            assertEquals(HttpStatusCode.Found, form("/dashboard/capacity/pool-size", ""))
            assertFalse(stored.autoPoolSize, "An unchecked box turns it off")
            assertEquals(HttpStatusCode.Found, form("/dashboard/capacity/api-prices",
                "provider=anthropic&model=Claude-Opus-4&effectiveFrom=2026-10-02&input=15&cacheWrite=18.75&cacheRead=1.5&output=75"))
            assertEquals(ApiPrice("anthropic", "claude-opus-4", 20_728, 15_000_000, 18_750_000, 1_500_000, 75_000_000), table.single { it.model == "claude-opus-4" },
                "Dollars per million tokens become micro-dollars per million; the model is lowercased")
            assertEquals(HttpStatusCode.BadRequest, form("/dashboard/capacity/api-prices",
                "provider=anthropic&model=claude-haiku-4&effectiveFrom=2026-10-02&input=-1&cacheWrite=0&cacheRead=0&output=0"))
            assertEquals(HttpStatusCode.BadRequest, form("/dashboard/capacity/api-prices",
                "provider=anthropic&model=claude-haiku-4&effectiveFrom=2026-10-02&input=18446744073710&cacheWrite=0&cacheRead=0&output=1"), "No silent overflow")
            assertEquals(HttpStatusCode.Found, form("/dashboard/capacity/api-prices", "remove=on&provider=anthropic&model=claude-opus-4&effectiveFrom=2026-10-02"))
            assertEquals(listOf("claude-sonnet-4"), table.map { it.model })

            assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/policy/api-prices/load-defaults") { bearerAuth("member") }.status)
            assertEquals(listOf("claude-sonnet-4"), table.map { it.model }, "Nothing is loaded without an administrator")
            assertTrue(page.contains("action=\"/dashboard/capacity/api-prices/load-defaults\""), "The dashboard offers the action")
            val loaded = sdk.loadDefaultApiPrices().prices
            assertEquals(AnthropicListPrices.rows.size + 1, loaded.size)
            assertTrue(loaded.any { it.model == "claude-opus-5-5" && it.effectiveFrom == "2026-10-21" && it.inputMicros == 4_000_000L })
            assertEquals(loaded, sdk.loadDefaultApiPrices().prices, "Idempotent")
            table = emptyList()
            assertEquals(HttpStatusCode.Found, form("/dashboard/capacity/api-prices/load-defaults", ""))
            assertEquals(AnthropicListPrices.rows.size, table.size)
        } finally { sdk.close() }
    }
}
