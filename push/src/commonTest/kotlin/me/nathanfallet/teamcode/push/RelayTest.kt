package me.nathanfallet.teamcode.push

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.io.encoding.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.nathanfallet.flareon.core.credentials.GoogleCredentials
import me.nathanfallet.flareon.core.credentials.OAuth2TokenResponse
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import kotlin.test.*

class RelayTest {
    private val crypto = CryptographyProvider.Default
    private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val request = PushRelaySendRequest("t".repeat(40), "ios", "c2VhbGVkIHBheWxvYWQgYmxvYg==", "0123456789abcdef", 3600)

    private class Instance(val id: String, val sign: suspend (ByteArray) -> ByteArray)

    private suspend fun instance(): Instance {
        val pair = crypto.get(EdDSA).keyPairGenerator(EdDSA.Curve.Ed25519).generateKey()
        val raw = pair.publicKey.encodeToByteArray(EdDSA.PublicKey.Format.RAW)
        val signer = pair.privateKey.signatureGenerator()
        return Instance(urlSafe.encode(raw)) { signer.generateSignature(it) }
    }

    private suspend fun signedHeaders(instance: Instance, body: ByteArray, at: Long): Triple<String, String, String> {
        val hash = Relay.hex(crypto.get(SHA256).hasher().hash(body))
        val signature = instance.sign(PushRelay.signingString("POST", PushRelay.SEND_PATH, at, hash).encodeToByteArray())
        return Triple(instance.id, at.toString(), urlSafe.encode(signature))
    }

    private fun body(request: PushRelaySendRequest = this.request) = Serialization.json.encodeToString(request).encodeToByteArray()
    private val parse: (ByteArray) -> PushRelaySendRequest? = { runCatching { Serialization.json.decodeFromString<PushRelaySendRequest>(it.decodeToString()) }.getOrNull() }

    private class Fcm(var answer: PushRelaySendResponse = PushRelaySendResponse("sent")) : FcmSender {
        val sent = mutableListOf<PushRelaySendRequest>()
        override suspend fun send(request: PushRelaySendRequest): PushRelaySendResponse { sent += request; return answer }
    }

    @Test
    fun aSignedRequestIsSentOnceAndAForgedOneNever() = runTest {
        val fcm = Fcm()
        var clock = 1_700_000_000L
        val relay = Relay(fcm, now = { clock })
        val server = instance()
        assertEquals(server.id, relay.register(server.id), "a key is its own id")
        assertNull(relay.register("not-a-key"))
        assertNull(relay.register(urlSafe.encode(ByteArray(31))))

        val body = body()
        val (id, at, signature) = signedHeaders(server, body, clock)
        assertEquals(RelayAnswer(200, PushRelaySendResponse("sent")), relay.send(id, at, signature, body, parse))
        assertEquals(listOf(request), fcm.sent)
        // The same request again is answered without a second push.
        assertEquals(200, relay.send(id, at, signature, body, parse).status)
        assertEquals(1, fcm.sent.size)

        // Another body under the signature, another instance's key, a stale timestamp, no signature: refused.
        val other = body(request.copy(token = "u".repeat(40)))
        assertEquals("bad_signature", relay.send(id, at, signature, other, parse).body.error)
        assertEquals("bad_signature", relay.send(instance().id, at, signature, body, parse).body.error)
        assertEquals(401, relay.send(id, at, null, body, parse).status)
        assertEquals(401, relay.send("unknown", at, signature, body, parse).status)
        val (_, oldAt, oldSignature) = signedHeaders(server, other, clock - PushRelay.MAX_SKEW_SECONDS - 1)
        assertEquals("stale_request", relay.send(id, oldAt, oldSignature, other, parse).body.error)
        clock += 1
        assertEquals(1, fcm.sent.size)
    }

    @Test
    fun aBlockedInstanceAndAnInvalidRequestAreRefused() = runTest {
        val blocked = instance()
        val fcm = Fcm()
        val relay = Relay(fcm, blocked = setOf(blocked.id), now = { 100 })
        val body = body()
        val (id, at, signature) = signedHeaders(blocked, body, 100)
        assertEquals(RelayAnswer(403, PushRelaySendResponse("rejected", error = "instance_blocked")), relay.send(id, at, signature, body, parse))

        val server = instance()
        suspend fun refusal(request: PushRelaySendRequest): String? {
            val bytes = body(request); val (i, t, s) = signedHeaders(server, bytes, 100)
            return relay.send(i, t, s, bytes, parse).also { assertEquals(400, it.status) }.body.error
        }
        assertEquals("invalid_token", refusal(request.copy(token = "short")))
        assertEquals("invalid_platform", refusal(request.copy(platform = "web")))
        assertEquals("invalid_payload", refusal(request.copy(payload = "x".repeat(PushRelay.MAX_PAYLOAD_CHARS + 1))))
        assertEquals("invalid_collapse_key", refusal(request.copy(collapseKey = "wait:w1")))
        assertEquals("invalid_ttl", refusal(request.copy(ttlSeconds = Relay.MAX_TTL_SECONDS + 1)))
        val garbage = "{".encodeToByteArray(); val (i, t, s) = signedHeaders(server, garbage, 100)
        assertEquals("invalid_request", relay.send(i, t, s, garbage, parse).body.error)
        assertEquals(emptyList(), fcm.sent)
    }

