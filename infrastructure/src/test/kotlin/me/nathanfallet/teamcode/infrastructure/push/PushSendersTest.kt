package me.nathanfallet.teamcode.infrastructure.push

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.nathanfallet.flareon.core.credentials.GoogleCredentials
import me.nathanfallet.flareon.core.credentials.OAuth2TokenResponse
import me.nathanfallet.teamcode.domain.notifications.*
import me.nathanfallet.teamcode.infrastructure.secrets.CipherSecretSealer
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import kotlin.test.*

/** The relay and Firebase senders against fake HTTP servers: no provider is ever called. */
class PushSendersTest {
    private val message = PushMessage("t".repeat(40), "ios", "c2VhbGVk", "0123456789abcdef0123456789abcdef", 3600)
    private val sealer = CipherSecretSealer(CredentialCipher(ByteArray(32) { 7 }))

    private class Identities : RelayIdentityStore {
        val rows = mutableMapOf<String, RelayIdentity>()
        override suspend fun load(relayUrl: String) = rows[relayUrl]
        override suspend fun create(relayUrl: String, identity: RelayIdentity) = rows.getOrPut(relayUrl) { identity }
        override suspend fun setInstance(relayUrl: String, publicKey: String, instanceId: String?) {
            rows[relayUrl]?.takeIf { it.publicKey == publicKey }?.let { rows[relayUrl] = it.copy(instanceId = instanceId) }
        }
    }

    private fun HttpRequestData.text() = (body as TextContent).text

    /** The relay's check: the Ed25519 signature over the documented string, with the key it registered. */
    private fun verify(request: HttpRequestData, publicKey: String): Boolean {
        val raw = Base64.getUrlDecoder().decode(publicKey)
        val spki = Base64.getDecoder().decode("MCowBQYDK2VwAyEA") + raw
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
        val signing = "POST\n${request.url.encodedPath}\n${request.headers["TeamCode-Timestamp"]}\n${PushPayloads.sha256Hex(request.text().encodeToByteArray())}"
        return Signature.getInstance("Ed25519").run {
            initVerify(key); update(signing.encodeToByteArray()); verify(Base64.getUrlDecoder().decode(request.headers["TeamCode-Signature"]))
        }
    }

