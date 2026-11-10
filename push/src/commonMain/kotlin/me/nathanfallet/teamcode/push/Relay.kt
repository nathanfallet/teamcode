package me.nathanfallet.teamcode.push

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.nathanfallet.teamcode.api.models.PushRelay
import me.nathanfallet.teamcode.api.models.PushRelaySendRequest
import me.nathanfallet.teamcode.api.models.PushRelaySendResponse

/** The provider edge: one message to FCM, answered in the relay's own terms. Never logs the token or the payload. */
fun interface FcmSender {
    suspend fun send(request: PushRelaySendRequest): PushRelaySendResponse
}

/**
 * The relay's quotas, per minute. [perInstance] bounds one TeamCode server, [perToken] one phone whatever the server,
 * [total] the whole relay (the Firebase project's own quota is shared by every instance).
 */
data class RelayLimits(val perInstance: Int = 600, val perToken: Int = 20, val total: Int = 6_000)

/** What a request to the relay ends as: an HTTP status and the JSON body, or a refusal with [retryAfterSeconds]. */
data class RelayAnswer(val status: Int, val body: PushRelaySendResponse, val retryAfterSeconds: Long? = null)

/**
 * The relay (docs/notifications.md, "Relay protocol"). It keeps no registry: an instance's id is its Ed25519 public key
 * (raw 32 bytes, base64url), so any replica of the relay checks a signature without shared state, and a restart forgets
 * nothing. An instance is cut off by listing its id in [blocked]. Quotas and the replay guard are per relay process: the
 * Cloud Run service runs one instance (see push/README.md).
 */
class Relay(
    private val fcm: FcmSender,
    private val blocked: Set<String> = emptySet(),
    private val limits: RelayLimits = RelayLimits(),
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val crypto = CryptographyProvider.Default
    private val keys = crypto.get(EdDSA).publicKeyDecoder(EdDSA.Curve.Ed25519)
    private val sha256 = crypto.get(SHA256).hasher()
    private val quotas = Quotas(limits)
    private val seen = ReplayGuard()

    /** `POST /v1/instances`: a valid Ed25519 key is its own id; the same key always gets the same id. */
    fun register(publicKey: String): String? = publicKey.takeIf { parseKey(it) != null }

    /** `POST /v1/send`: the signed request, checked, rate limited, validated, then sent. */
    suspend fun send(instanceId: String?, timestamp: String?, signature: String?, body: ByteArray, parse: (ByteArray) -> PushRelaySendRequest?): RelayAnswer {
        val id = instanceId?.takeIf { it.length <= 64 } ?: return unauthorized("unknown_instance")
        val key = parseKey(id) ?: return unauthorized("unknown_instance")
        val at = timestamp?.toLongOrNull() ?: return unauthorized("bad_signature")
        if (kotlin.math.abs(now() - at) > PushRelay.MAX_SKEW_SECONDS) return unauthorized("stale_request")
        val signed = signature?.let { decode(it) }?.takeIf { it.size == 64 } ?: return unauthorized("bad_signature")
        val signing = PushRelay.signingString("POST", PushRelay.SEND_PATH, at, hex(sha256.hash(body)))
        if (!key.signatureVerifier().tryVerifySignature(signing.encodeToByteArray(), signed)) return unauthorized("bad_signature")
        if (id in blocked) return RelayAnswer(403, rejected("instance_blocked"))
        // A request seen once is not sent twice: a copy within the skew window is answered as sent.
        if (!seen.first(signature, at, now())) return RelayAnswer(200, PushRelaySendResponse("sent"))

        val request = parse(body) ?: return RelayAnswer(400, rejected("invalid_request"))
        validate(request)?.let { return RelayAnswer(400, rejected(it)) }
        val minute = now() / 60
        if (!quotas.take(id, request.token, minute))
            return RelayAnswer(429, PushRelaySendResponse("retry", 60 - now() % 60, "rate_limited"), 60 - now() % 60)
        return try {
            RelayAnswer(200, fcm.send(request))
        } catch (error: CancellationException) { throw error } catch (_: Exception) {
            RelayAnswer(200, PushRelaySendResponse("retry", error = "fcm_unreachable"))
        }
    }

    private fun parseKey(base64: String) = decode(base64)?.takeIf { it.size == 32 }?.let {
        try { keys.decodeFromByteArrayBlocking(EdDSA.PublicKey.Format.RAW, it) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
    }

    private fun unauthorized(error: String) = RelayAnswer(401, rejected(error))
    private fun rejected(error: String) = PushRelaySendResponse("rejected", error = error)

    companion object {
        private val tokenPattern = Regex("[A-Za-z0-9_:\\-.]{20,${PushRelay.MAX_TOKEN_CHARS}}")
        private val payloadPattern = Regex("[A-Za-z0-9+/=]{16,${PushRelay.MAX_PAYLOAD_CHARS}}")
        private val collapsePattern = Regex("[a-f0-9]{1,64}")
        const val MAX_TTL_SECONDS = 86_400L

        fun validate(request: PushRelaySendRequest): String? = when {
            !tokenPattern.matches(request.token) -> "invalid_token"
            request.platform != "ios" && request.platform != "android" -> "invalid_platform"
            !payloadPattern.matches(request.payload) -> "invalid_payload"
            !collapsePattern.matches(request.collapseKey) -> "invalid_collapse_key"
            request.ttlSeconds !in 0..MAX_TTL_SECONDS -> "invalid_ttl"
            else -> null
        }

        private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        fun decode(value: String): ByteArray? = try { urlSafe.decode(value) } catch (_: IllegalArgumentException) { null }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

/**
 * Fixed one-minute windows for the three quotas, checked together: a request counts only when all of them accept it.
 * Bounded: when a window holds too many keys, the minutes already over are dropped; still full, new keys are refused.
 */
internal class Quotas(private val limits: RelayLimits, private val maxKeys: Int = 200_000) {
    private val mutex = Mutex()
    private val instances = HashMap<String, Pair<Long, Int>>()
    private val tokens = HashMap<String, Pair<Long, Int>>()
    private var total = 0L to 0

    suspend fun take(instance: String, token: String, minute: Long): Boolean = mutex.withLock {
        fun count(map: HashMap<String, Pair<Long, Int>>, key: String) = map[key]?.takeIf { it.first == minute }?.second ?: 0
        fun room(map: HashMap<String, Pair<Long, Int>>, key: String): Boolean {
            if (map.size >= maxKeys) map.entries.removeAll { it.value.first != minute }
            return map.size < maxKeys || key in map
        }
        val totalCount = if (total.first == minute) total.second else 0
        if (totalCount >= limits.total || !room(instances, instance) || !room(tokens, token)) return@withLock false
        val byInstance = count(instances, instance); val byToken = count(tokens, token)
        if (byInstance >= limits.perInstance || byToken >= limits.perToken) return@withLock false
        instances[instance] = minute to byInstance + 1; tokens[token] = minute to byToken + 1; total = minute to totalCount + 1
        true
    }
}

/** The signatures of the requests sent within the skew window, so a copied request is not sent twice. */
internal class ReplayGuard(private val maxEntries: Int = 200_000) {
    private val mutex = Mutex()
    private val seen = HashMap<String, Long>()

    suspend fun first(signature: String, timestamp: Long, now: Long): Boolean = mutex.withLock {
        if (seen.size >= maxEntries) seen.entries.removeAll { now - it.value > 2 * PushRelay.MAX_SKEW_SECONDS }
        if (seen.size >= maxEntries) return@withLock false
        seen.put(signature, timestamp) == null
    }
}
