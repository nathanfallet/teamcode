package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.cloud.SecretSealer
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.webhooks.*
import kotlin.test.*

class WebhookRoutesTest {
    private class Store : WebhookStore {
        val subscriptions = linkedMapOf<String, WebhookSubscription>()
        val deliveries = mutableListOf<WebhookDelivery>()
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun list(teamId: String) = subscriptions.values.toList()
        override suspend fun find(teamId: String, id: String) = subscriptions[id]
        override suspend fun create(actor: Principal, subscription: WebhookSubscription, sealedSecret: String): WebhookSubscription = subscription.also { subscriptions[it.id] = it }
        override suspend fun update(actor: Principal, id: String, expectedVersion: Int, url: String, types: Set<TeamEventType>, description: String, enabled: Boolean): WebhookSubscription {
            val current = subscriptions[id] ?: throw TeamFailure("not_found", 404, "Webhook not found.")
            if (current.version != expectedVersion) throw TeamFailure("stale_version", 409, "Changed.")
            return current.copy(url = url, types = types, description = description, enabled = enabled, version = expectedVersion + 1).also { subscriptions[id] = it }
        }
        override suspend fun rotateSecret(actor: Principal, id: String, sealedSecret: String) = subscriptions[id] ?: throw TeamFailure("not_found", 404, "Webhook not found.")
        override suspend fun delete(actor: Principal, id: String) { subscriptions.remove(id) ?: throw TeamFailure("not_found", 404, "Webhook not found.") }
        override suspend fun deliveries(teamId: String, id: String, limit: Int) = deliveries.filter { it.subscriptionId == id }
        override suspend fun enqueueTest(actor: Principal, id: String, payload: String): WebhookDelivery {
            subscriptions[id] ?: throw TeamFailure("not_found", 404, "Webhook not found.")
            return WebhookDelivery("t1", id, actor.teamId, null, "webhook.test", payload, DeliveryState.PENDING, 0, 5, null, null, 1, null, true).also { deliveries += it }
        }
        override suspend fun fanOut(subscriptions: Int, eventsPerSubscription: Int) = 0
        override suspend fun claimDue(limit: Int, leaseMillis: Long) = emptyList<DueDelivery>()
        override suspend fun record(id: String, state: DeliveryState, attempts: Int, nextAttemptAt: Long, result: SendResult, now: Long) = Unit
        override suspend fun disableFailing(limit: Int) = 0
        override suspend fun maintain(limit: Int) = 0
    }
    private val sealer = object : SecretSealer {
        override fun seal(plaintext: ByteArray, context: String) = "sealed"
        override fun open(envelope: String, context: String): ByteArray? = null
    }
    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = Unit
    }
    private fun team(): TeamService {
        val interactive = FakeTeam()
        return object : TeamService by interactive {
            override suspend fun authenticate(token: String): Principal? = when (token) {
                "admin-cloud" -> interactive.authenticate("admin")!!.copy(serviceScopes = setOf(ServiceScope.ADMIN_CLOUD))
                "admin-plain" -> interactive.authenticate("admin")!!.copy(serviceScopes = setOf(ServiceScope.CLOUD_WRITE))
                "monitor" -> interactive.authenticate("admin")!!.copy(serviceScopes = setOf(ServiceScope.TEAM_READ))
                "inference" -> interactive.authenticate("admin")!!.copy(serviceScopes = setOf(ServiceScope.INFERENCE))
                "member" -> Principal(Member("member-id", "member", Role.MEMBER, false, false), "member-session", "default")
                else -> interactive.authenticate(token)
            }
        }
    }
    private val body = """{"url":"https://hooks.example.test/x","types":["cloud.turn","run.stale"],"description":"support"}"""

    @Test fun adminsAndCloudAdminKeysManageWebhooksAndSecretsAreShownOnce() = testApplication {
        val store = Store()
        application { configureGateway(gateway, team = team(), webhooks = WebhookService(store, sealer)) }
        suspend fun call(method: HttpMethod, path: String, token: String, json: String? = null) = client.request(path) {
            this.method = method; bearerAuth(token); if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        val features = Serialization.json.decodeFromString<Capabilities>(call(HttpMethod.Get, "/api/v1/capabilities", "admin").bodyAsText()).features
        assertTrue("team.webhooks.v1" in features)

        for (token in listOf("member", "admin-plain", "monitor")) {
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/api/v1/webhooks", token).status, "$token does not read subscriptions")
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/webhooks", token, body).status, "$token does not create")
        }
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/api/v1/webhooks", "inference").status, "A key without cloud scopes never reaches them")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/webhooks").status)

        val created = call(HttpMethod.Post, "/api/v1/webhooks", "admin-cloud", body)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val view = Serialization.json.decodeFromString<CreatedWebhookView>(created.bodyAsText())
        assertTrue(view.secret.startsWith("whsec_")); assertEquals(listOf("cloud.turn", "run.stale"), view.webhook.types)
        val id = view.webhook.id
        for (path in listOf("/api/v1/webhooks", "/api/v1/webhooks/$id")) assertFalse("whsec_" in call(HttpMethod.Get, path, "admin").bodyAsText(), "$path never returns the secret")
        val rotated = Serialization.json.decodeFromString<CreatedWebhookView>(call(HttpMethod.Post, "/api/v1/webhooks/$id/rotate-secret", "admin").bodyAsText())
        assertNotEquals(view.secret, rotated.secret)

        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/v1/webhooks", "admin", """{"url":"http://x.example.test","types":["cloud.turn"]}""").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/v1/webhooks", "admin", """{"url":"https://x.example.test","types":["cloud.nope"]}""").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/v1/webhooks", "admin", """{"url":"https://x.example.test","types":[]}""").status)

        val update = """{"version":1,"url":"https://hooks.example.test/y","types":["run.stale"],"enabled":false}"""
        val updated = Serialization.json.decodeFromString<WebhookView>(call(HttpMethod.Put, "/api/v1/webhooks/$id", "admin", update).bodyAsText())
        assertEquals(listOf("run.stale"), updated.types); assertFalse(updated.enabled); assertEquals(2, updated.version)
        assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Put, "/api/v1/webhooks/$id", "admin", update).status, "The version being replaced is checked")

        val test = call(HttpMethod.Post, "/api/v1/webhooks/$id/test", "admin")
        assertEquals(HttpStatusCode.Accepted, test.status)
        assertEquals("webhook.test", Serialization.json.decodeFromString<WebhookDeliveryView>(test.bodyAsText()).eventType)
        assertEquals(1, Serialization.json.decodeFromString<List<WebhookDeliveryView>>(call(HttpMethod.Get, "/api/v1/webhooks/$id/deliveries", "admin").bodyAsText()).size)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/v1/webhooks/unknown", "admin").status)
        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/api/v1/webhooks/$id", "admin").status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/api/v1/webhooks/$id", "admin").status)
    }

    @Test fun theDashboardListsCreatesAndShowsTheSecretOnce() = testApplication {
        val store = Store()
        application { configureGateway(gateway, team = team(), webhooks = WebhookService(store, sealer), publicOrigin = "http://localhost") }
        val page = client.get("/dashboard/webhooks") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        assertEquals(HttpStatusCode.OK, page.status); assertTrue("Add a webhook" in page.bodyAsText())
        val csrf = FakeTeam().authenticate("admin")!!.tokenId
        val created = client.post("/dashboard/webhooks") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("csrf=$csrf&url=https%3A%2F%2Fhooks.example.test%2Fx&description=support&types=cloud.turn&types=run.ended")
        }
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        assertTrue("whsec_" in created.bodyAsText() && "shown once" in created.bodyAsText())
        assertEquals(setOf(TeamEventType.CLOUD_TURN, TeamEventType.RUN_ENDED), store.subscriptions.values.single().types)
        val again = client.get("/dashboard/webhooks/${store.subscriptions.keys.single()}") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertFalse("whsec_" in again); assertTrue("Send a test" in again)
        val member = client.get("/dashboard/webhooks") { header(HttpHeaders.Cookie, "teamcode_session=member") }
        assertEquals(HttpStatusCode.Forbidden, member.status)
    }
}
