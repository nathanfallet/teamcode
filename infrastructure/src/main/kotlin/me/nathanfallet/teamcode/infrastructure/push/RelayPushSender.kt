package me.nathanfallet.teamcode.infrastructure.push

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.cloud.SecretSealer
import me.nathanfallet.teamcode.domain.notifications.*

/** This server's identity at a relay: [instanceId] is null until the relay answered the registration. */
data class RelayIdentity(val publicKey: String, val sealedPrivateKey: String, val instanceId: String?)

/** Where the identity is kept: shared persistence, so every replica signs with the same key. */
interface RelayIdentityStore {
    suspend fun load(relayUrl: String): RelayIdentity?
    /** Keeps [identity] unless one exists already; returns the one in force. */
    suspend fun create(relayUrl: String, identity: RelayIdentity): RelayIdentity
    suspend fun setInstance(relayUrl: String, publicKey: String, instanceId: String?)
}

/**
 * Sends through the TeamCode Push relay, which holds the Firebase project of the published apps. The relay knows this
 * server by an Ed25519 public key, registered at the first push; every request is signed (docs/notifications.md,
 * "Relay protocol"). It sees a token and an encrypted blob, never a member, a session or the text. The wire format is the
 * `PushRelay` models of the API module; a test of the app module holds the two together.
 */
class RelayPushSender(
    relayUrl: String, private val identities: RelayIdentityStore, private val sealer: SecretSealer,
    private val http: HttpClient = defaultClient(), private val clock: () -> Long = System::currentTimeMillis,
) : PushSender, AutoCloseable {
    private val base = relayUrl.trimEnd('/')
    private val mutex = Mutex()
    private var cached: Signer? = null

    private class Signer(val instanceId: String, val publicKey: String, val sign: (ByteArray) -> ByteArray)

    override suspend fun send(message: PushMessage): PushResult {
        val signer = try { signer() } catch (error: CancellationException) { throw error } catch (_: Exception) { return PushResult.retry("relay_registration") }
            ?: return PushResult.retry("relay_identity")
        val body = buildJsonObject {
            put("token", message.token); put("platform", message.platform); put("payload", message.payload)
            put("collapseKey", message.collapseKey); put("ttlSeconds", message.ttlSeconds)
        }.toString()
        val response = try { signedPost(signer, SEND_PATH, body) } catch (error: CancellationException) { throw error } catch (_: Exception) { return PushResult.retry("relay_unreachable") }
        return when (response.status.value) {
            in 200..299 -> when (statusOf(response)) {
                "sent" -> PushResult.SENT
                "unregistered" -> PushResult.UNREGISTERED
                "retry" -> PushResult.retry("provider_unavailable")
                else -> PushResult.rejected("relay_rejected")
            }
            // The relay no longer knows this key (its registry was reset): register again at the next push.
            401 -> { forget(signer); PushResult.retry("relay_unknown_instance") }
            403 -> PushResult.rejected("relay_instance_blocked")
            408, 429 -> PushResult.retry("relay_rate_limited")
            in 500..599 -> PushResult.retry("relay_unavailable")
            else -> PushResult.rejected("relay_refused")
        }
    }

    private suspend fun statusOf(response: HttpResponse) =
        try { (Json.parseToJsonElement(response.bodyAsText()) as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }

    /** The key pair, made once for all replicas, and the instance id, asked of the relay once per key. */
    private suspend fun signer(): Signer? = mutex.withLock {
        cached?.let { return@withLock it }
        val identity = identities.load(base) ?: identities.create(base, newIdentity())
        val privateKey = sealer.open(identity.sealedPrivateKey, context(base)) ?: return@withLock null
        val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(privateKey))
        val sign: (ByteArray) -> ByteArray = { bytes -> Signature.getInstance("Ed25519").run { initSign(key); update(bytes); sign() } }
        // The relay answers the same id for the same key, so replicas registering together agree.
        val instanceId = identity.instanceId ?: register(identity.publicKey).also { identities.setInstance(base, identity.publicKey, it) }
        Signer(instanceId, identity.publicKey, sign).also { cached = it }
    }

    private suspend fun forget(signer: Signer) = mutex.withLock {
        if (cached === signer) { cached = null; identities.setInstance(base, signer.publicKey, null) }
    }

    private suspend fun register(publicKey: String): String {
        val response = http.post("$base$REGISTER_PATH") {
            contentType(ContentType.Application.Json); setBody(buildJsonObject { put("publicKey", publicKey) }.toString())
        }
        check(response.status.isSuccess()) { "relay registration refused" }
        return (Json.parseToJsonElement(response.bodyAsText()) as JsonObject)["instanceId"]!!.jsonPrimitive.content
            .also { require(it.length in 1..64 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' }) }
    }

    private suspend fun signedPost(signer: Signer, path: String, body: String): HttpResponse {
        val timestamp = clock() / 1000
        val signing = signingString("POST", path, timestamp, PushPayloads.sha256Hex(body.encodeToByteArray()))
        return http.post("$base$path") {
            contentType(ContentType.Application.Json)
            header(INSTANCE_HEADER, signer.instanceId); header(TIMESTAMP_HEADER, timestamp.toString())
            header(SIGNATURE_HEADER, Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign(signing.encodeToByteArray())))
            setBody(body)
        }
    }

    private fun newIdentity(): RelayIdentity {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        // X.509 of an Ed25519 key: a fixed 12-byte header, then the raw 32-byte key the relay expects.
        val raw = pair.public.encoded.takeLast(32).toByteArray()
        return RelayIdentity(Base64.getUrlEncoder().withoutPadding().encodeToString(raw), sealer.seal(pair.private.encoded, context(base)), null)
    }

    override fun close() = http.close()

    companion object {
        // The `PushRelay` constants of the API module, which the server modules may not depend on.
        const val REGISTER_PATH = "/v1/instances"
        const val SEND_PATH = "/v1/send"
        const val INSTANCE_HEADER = "TeamCode-Instance"
        const val TIMESTAMP_HEADER = "TeamCode-Timestamp"
        const val SIGNATURE_HEADER = "TeamCode-Signature"
        fun signingString(method: String, path: String, timestamp: Long, bodySha256Hex: String) = "${method.uppercase()}\n$path\n$timestamp\n$bodySha256Hex"
        fun context(relayUrl: String) = "push-relay:$relayUrl"

        fun defaultClient() = HttpClient(CIO) {
            followRedirects = false; expectSuccess = false
            install(HttpTimeout) { connectTimeoutMillis = 5_000; requestTimeoutMillis = 10_000; socketTimeoutMillis = 10_000 }
        }
    }
}
