package me.nathanfallet.teamcode.domain.notifications

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.team.*

/** Where a push goes: the installation's current FCM token and the key its notifications are encrypted for. */
data class PushTarget(val deviceId: String, val memberId: String, val token: String, val publicKey: String, val platform: String)

/** One push to one installation. [payload] is the encrypted notification, base64; [collapseKey] an opaque hash. */
data class PushMessage(val token: String, val platform: String, val payload: String, val collapseKey: String, val ttlSeconds: Long)

data class PushResult(val status: Status, val error: String? = null) {
    enum class Status {
        SENT,
        /** The provider no longer knows the token: the installation was removed or the token rotated. */
        UNREGISTERED,
        /** Unavailable, failing or rate limited: worth another attempt later. */
        RETRY,
        /** Refused for good; the token is kept (the fault may be ours). */
        REJECTED,
    }
    companion object {
        val SENT = PushResult(Status.SENT)
        val UNREGISTERED = PushResult(Status.UNREGISTERED)
        fun retry(error: String) = PushResult(Status.RETRY, error)
        fun rejected(error: String) = PushResult(Status.REJECTED, error)
    }
}

/**
 * The provider edge: the TeamCode Push relay (the published apps' Firebase project, without its key on this server) or
 * a team's own Firebase project. Never logs the token or the payload.
 */
fun interface PushSender {
    suspend fun send(message: PushMessage): PushResult
}

/** The push destinations of devices, on their `devices` row. */
interface PushDeviceStore {
    /** The interactive session's device: null for a browser session, an older client or a service key. */
    suspend fun currentDevice(actor: Principal): PushDevice?
    /** Sets or replaces the device's destination; the same token may be on other rows (several accounts on one phone). */
    suspend fun register(deviceId: String, token: String, publicKey: String)
    /** Clears the device's destination; with [onlyToken], only while it is still that token (a rotation may have replaced it). */
    suspend fun clear(deviceId: String, onlyToken: String? = null)
    /** The destinations of [memberIds] on devices that have a signed-in session, in one read. */
    suspend fun targets(teamId: String, memberIds: Set<String>): List<PushTarget>
}

data class PushDevice(val id: String, val memberId: String, val platform: String?)

/** A member registers the push destination of the device they are signed in on. */
class PushRegistrationService(private val store: PushDeviceStore) {
    suspend fun register(actor: Principal, token: String, publicKey: String) {
        actor.requireInteractive()
        PushPayloads.validateToken(token)
        PushPayloads.validatePublicKey(publicKey)
        val device = store.currentDevice(actor) ?: throw noDevice()
        if (device.platform !in PUSH_PLATFORMS) throw TeamFailure("push_unsupported", 400, "Push notifications reach iOS and Android installations only; desktop apps notify from the event feed.")
        store.register(device.id, token, publicKey)
    }

    suspend fun clear(actor: Principal) {
        actor.requireInteractive()
        store.clear((store.currentDevice(actor) ?: throw noDevice()).id)
    }

    private fun noDevice() = TeamFailure("device_required", 409, "Sign in again with the app: this session is not tied to a registered device.")

    companion object { val PUSH_PLATFORMS = setOf("ios", "android") }
}

/**
 * The encrypted payload of a push (docs/notifications.md, "Push payload"): ECIES on P-256.
 * `base64( 0x01 | ephemeral public key (65 bytes, uncompressed) | nonce (12) | AES-256-GCM ciphertext and tag )`, the key
 * being HKDF-SHA256 of the ECDH secret with salt `ephemeral point | recipient point` and info `teamcode-push-v1`.
 */
object PushPayloads {
    const val VERSION: Byte = 1
    private const val INFO = "teamcode-push-v1"
    private val tokenPattern = Regex("[A-Za-z0-9_:\\-.]{20,512}")
    private val random = SecureRandom()

    fun validateToken(token: String) {
        if (!tokenPattern.matches(token)) throw TeamFailure("invalid_push_token", 400, "Send the FCM registration token of the installation.")
    }

    /** A P-256 public key, SubjectPublicKeyInfo DER in base64. */
    fun validatePublicKey(publicKey: String) {
        if (publicKey.length > 255 || runCatching { publicKey(publicKey) }.isFailure)
            throw TeamFailure("invalid_push_key", 400, "Send the device's P-256 public key (SubjectPublicKeyInfo, DER, base64).")
    }

    /** The JSON the device decrypts, field for field the `PushPayload` of the API. */
    fun plaintext(notification: Notification, origin: String): String = with(notification) {
        plaintext(id, memberId, kind.wire, title, body, sessionId, waitId, createdAt, origin)
    }

    fun plaintext(id: String, memberId: String, kind: String, title: String, body: String, sessionId: String?, waitId: String?, at: Long, origin: String): String = buildJsonObject {
        put("v", 1); put("id", id); put("origin", origin); put("memberId", memberId); put("kind", kind)
        put("title", title); put("body", body)
        sessionId?.let { put("sessionId", it) }; waitId?.let { put("waitId", it) }
        put("at", at)
    }.toString()

