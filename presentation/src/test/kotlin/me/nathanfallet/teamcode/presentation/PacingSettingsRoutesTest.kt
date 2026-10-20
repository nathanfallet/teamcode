package me.nathanfallet.teamcode.presentation

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.api.models.PacingSettingsView
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class PacingSettingsRoutesTest {
    @Test fun sdkAndDashboardExposePreviewWithAdminOnlyCsrfProtectedModeChanges() = testApplication {
        var stored = PacingSettings()
        val settings = object : PacingSettingsStore {
            override suspend fun get(actor: Principal): PacingSettings { actor.requireInteractive(); actor.requireReady(); return stored }
            override suspend fun save(actor: Principal, settings: PacingSettings): PacingSettings {
                actor.requireAdmin(); stored = settings; return stored
            }
        }
        val directive = AgentDirective("synthetic-directive", "admin", PacingAdvice(Pace.SLOW_DOWN, 1, 10_000, "Synthetic forecast"), 1000, 2000)
        val directives = object : DirectiveService {
            override suspend fun preview(actor: Principal) = directive
            override suspend fun current(actor: Principal, sessionId: String?) = directive.takeUnless { stored.observationOnly }
            override suspend fun claim(actor: Principal, sessionId: String, stage: String): DirectiveDelivery? = null
            override suspend fun acknowledge(actor: Principal, id: String, stage: String, sessionId: String?, claimId: String?) = Unit
        }
        val gateway = object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("No inference expected")
        }
        val team = object : TeamService by FakeTeam() {
            override suspend fun authenticate(token: String): Principal? = if (token == "service")
                FakeTeam().authenticate("admin")!!.copy(serviceScopes = ServiceScope.entries.toSet()) else FakeTeam().authenticate(token)
        }
        application { configureGateway(gateway, team = team, directives = directives, pacingSettings = settings, publicOrigin = "http://localhost") }
        val sdk = TeamCodeClient("http://localhost", { "admin" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        try {
            assertFalse(sdk.pacingSettings().observationOnly)
            assertEquals("SLOW_DOWN", sdk.pacingPreview().directive!!.pace)
            assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/policy/pacing") {
                bearerAuth("member"); contentType(ContentType.Application.Json); setBody("""{"observationOnly":true}""")
            }.status)
            assertFalse(stored.observationOnly)
            assertTrue(sdk.savePacingSettings(PacingSettingsView(true)).observationOnly)
            assertTrue(sdk.pacingPreview().settings.observationOnly)
            assertEquals("SLOW_DOWN", sdk.pacingPreview().directive!!.pace)
            assertNull(sdk.directive().directive, "The delivery API remains silent while preview stays available")
            for (path in listOf("/api/v1/policy/pacing", "/api/v1/directives/preview")) {
                assertEquals(HttpStatusCode.Forbidden, client.get(path) { bearerAuth("service") }.status)
            }
            assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/policy/pacing") {
                bearerAuth("service"); contentType(ContentType.Application.Json); setBody("""{"observationOnly":false}""")
            }.status)
            val adminPage = client.get("/dashboard/agent-guidance") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
            assertTrue(adminPage.contains("Observation only")); assertTrue(adminPage.contains("SLOW_DOWN"))
            assertTrue(adminPage.contains("name=\"observationOnly\" checked")); assertTrue(adminPage.contains("informational during observation mode"))
            val memberPage = client.get("/dashboard/agent-guidance") { header(HttpHeaders.Cookie, "teamcode_session=member") }.bodyAsText()
            assertFalse(memberPage.contains("Save controller mode"))
            for ((origin, csrf) in listOf("https://attacker.test" to "session", "http://localhost" to "wrong")) {
                assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/agent-guidance/pacing") {
                    header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, origin)
                    contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=$csrf")
                }.status)
                assertTrue(stored.observationOnly)
            }
            assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/agent-guidance/pacing") {
                header(HttpHeaders.Cookie, "teamcode_session=member"); header(HttpHeaders.Origin, "http://localhost")
                contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session")
            }.status)
            assertTrue(stored.observationOnly)
            assertEquals(HttpStatusCode.Found, client.post("/dashboard/agent-guidance/pacing") {
                header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
                contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session")
            }.status)
            assertFalse(sdk.pacingSettings().observationOnly)
            assertNotNull(sdk.directive().directive)
            assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/policy/pacing") {
                bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"observationOnly":"yes"}""")
            }.status)
            assertFalse(sdk.pacingSettings().observationOnly)
        } finally { sdk.close() }
    }
}
