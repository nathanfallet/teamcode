package me.nathanfallet.teamcode

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.notifications.*
import me.nathanfallet.teamcode.infrastructure.push.RelayIdentity
import me.nathanfallet.teamcode.infrastructure.push.RelayIdentityStore
import me.nathanfallet.teamcode.infrastructure.push.RelayPushSender
import me.nathanfallet.teamcode.infrastructure.secrets.CipherSecretSealer
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import kotlin.test.*

/**
 * The server modules may not depend on the API module, so the relay client repeats its `PushRelay` constants: this test
 * holds the two together, decoding what the server sends with the models the relay and the apps use.
 */
class PushRelayContractTest {
    @Test
    fun theRelayClientSpeaksTheApiModels() = testApplication {
        val received = mutableListOf<Pair<Headers, PushRelaySendRequest>>()
        routing {
            post(PushRelay.REGISTER_PATH) {
                val request = Serialization.json.decodeFromString<PushRelayRegisterRequest>(call.receiveText())
                assertEquals(32, Base64.getUrlDecoder().decode(request.publicKey).size)
                call.respondText(Serialization.json.encodeToString(PushRelayRegisterResponse("inst-1")), ContentType.Application.Json)
            }
            post(PushRelay.SEND_PATH) {
                received += call.request.headers to Serialization.json.decodeFromString<PushRelaySendRequest>(call.receiveText())
                call.respondText(Serialization.json.encodeToString(PushRelaySendResponse("sent")), ContentType.Application.Json)
            }
        }
        val identities = object : RelayIdentityStore {
            var row: RelayIdentity? = null
            override suspend fun load(relayUrl: String) = row
            override suspend fun create(relayUrl: String, identity: RelayIdentity) = row ?: identity.also { row = it }
            override suspend fun setInstance(relayUrl: String, publicKey: String, instanceId: String?) { row = row?.copy(instanceId = instanceId) }
        }
        val sender = RelayPushSender("http://localhost", identities, CipherSecretSealer(CredentialCipher(ByteArray(32) { 3 })), client)
        assertEquals(PushResult.SENT, sender.send(PushMessage("t".repeat(40), "android", "YmxvYg==", "abc", 3600)))
        val (headers, request) = received.single()
        assertEquals(PushRelaySendRequest("t".repeat(40), "android", "YmxvYg==", "abc", 3600), request)
        assertEquals("inst-1", headers[PushRelay.INSTANCE_HEADER])
        assertNotNull(headers[PushRelay.TIMESTAMP_HEADER]); assertNotNull(headers[PushRelay.SIGNATURE_HEADER])
        assertEquals(PushRelay.signingString("post", "/p", 1, "h"), RelayPushSender.signingString("POST", "/p", 1, "h"))
    }

    @Test
    fun thePayloadIsThePushPayloadOfTheApi() {
        val notification = Notification("n1", "team", "alice", NotificationKind.TURN_FAILED, "Turn failed", "Fix the login", "s1", null, "turn:s1", 1_000, 1)
        val payload = Serialization.json.decodeFromString<PushPayload>(PushPayloads.plaintext(notification, "https://teamcode.example.org"))
        assertEquals(PushPayload(1, "n1", "https://teamcode.example.org", "alice", "turn_failed", "Turn failed", "Fix the login", "s1", null, 1_000), payload)
        // A long body still fits the relay's payload limit once encrypted.
        val key = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded)
        val long = notification.copy(title = "€".repeat(NotificationRules.MAX_TITLE), body = "€".repeat(NotificationRules.MAX_BODY))
        assertTrue(PushPayloads.encrypt(key, PushPayloads.plaintext(long, "https://" + "o".repeat(200))).length <= PushRelay.MAX_PAYLOAD_CHARS)
    }
}