    fun encrypt(recipientPublicKey: String, plaintext: String): String {
        val recipient = publicKey(recipientPublicKey)
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }
        val ephemeral = generator.generateKeyPair()
        val secret = KeyAgreement.getInstance("ECDH").run { init(ephemeral.private); doPhase(recipient, true); generateSecret() }
        val ephemeralPoint = point(ephemeral.public as ECPublicKey)
        val key = hkdf(secret, ephemeralPoint + point(recipient), INFO.encodeToByteArray(), 32)
        val nonce = ByteArray(12).also(random::nextBytes)
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); doFinal(plaintext.encodeToByteArray())
        }
        return Base64.getEncoder().encodeToString(byteArrayOf(VERSION) + ephemeralPoint + nonce + sealed)
    }

    /** An opaque collapse key: the provider sees which pushes replace one another, not which session or wait they are about. */
    fun collapseKey(key: String): String = sha256Hex(key.encodeToByteArray()).take(32)

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    internal fun publicKey(base64: String): ECPublicKey {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64))) as ECPublicKey
        require(key.params.curve.field.fieldSize == 256 && key.params.order.bitLength() == 256)
        return key
    }

    /** The uncompressed SEC1 point: `0x04 | x | y`, 32 bytes each. */
    internal fun point(key: ECPublicKey): ByteArray {
        fun fixed(value: java.math.BigInteger) = value.toByteArray().let { bytes -> if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size) else ByteArray(32 - bytes.size) + bytes }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(salt, "HmacSHA256")); doFinal(ikm) }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        var block = ByteArray(0); var out = ByteArray(0); var counter = 1
        while (out.size < length) { mac.update(block); mac.update(info); mac.update(counter.toByte()); block = mac.doFinal(); out += block; counter++ }
        return out.copyOf(length)
    }
}

/**
 * Push to the members' phones: one encrypted push per installation that registered a token and still has a signed-in
 * session, through the provider the team chose (the relay or its own Firebase project; nothing while push is off). The
 * provider sees a token and a blob; the text exists in clear only on this server and on the device.
 */
class PushChannel(
    private val store: PushDeviceStore, private val senders: PushSenders, private val origin: String,
    private val ttlSeconds: Long = NotificationService.GIVE_UP_MILLIS / 1000, private val clock: () -> Long = System::currentTimeMillis,
) : NotificationChannel {
    override val id = ID

    override suspend fun reachable(teamId: String, memberIds: Set<String>): Set<String> {
        if (senders.senders(setOf(teamId)).isEmpty()) return emptySet()
        return store.targets(teamId, memberIds).map { it.memberId }.toSet()
    }

    override suspend fun deliver(notifications: List<Notification>): Map<String, ChannelResult> {
        if (notifications.isEmpty()) return emptyMap()
        val byTeam = notifications.groupBy { it.teamId }
        // The providers of every team of the batch in one read, then their destinations: one read per team.
        val providers = senders.senders(byTeam.keys)
        val targets = byTeam.filterKeys { it in providers }.flatMap { (team, list) -> store.targets(team, list.map { it.memberId }.toSet()) }.groupBy { it.memberId }
        val attempts = coroutineScope {
            notifications.filter { it.teamId in providers }.flatMap { notification -> targets[notification.memberId].orEmpty().map { notification to it } }
                .map { (notification, target) ->
                    async { Triple(notification.id, target, send(providers.getValue(notification.teamId), PushPayloads.plaintext(notification, origin), notification.teamId, notification.collapseKey, target)) }
                }.awaitAll()
        }
        forgetUnregistered(attempts.map { it.second to it.third })
        val outcomes = attempts.groupBy({ it.first }, { it.third })
        return notifications.associate { notification ->
            notification.id to if (notification.teamId !in providers) ChannelResult.failed("push_off") else outcome(outcomes[notification.id].orEmpty())
        }
    }

    /** A test notification to [memberId]'s installations, sent now through the team's provider; nothing is queued. */
    suspend fun test(teamId: String, memberId: String): PushTestResult {
        val sender = senders.senders(setOf(teamId))[teamId] ?: throw TeamFailure("push_off", 409, "Choose a push provider and save it before sending a test.")
        val targets = store.targets(teamId, setOf(memberId))
        if (targets.isEmpty()) throw TeamFailure("push_no_device", 409, "Sign in with the TeamCode app on your phone and allow notifications, then send the test again.")
        val now = clock()
        val plaintext = PushPayloads.plaintext("test-$now", memberId, "test", "TeamCode", "Push notifications work.", null, null, now, origin)
        val results = coroutineScope { targets.map { target -> async { target to send(sender, plaintext, teamId, "test", target) } }.awaitAll() }
        forgetUnregistered(results)
        return PushTestResult(results.map { it.second })
    }

    /** A token the provider no longer knows is forgotten, unless the app already replaced it. */
    private suspend fun forgetUnregistered(results: List<Pair<PushTarget, PushResult>>) =
        results.filter { it.second.status == PushResult.Status.UNREGISTERED }.map { it.first }.distinctBy { it.deviceId to it.token }
            .forEach { store.clear(it.deviceId, onlyToken = it.token) }

    private suspend fun send(sender: PushSender, plaintext: String, teamId: String, collapseKey: String, target: PushTarget): PushResult = try {
        val payload = PushPayloads.encrypt(target.publicKey, plaintext)
        withTimeout(SEND_DEADLINE_MILLIS) {
            sender.send(PushMessage(target.token, target.platform, payload, PushPayloads.collapseKey("$teamId:$collapseKey"), ttlSeconds))
        }
    } catch (_: TimeoutCancellationException) { PushResult.retry("timeout") }

    /** Delivered when one installation got it; retried when none did and one may still; failed otherwise. */
    private fun outcome(results: List<PushResult>): ChannelResult = when {
        results.isEmpty() -> ChannelResult.failed("no_device")
        results.any { it.status == PushResult.Status.SENT } -> ChannelResult.DELIVERED
        results.any { it.status == PushResult.Status.RETRY } -> ChannelResult.retry(results.first { it.status == PushResult.Status.RETRY }.error ?: "retry")
        results.all { it.status == PushResult.Status.UNREGISTERED } -> ChannelResult.failed("unregistered")
        else -> ChannelResult.failed(results.firstNotNullOfOrNull { it.error } ?: "rejected")
    }

    companion object {
        const val ID = "push"
        const val SEND_DEADLINE_MILLIS = 10_000L
    }
}
