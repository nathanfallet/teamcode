package me.nathanfallet.teamcode.presentation

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.flowOf
import me.nathanfallet.teamcode.api.models.CreateServiceKeyRequest
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class ServiceKeyRoutesTest {
    private class Registry : ServiceKeyService {
        val records = mutableMapOf<String, ServiceKey>()
        var creates = 0
        var lastSecret = ""
        var lastDays: Int? = -1
        private fun authorize(actor: Principal, member: String) {
            actor.requireReady(); actor.requireInteractive()
            if (actor.member.id != member) actor.requireAdmin()
        }
        override suspend fun list(actor: Principal, memberId: String): List<ServiceKey> { authorize(actor, memberId); return records.values.filter { it.memberId == memberId } }
        override suspend fun create(actor: Principal, memberId: String, name: String, scopes: Set<ServiceScope>, validDays: Int?): CreatedServiceKey {
            authorize(actor, memberId); ServiceKeyService.validate(name, scopes, validDays)
            ServiceKeyService.requireNoExpiryAllowed(validDays, actor.member.role.isAdministrator)
            lastDays = validDays
            val key = ServiceKey("00000000-0000-0000-0000-${(++creates).toString().padStart(12, '0')}", memberId, name, scopes, 1, if (validDays == null) ServiceKeyService.NO_EXPIRY else 1_000L * validDays)
            records[key.id] = key
            lastSecret = "tcsk_" + "synthetic-service-secret".repeat(2) + creates
            return CreatedServiceKey(key, lastSecret)
        }
        override suspend fun revoke(actor: Principal, id: String): String {
            val key = records.getValue(id); authorize(actor, key.memberId); records.remove(id); return key.memberId
        }
    }
    private fun team(): TeamService {
        val interactive = FakeTeam()
        return object : TeamService by interactive {
            override suspend fun authenticate(token: String): Principal? {
                val scopes = when (token) {
                    "service-inference" -> setOf(ServiceScope.INFERENCE)
                    "service-usage" -> setOf(ServiceScope.USAGE_READ)
                    "service-guidance" -> setOf(ServiceScope.GUIDANCE)
                    "service-all" -> ServiceScope.entries.toSet()
                    else -> return interactive.authenticate(token)
                }
                return interactive.authenticate("admin")!!.copy(serviceScopes = scopes)
            }
        }
    }
    private fun gateway(onInference: () -> Unit = {}): GatewayService = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) {
            request.principal!!.requireScope(ServiceScope.INFERENCE); onInference()
            receive(GenerationHead(200, "text/event-stream"), flowOf("data: {}\n\n".encodeToByteArray()))
        }
    }
    @Test fun serviceCredentialsHaveAClosedTransportAllowlistEvenWhenOwnedByAnAdmin() = testApplication {
        var inferences = 0
        application {
            configureGateway(gateway { inferences++ }, team = team(), serviceKeys = Registry())
            routing {
                get("/api/v1/capacity") { call.respondText("owner capacity") }
                post("/api/v1/directives/current") { call.respondText("owner guidance") }
                get("/api/v1/future-admin") { fail("New endpoints cannot automatically grant service-key access") }
            }
        }
        suspend fun get(path: String, token: String) = client.get(path) { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, get("/api/v1/capabilities", "service-inference").status)
        assertEquals(HttpStatusCode.OK, get("/api/v1/auth/me", "service-inference").status)
        assertEquals(HttpStatusCode.OK, get("/api/v1/capacity", "service-usage").status)
        for (path in listOf("/api/v1/capacity", "/api/v1/service-keys", "/api/v1/members", "/api/v1/status", "/api/v1/policy/models", "/api/v1/future-admin", "/api/v1/members/other/capacity")) {
            val token = if (path == "/api/v1/capacity") "service-inference" else "service-all"
            assertEquals(HttpStatusCode.Forbidden, get(path, token).status, path)
        }
        for (path in listOf("/api/v1/auth/password", "/api/v1/service-keys", "/api/v1/accounts")) {
            assertEquals(HttpStatusCode.Forbidden, client.post(path) { bearerAuth("service-all"); contentType(ContentType.Application.Json); setBody("{}") }.status)
        }
        assertEquals(HttpStatusCode.Forbidden, client.get("/dashboard/service-keys") { header(HttpHeaders.Cookie, "teamcode_session=service-all") }.status)
        val inference = """{"model":"claude-test","stream":true,"max_tokens":100,"messages":[{"role":"user","content":"Hello"}]}"""
        for ((token, expected) in listOf("service-inference" to HttpStatusCode.OK, "service-usage" to HttpStatusCode.Forbidden, "service-guidance" to HttpStatusCode.Forbidden)) {
            assertEquals(expected, client.post("/v1/messages") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(inference) }.status)
        }
        assertEquals(1, inferences)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/directives/current") { bearerAuth("service-guidance") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/directives/current") { bearerAuth("service-inference") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/logout") { bearerAuth("service-inference") }.status)
    }
    @Test fun sdkAndDashboardCreateOneTimeSecretsWithoutMetadataLeakageAndRequireCsrf() = testApplication {
        val registry = Registry()
        application { configureGateway(gateway(), team = team(), serviceKeys = registry, publicOrigin = "http://localhost") }
        val sdk = TeamCodeClient("http://localhost", { "admin" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        val created = try {
            val created = sdk.createServiceKey(CreateServiceKeyRequest("<script>CI</script>", listOf("inference", "guidance"), 30))
            assertEquals(setOf("inference", "guidance"), created.metadata.scopes.toSet())
            assertFalse(created.toString().contains(created.accessToken))
            assertEquals(created.metadata, sdk.serviceKeys().single())
            val metadata = client.get("/api/v1/service-keys") { bearerAuth("admin") }.bodyAsText()
            assertFalse(metadata.contains(created.accessToken)); assertFalse(metadata.contains("tokenHash"))
            val dashboard = client.get("/dashboard/service-keys") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
            assertTrue(dashboard.contains("&lt;script&gt;CI&lt;/script&gt;"))
            assertFalse(dashboard.contains("<script>CI</script>")); assertFalse(dashboard.contains(created.accessToken))
            sdk.revokeServiceKey(created.metadata.id)
            assertTrue(sdk.serviceKeys().isEmpty())
            created
        } finally { sdk.close() }
        val form = "name=CI&scope=inference&days=30"
        for ((origin, csrf) in listOf("https://attacker.test" to "session", "http://localhost" to "wrong")) {
            assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/service-keys") {
                header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, origin)
                contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=$csrf&$form")
            }.status)
        }
        assertEquals(1, registry.creates)
        val once = client.post("/dashboard/service-keys") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session&$form")
        }
        assertEquals(HttpStatusCode.OK, once.status)
        assertTrue(once.bodyAsText().contains(registry.lastSecret))
        assertFalse(once.bodyAsText().contains(created.accessToken))
        assertEquals("no-store, no-transform", once.headers[HttpHeaders.CacheControl])
        val after = client.get("/dashboard/service-keys") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertFalse(after.contains(created.accessToken)); assertFalse(after.contains(registry.lastSecret))
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/service-keys") {
            bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"name":"CI","scopes":["admin"],"validDays":30}""")
        }.status)
    }
    @Test fun keysWithoutExpiryUseExplicitNullAndLegacyRequestsKeepTheirLifetime() = testApplication {
        val registry = Registry()
        application { configureGateway(gateway(), team = team(), serviceKeys = registry, publicOrigin = "http://localhost") }
        suspend fun create(body: String) = client.post("/api/v1/service-keys") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Created, create("""{"name":"old client","scopes":["inference"],"validDays":7}""").status)
        assertEquals(7, registry.lastDays)
        assertEquals(HttpStatusCode.Created, create("""{"name":"no field","scopes":["inference"]}""").status)
        assertEquals(30, registry.lastDays, "An omitted lifetime keeps the 30-day default, never 'no expiry'")
        val forever = create("""{"name":"ci","scopes":["inference"],"validDays":null}""")
        assertEquals(HttpStatusCode.Created, forever.status)
        assertNull(registry.lastDays)
        assertTrue(forever.bodyAsText().contains(""""neverExpires":true"""))
        assertTrue(client.get("/api/v1/service-keys") { bearerAuth("admin") }.bodyAsText().contains(""""expiresAt":${Long.MAX_VALUE}"""))
        assertEquals(HttpStatusCode.BadRequest, create("""{"name":"bad","scopes":["inference"],"validDays":0}""").status)
        val dashboard = client.get("/dashboard/service-keys") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertTrue(dashboard.contains("<td>never</td>")); assertTrue(dashboard.contains("""name="never""""))
        val form = client.post("/dashboard/service-keys") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session&name=form&scope=inference&days=30&never=1")
        }
        assertEquals(HttpStatusCode.OK, form.status); assertNull(registry.lastDays)
        val memberCreate = client.post("/api/v1/service-keys") { bearerAuth("member"); contentType(ContentType.Application.Json); setBody("""{"name":"m","scopes":["inference"],"validDays":null}""") }
        assertEquals(HttpStatusCode.BadRequest, memberCreate.status)
        assertFalse(client.get("/dashboard/service-keys") { header(HttpHeaders.Cookie, "teamcode_session=member") }.bodyAsText().contains("""name="never""""))
        val sdk = TeamCodeClient("http://localhost", { "admin" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        try {
            val created = sdk.createServiceKey(CreateServiceKeyRequest("sdk", listOf("inference"), null))
            assertTrue(created.metadata.neverExpires)
            assertTrue(sdk.serviceKeys().first { it.id == created.metadata.id }.neverExpires)
            sdk.revokeServiceKey(created.metadata.id)
            assertTrue(sdk.serviceKeys().none { it.id == created.metadata.id })
        } finally { sdk.close() }
    }
}
