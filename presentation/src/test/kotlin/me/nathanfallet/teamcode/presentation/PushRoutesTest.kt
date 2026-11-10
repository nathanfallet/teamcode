package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.notifications.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

/** The push routes decode, authorize and encode; the rules are the domain's. */
class PushRoutesTest {
    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = Unit
    }
    private val token = "d".repeat(40) + ":APA91b"
    private val publicKey = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded)

    private class Devices : PushDeviceStore {
        var token: String? = null
        override suspend fun currentDevice(actor: Principal) = PushDevice("d1", actor.member.id, "ios")
        override suspend fun register(deviceId: String, token: String, publicKey: String, platform: String?) { this.token = token }
        override suspend fun clear(deviceId: String, onlyToken: String?) { token = null }
        override suspend fun targets(teamId: String, memberIds: Set<String>) =
            token?.let { listOf(PushTarget("d1", memberIds.first(), it, KEY, "ios")) }.orEmpty()
        companion object { val KEY: String = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded) }
    }

    private class Settings : PushSettingsStore {
        var stored = PushSettings()
        var key: String? = null
        override suspend fun settings(actor: Principal) = stored
        override suspend fun save(actor: Principal, provider: PushProvider, relayUrl: String, serviceAccount: FirebaseServiceAccount?, version: Int): PushSettings {
            if (version != stored.version) throw TeamFailure("push_settings_conflict", 409, "Reload.")
            if (serviceAccount != null) key = serviceAccount.json
            stored = PushSettings(provider, relayUrl, serviceAccount?.projectId ?: stored.firebaseProjectId, serviceAccount?.clientEmail ?: stored.firebaseClientEmail, version + 1, 1)
            return stored
        }
        override suspend fun connections(teamIds: Set<String>) = emptyMap<String, PushConnection>()
    }

    private val account = """{"type":"service_account","project_id":"teamcode-app","private_key_id":"k1","private_key":"-----BEGIN PRIVATE KEY-----\nsynthetic\n-----END PRIVATE KEY-----\n","client_email":"push@teamcode-app.iam.gserviceaccount.com","token_uri":"https://oauth2.googleapis.com/token"}"""

    @Test fun anAppRegistersItsDeviceAndAnAdministratorChoosesTheProvider() = testApplication {
        val devices = Devices(); val settings = Settings()
        val sent = mutableListOf<PushMessage>()
        val channel = PushChannel(devices, { teams -> if (settings.stored.provider == PushProvider.OFF) emptyMap() else teams.associateWith { PushSender { sent += it; PushResult.SENT } } }, "http://localhost")
        application { configureGateway(gateway, team = FakeTeam(), push = PushRegistrationService(devices), pushAdmin = PushAdministrationService(settings, channel), publicOrigin = "http://localhost") }
        suspend fun call(method: HttpMethod, path: String, bearer: String, json: String? = null) = client.request(path) {
            this.method = method; bearerAuth(bearer); if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        assertTrue(Notifications.PUSH_CAPABILITY in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("member") }.bodyAsText()).features)

        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Put, "/api/v1/devices/current/push", "member", Serialization.json.encodeToString(DevicePushRequest(token, publicKey))).status)
        assertEquals(token, devices.token)
        val bad = call(HttpMethod.Put, "/api/v1/devices/current/push", "member", Serialization.json.encodeToString(DevicePushRequest("x", publicKey)))
        assertEquals("invalid_push_token", Serialization.json.decodeFromString<ApiError>(bad.bodyAsText()).error.type)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Put, "/api/v1/devices/current/push", "service", Serialization.json.encodeToString(DevicePushRequest(token, publicKey))).status)

        // Administrators only.
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/api/v1/notifications/push/settings", "member").status)
        val initial = Serialization.json.decodeFromString<PushSettingsView>(call(HttpMethod.Get, "/api/v1/notifications/push/settings", "admin").bodyAsText())
        assertEquals(PushSettingsView("off", PushAdministrationService.DEFAULT_RELAY_URL, version = 0), initial)
        val test = call(HttpMethod.Post, "/api/v1/notifications/push/test", "admin")
        assertEquals("push_off", Serialization.json.decodeFromString<ApiError>(test.bodyAsText()).error.type)

        val saved = call(HttpMethod.Put, "/api/v1/notifications/push/settings", "admin", Serialization.json.encodeToString(SavePushSettingsRequest("firebase", null, account, 0)))
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        val view = Serialization.json.decodeFromString<PushSettingsView>(saved.bodyAsText())
        assertEquals("firebase", view.provider); assertEquals("teamcode-app", view.firebaseProjectId)
        assertFalse("PRIVATE" in saved.bodyAsText(), "the key is never returned")
        assertEquals(account, settings.key)
        assertEquals("invalid_push_settings", Serialization.json.decodeFromString<ApiError>(call(HttpMethod.Put, "/api/v1/notifications/push/settings", "admin",
            Serialization.json.encodeToString(SavePushSettingsRequest("pigeon", null, null, 1))).bodyAsText()).error.type)
        assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Put, "/api/v1/notifications/push/settings", "admin", Serialization.json.encodeToString(SavePushSettingsRequest("relay", null, null, 0))).status)

        val result = Serialization.json.decodeFromString<PushTestView>(call(HttpMethod.Post, "/api/v1/notifications/push/test", "admin").bodyAsText())
        assertEquals(PushTestView(true, listOf(PushTestResultView("sent"))), result)
        assertEquals(1, sent.size)

        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/api/v1/devices/current/push", "member").status)
        assertNull(devices.token)
    }

    @Test fun theDashboardPageSavesTheProviderWithoutShowingTheKey() = testApplication {
        val settings = Settings()
        val channel = PushChannel(Devices(), { emptyMap() }, "http://localhost")
        application { configureGateway(gateway, team = FakeTeam(), pushAdmin = PushAdministrationService(settings, channel), publicOrigin = "http://localhost") }
        val csrf = FakeTeam().authenticate("admin")!!.tokenId
        suspend fun form(path: String, body: String) = client.post(path) {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=$csrf&$body")
        }
        val page = client.get(PUSH_SETTINGS_PATH) { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertTrue("Push notifications are off" in page && "href=\"$PUSH_SETTINGS_PATH\"" in page, page)
        assertFalse(PUSH_SETTINGS_PATH in client.get("/dashboard/settings") { header(HttpHeaders.Cookie, "teamcode_session=member") }.bodyAsText(), "members do not see the link")
        assertEquals(HttpStatusCode.Forbidden, client.get(PUSH_SETTINGS_PATH) { header(HttpHeaders.Cookie, "teamcode_session=member") }.status)

        val saved = form(PUSH_SETTINGS_PATH, "version=0&provider=firebase&relayUrl=&firebaseServiceAccount=${URLEncoder.encode(account, "UTF-8")}")
        assertEquals(HttpStatusCode.Found, saved.status, saved.bodyAsText())
        assertEquals(account, settings.key)
        val shown = client.get(PUSH_SETTINGS_PATH) { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertTrue("your Firebase project</strong> <code>teamcode-app</code>" in shown, shown)
        assertFalse("synthetic" in shown, "the key is never shown")
        // An empty key field keeps the stored one.
        assertEquals(HttpStatusCode.Found, form(PUSH_SETTINGS_PATH, "version=1&provider=relay&relayUrl=https%3A%2F%2Frelay.example.org&firebaseServiceAccount=").status)
        assertEquals(PushProvider.RELAY, settings.stored.provider); assertEquals("https://relay.example.org", settings.stored.relayUrl)
        val tested = form("$PUSH_SETTINGS_PATH/test", "")
        assertEquals(HttpStatusCode.Conflict, tested.status)
        assertTrue("Choose a push provider" in tested.bodyAsText(), "the test reports why nothing was sent")
    }
}
