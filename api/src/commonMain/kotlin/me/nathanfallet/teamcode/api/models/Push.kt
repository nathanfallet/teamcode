package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/**
 * `PUT /api/v1/devices/current/push`: the push destination of the caller's device (an iOS or Android installation).
 * [token] is the FCM registration token of the installation; [publicKey] the device's P-256 public key (SubjectPublicKeyInfo,
 * DER, base64) the server encrypts each notification for. Sent at every start of the app and when FCM rotates the token.
 * [platform] (`ios` or `android`) is recorded on a device whose login did not report one; an older app omits it.
 */
@Serializable
data class DevicePushRequest(val token: String, val publicKey: String, val platform: String? = null)

/**
 * What a push carries, encrypted for the device (docs/notifications.md, "Push payload"). The app decrypts it in its
 * notification service extension (iOS) or messaging service (Android) and shows [title] and [body]; [origin] and
 * [memberId] say which signed-in account it is for, [sessionId] and [waitId] where a tap leads. [kind] is a notification
 * kind (`waiting`, `turn_completed`, `turn_failed`, `session_failed`) or `test` for the administrator's test push.
 */
@Serializable
data class PushPayload(
    val v: Int = 1, val id: String, val origin: String, val memberId: String, val kind: String,
    val title: String, val body: String, val sessionId: String? = null, val waitId: String? = null, val at: Long,
)

/**
 * The TeamCode Push relay protocol (`push.v1`): what an instance sends to the relay that holds the Firebase project of the
 * published apps. The relay sees an instance id, a token and an encrypted blob: never a member, a session or the text.
 *
 * Every request but [REGISTER_PATH] is signed by the instance with its Ed25519 key: headers [INSTANCE_HEADER],
 * [TIMESTAMP_HEADER] (Unix seconds) and [SIGNATURE_HEADER] (base64url, no padding) over [signingString].
 */
object PushRelay {
    const val REGISTER_PATH = "/v1/instances"
    const val SEND_PATH = "/v1/send"
    const val INSTANCE_HEADER = "TeamCode-Instance"
    const val TIMESTAMP_HEADER = "TeamCode-Timestamp"
    const val SIGNATURE_HEADER = "TeamCode-Signature"
    /** A signed request older or newer than this is refused. */
    const val MAX_SKEW_SECONDS = 300L
    /** The encrypted payload, base64: well under FCM's 4 096-byte data limit. */
    const val MAX_PAYLOAD_CHARS = 3_000
    const val MAX_TOKEN_CHARS = 512
    /** What the relay shows when the device cannot decrypt (the iOS extension ran out of time, an old app). */
    const val GENERIC_TITLE = "TeamCode"
    const val GENERIC_BODY = "New activity in TeamCode"
    /** The key of the encrypted payload in the FCM data, and of its format version. */
    const val DATA_PAYLOAD = "p"
    const val DATA_VERSION = "v"

    /** `<METHOD>\n<path>\n<timestamp>\n<sha256 of the body, lowercase hex>`. */
    fun signingString(method: String, path: String, timestamp: Long, bodySha256Hex: String) = "${method.uppercase()}\n$path\n$timestamp\n$bodySha256Hex"
}

/** `POST /v1/instances`: an instance introduces its Ed25519 public key (raw 32 bytes, base64url). The same key gets the same id. */
@Serializable
data class PushRelayRegisterRequest(val publicKey: String)

@Serializable
data class PushRelayRegisterResponse(val instanceId: String)

/**
 * `POST /v1/send`: one push to one installation. [platform] is `ios` or `android`; [payload] the encrypted [PushPayload],
 * base64; [collapseKey] an opaque token (a hash) grouping pushes that replace one another; [ttlSeconds] how long FCM keeps it.
 */
@Serializable
data class PushRelaySendRequest(val token: String, val platform: String, val payload: String, val collapseKey: String, val ttlSeconds: Long)

/**
 * The outcome: `sent`, `unregistered` (FCM no longer knows the token: forget it), `retry` (FCM or the relay is
 * unavailable or rate limited; [retryAfterSeconds] when known) or `rejected` (refused for good, keep the token).
 */
@Serializable
data class PushRelaySendResponse(val status: String, val retryAfterSeconds: Long? = null, val error: String? = null)

/**
 * `GET/PUT /api/v1/notifications/push/settings` (administrators): who sends the team's pushes. [provider] is `off`,
 * `relay` (the TeamCode Push relay at [relayUrl], for the published apps) or `firebase` (the team's own Firebase project,
 * for apps it builds itself). The service account key is never returned: [firebaseProjectId] and [firebaseClientEmail]
 * say which one is stored.
 */
@Serializable
data class PushSettingsView(
    val provider: String, val relayUrl: String, val firebaseProjectId: String? = null, val firebaseClientEmail: String? = null,
    val version: Int, val updatedAt: Long? = null,
)

/**
 * [firebaseServiceAccount] is the JSON key of a Google service account of the Firebase project; leave it null to keep the
 * stored one. Choosing another provider than `firebase` removes the stored key. [version] is the one read.
 */
@Serializable
data class SavePushSettingsRequest(val provider: String, val relayUrl: String? = null, val firebaseServiceAccount: String? = null, val version: Int)

/** `POST /api/v1/notifications/push/test`: one test push per installation of the caller; [ok] when one was sent. */
@Serializable
data class PushTestView(val ok: Boolean, val results: List<PushTestResultView>)

/** [status] is `sent`, `unregistered`, `retry` or `rejected`; [error] a short code, never a provider body. */
@Serializable
data class PushTestResultView(val status: String, val error: String? = null)