    @Test
    fun theRelayKnowsThisServerByOneSignedKey() = runTest {
        val identities = Identities()
        var registrations = 0
        var publicKey = ""
        val requests = mutableListOf<HttpRequestData>()
        var answer: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to """{"status":"sent"}""" }
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/instances" -> {
                    registrations++
                    publicKey = Json.parseToJsonElement(request.text()).jsonObject["publicKey"]!!.jsonPrimitive.content
                    respond("""{"instanceId":"inst-1"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> { requests += request; val (status, body) = answer(); respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
            }
        }
        val sender = RelayPushSender("https://relay.example.org/", identities, sealer, HttpClient(engine), clock = { 1_700_000_000_000 })

        assertEquals(PushResult.SENT, sender.send(message))
        assertEquals(PushResult.SENT, sender.send(message))
        assertEquals(1, registrations, "registered once, then the instance id is kept")
        assertEquals(32, Base64.getUrlDecoder().decode(publicKey).size)
        val sent = requests.first()
        assertEquals("https://relay.example.org/v1/send", sent.url.toString())
        assertEquals("inst-1", sent.headers["TeamCode-Instance"])
        assertEquals("1700000000", sent.headers["TeamCode-Timestamp"])
        assertTrue(verify(sent, publicKey))
        val body = Json.parseToJsonElement(sent.text()).jsonObject
        assertEquals(setOf("token", "platform", "payload", "collapseKey", "ttlSeconds"), body.keys)
        assertEquals(message.payload, body["payload"]!!.jsonPrimitive.content)
        // The private key is stored sealed, never in clear.
        val stored = identities.rows.getValue("https://relay.example.org")
        assertEquals("inst-1", stored.instanceId)
        assertNotNull(sealer.open(stored.sealedPrivateKey, RelayPushSender.context("https://relay.example.org")))

        // Another replica reads the same identity and registers nothing.
        val replica = RelayPushSender("https://relay.example.org", identities, sealer, HttpClient(engine))
        assertEquals(PushResult.SENT, replica.send(message))
        assertEquals(1, registrations)
        assertTrue(verify(requests.last(), publicKey))

        answer = { HttpStatusCode.OK to """{"status":"unregistered"}""" }; assertEquals(PushResult.UNREGISTERED, sender.send(message))
        answer = { HttpStatusCode.OK to """{"status":"retry","retryAfterSeconds":30}""" }; assertEquals(PushResult.Status.RETRY, sender.send(message).status)
        answer = { HttpStatusCode.OK to """{"status":"rejected"}""" }; assertEquals(PushResult.Status.REJECTED, sender.send(message).status)
        answer = { HttpStatusCode.OK to "not json" }; assertEquals(PushResult.Status.REJECTED, sender.send(message).status)
        answer = { HttpStatusCode.TooManyRequests to "" }; assertEquals(PushResult.retry("relay_rate_limited"), sender.send(message))
        answer = { HttpStatusCode.BadGateway to "" }; assertEquals(PushResult.retry("relay_unavailable"), sender.send(message))
        answer = { HttpStatusCode.Forbidden to "" }; assertEquals(PushResult.rejected("relay_instance_blocked"), sender.send(message))
        answer = { HttpStatusCode.BadRequest to "" }; assertEquals(PushResult.rejected("relay_refused"), sender.send(message))

        // The relay forgot this server: the next push registers the same key again.
        answer = { HttpStatusCode.Unauthorized to "" }; assertEquals(PushResult.retry("relay_unknown_instance"), sender.send(message))
        assertNull(identities.rows.getValue("https://relay.example.org").instanceId)
        answer = { HttpStatusCode.OK to """{"status":"sent"}""" }; assertEquals(PushResult.SENT, sender.send(message))
        assertEquals(2, registrations)
        assertTrue(verify(requests.last(), publicKey), "the same key")
    }

    @Test
    fun anUnreachableRelayIsARetry() = runTest {
        val down = RelayPushSender("https://relay.example.org", Identities(), sealer, HttpClient(MockEngine { throw java.io.IOException("down") }))
        assertEquals(PushResult.retry("relay_registration"), down.send(message))
        val identities = Identities()
        var registered = false
        val flaky = RelayPushSender("https://relay.example.org", identities, sealer, HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/v1/instances") { registered = true; respond("""{"instanceId":"i"}""", HttpStatusCode.OK) } else throw java.io.IOException("reset")
        }))
        assertEquals(PushResult.retry("relay_unreachable"), flaky.send(message))
        assertTrue(registered)
        // An identity sealed with another key cannot be opened: nothing is sent, and the stored key is not replaced.
        val other = RelayPushSender("https://relay.example.org", identities, CipherSecretSealer(CredentialCipher(ByteArray(32) { 9 })), HttpClient(MockEngine { error("no call") }))
        assertEquals(PushResult.retry("relay_identity"), other.send(message))
    }

    private class FakeCredentials : GoogleCredentials() {
        override suspend fun fetchAccessToken() = OAuth2TokenResponse("access", "Bearer", 3600)
        override suspend fun fetchProjectId() = "teamcode-app"
    }

    private fun firebase(status: HttpStatusCode, seen: MutableList<HttpRequestData> = mutableListOf()) = FirebasePushSender(FakeCredentials(), HttpClient(MockEngine { request ->
        seen += request
        respond(if (status.isSuccess()) """{"name":"projects/teamcode-app/messages/1"}""" else """{"error":{"code":${status.value}}}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
    }) {
        expectSuccess = true
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
    })

    @Test
    fun aTeamsOwnFirebaseProjectGetsTheRelaysMessage() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        assertEquals(PushResult.SENT, firebase(HttpStatusCode.OK, seen).send(message))
        val request = seen.single()
        assertEquals("/v1/projects/teamcode-app/messages:send", request.url.encodedPath)
        assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
        val sent = Json.parseToJsonElement(String((request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes())).jsonObject["message"]!!.jsonObject
        assertEquals(message.token, sent["token"]!!.jsonPrimitive.content)
        assertEquals(mapOf("p" to message.payload, "v" to "1"), sent["data"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })
        val apns = sent["apns"]!!.jsonObject
        assertEquals(message.collapseKey, apns["headers"]!!.jsonObject["apns-collapse-id"]!!.jsonPrimitive.content)
        val aps = apns["payload"]!!.jsonObject["aps"]!!.jsonObject
        assertEquals(1, aps["mutable-content"]!!.jsonPrimitive.int)
        assertEquals("New activity in TeamCode", aps["alert"]!!.jsonObject["body"]!!.jsonPrimitive.content)

        val android = FirebasePushSender.message(message.copy(platform = "android"))
        assertNull(android.apns); assertNull(android.notification)
        assertEquals(message.collapseKey, android.android!!.collapseKey)
        assertEquals("3600s", android.android!!.ttl)

        assertEquals(PushResult.UNREGISTERED, firebase(HttpStatusCode.NotFound).send(message))
        assertEquals(PushResult.Status.RETRY, firebase(HttpStatusCode.TooManyRequests).send(message).status)
        assertEquals(PushResult.Status.RETRY, firebase(HttpStatusCode.ServiceUnavailable).send(message).status)
        assertEquals(PushResult.rejected("fcm_400"), firebase(HttpStatusCode.BadRequest).send(message))
        assertEquals(PushResult.rejected("fcm_403"), firebase(HttpStatusCode.Forbidden).send(message))
    }

    @Test
    fun sendersFollowTheTeamsSettings() = runTest {
        val connections = mutableMapOf<String, PushConnection>()
        val store = object : PushSettingsStore {
            override suspend fun settings(actor: me.nathanfallet.teamcode.domain.team.Principal) = PushSettings()
            override suspend fun save(actor: me.nathanfallet.teamcode.domain.team.Principal, provider: PushProvider, relayUrl: String, serviceAccount: FirebaseServiceAccount?, version: Int) = PushSettings()
            override suspend fun connections(teamIds: Set<String>) = connections.filterKeys { it in teamIds }
        }
        class Closable(val name: String) : PushSender, AutoCloseable {
            var closed = false
            override suspend fun send(message: PushMessage) = PushResult.SENT
            override fun close() { closed = true }
        }
        val made = mutableListOf<Closable>()
        val senders = CachedPushSenders(store, relay = { url -> Closable("relay:$url").also { made += it } }, firebase = { json -> Closable("firebase:$json").also { made += it } })

        assertEquals(emptyMap(), senders.senders(setOf("a", "b")))
        connections["a"] = PushConnection("a", PushProvider.RELAY, "https://relay.example.org", null, 1)
        connections["b"] = PushConnection("b", PushProvider.RELAY, "https://relay.example.org", null, 1)
        val shared = senders.senders(setOf("a", "b"))
        assertSame(shared["a"], shared["b"], "one relay sender per relay")

        connections["b"] = PushConnection("b", PushProvider.FIREBASE, "https://relay.example.org", "{key-1}", 2)
        val own = senders.senders(setOf("b"))["b"] as Closable
        assertEquals("firebase:{key-1}", own.name)
        assertSame(own, senders.senders(setOf("b"))["b"], "kept while the settings are unchanged")
        connections["b"] = PushConnection("b", PushProvider.FIREBASE, "https://relay.example.org", "{key-2}", 3)
        assertEquals("firebase:{key-2}", (senders.senders(setOf("b"))["b"] as Closable).name)
        assertTrue(own.closed, "the replaced key's sender is closed")
        connections.remove("b")
        assertEquals(emptyMap(), senders.senders(setOf("b")))
        assertTrue(made.last().closed, "push turned off closes it")
        senders.close()
        assertTrue(made.all { it.closed })
    }
}