    @Test
    fun quotasHoldPerInstancePerTokenAndInTotal() = runTest {
        var clock = 6_000L
        val fcm = Fcm()
        val relay = Relay(fcm, limits = RelayLimits(perInstance = 3, perToken = 2, total = 100), now = { clock })
        val server = instance()
        suspend fun send(token: String): RelayAnswer {
            val bytes = body(request.copy(token = token)); val (i, t, s) = signedHeaders(server, bytes, clock)
            clock += 1 // A new timestamp, so the replay guard does not answer for them.
            return relay.send(i, t, s, bytes, parse)
        }
        assertEquals(200, send("a".repeat(30)).status)
        assertEquals(200, send("a".repeat(30)).status)
        val token = send("a".repeat(30))
        assertEquals(429, token.status); assertEquals("retry", token.body.status); assertNotNull(token.retryAfterSeconds)
        assertEquals(200, send("b".repeat(30)).status, "a refusal counted for nothing")
        assertEquals(429, send("c".repeat(30)).status, "the instance's quota")
        clock += 60
        assertEquals(200, send("c".repeat(30)).status, "a new minute")
    }

    @Test
    fun aProviderFailureIsARetry() = runTest {
        val relay = Relay({ error("down") }, now = { 0 })
        val server = instance()
        val bytes = body(); val (i, t, s) = signedHeaders(server, bytes, 0)
        assertEquals(PushRelaySendResponse("retry", error = "fcm_unreachable"), relay.send(i, t, s, bytes, parse).body)
    }

    @Test
    fun theHttpSurfaceSpeaksTheProtocol() = testApplication {
        val fcm = Fcm()
        val relay = Relay(fcm)
        application { relayModule(relay) }
        val server = instance()
        val registered = client.post(PushRelay.REGISTER_PATH) { contentType(ContentType.Application.Json); setBody(Serialization.json.encodeToString(PushRelayRegisterRequest(server.id))) }
        assertEquals(PushRelayRegisterResponse(server.id), Serialization.json.decodeFromString(registered.bodyAsText()))
        assertEquals(HttpStatusCode.BadRequest, client.post(PushRelay.REGISTER_PATH) { setBody("""{"publicKey":"nope"}""") }.status)

        val bytes = body(); val (i, t, s) = signedHeaders(server, bytes, kotlin.time.Clock.System.now().epochSeconds)
        val sent = client.post(PushRelay.SEND_PATH) {
            header(PushRelay.INSTANCE_HEADER, i); header(PushRelay.TIMESTAMP_HEADER, t); header(PushRelay.SIGNATURE_HEADER, s)
            contentType(ContentType.Application.Json); setBody(bytes)
        }
        assertEquals(HttpStatusCode.OK, sent.status)
        assertEquals(PushRelaySendResponse("sent"), Serialization.json.decodeFromString(sent.bodyAsText()))
        assertEquals(HttpStatusCode.Unauthorized, client.post(PushRelay.SEND_PATH) { setBody(bytes) }.status)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post(PushRelay.SEND_PATH) { setBody(ByteArray(MAX_BODY_BYTES + 1)) }.status)
        assertEquals("ok", client.get("/healthz").bodyAsText())
        assertEquals(1, fcm.sent.size)
    }

    private class FakeCredentials : GoogleCredentials() {
        override suspend fun fetchAccessToken() = OAuth2TokenResponse("access", "Bearer", 3600)
        override suspend fun fetchProjectId() = "teamcode-push"
    }

    private fun firebase(status: HttpStatusCode, seen: MutableList<HttpRequestData> = mutableListOf(), headers: Headers = headersOf()) =
        FirebaseFcm(FakeCredentials(), HttpClient(MockEngine { request ->
            seen += request
            respond(if (status.isSuccess()) """{"name":"projects/teamcode-push/messages/1"}""" else """{"error":{"code":${status.value}}}""", status,
                Headers.build { appendAll(headers); append(HttpHeaders.ContentType, "application/json") })
        }) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        })

    @Test
    fun fcmAnswersBecomeRelayStatuses() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        assertEquals(PushRelaySendResponse("sent"), firebase(HttpStatusCode.OK, seen).send(request))
        assertEquals("/v1/projects/teamcode-push/messages:send", seen.single().url.encodedPath)
        assertEquals("Bearer access", seen.single().headers[HttpHeaders.Authorization])
        assertEquals("unregistered", firebase(HttpStatusCode.NotFound).send(request).status)
        val limited = firebase(HttpStatusCode.TooManyRequests, headers = headersOf(HttpHeaders.RetryAfter, "30")).send(request)
        assertEquals(PushRelaySendResponse("retry", 30, "fcm_rate_limited"), limited)
        assertEquals("retry", firebase(HttpStatusCode.ServiceUnavailable).send(request).status)
        assertEquals(PushRelaySendResponse("retry", error = "fcm_auth"), firebase(HttpStatusCode.Unauthorized).send(request))
        assertEquals(PushRelaySendResponse("rejected", error = "fcm_400"), firebase(HttpStatusCode.BadRequest).send(request))

        val ios = FirebaseFcm.message(request)
        assertEquals(mapOf("p" to request.payload, "v" to "1"), ios.data)
        assertEquals(1, ios.apns!!.payload!!.aps.mutableContent)
        assertEquals(PushRelay.GENERIC_BODY, ios.apns!!.payload!!.aps.alert!!.body)
        assertEquals(request.collapseKey, ios.apns!!.headers!!["apns-collapse-id"])
        assertNull(ios.notification, "no visible notification with the text: the app decrypts it")
        val android = FirebaseFcm.message(request.copy(platform = "android"))
        assertNull(android.apns); assertNull(android.notification)
        assertEquals("3600s", android.android!!.ttl)
    }
}
