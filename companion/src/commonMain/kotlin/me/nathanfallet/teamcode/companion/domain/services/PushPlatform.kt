package me.nathanfallet.teamcode.companion.domain.services

import kotlinx.coroutines.flow.StateFlow
import me.nathanfallet.teamcode.companion.domain.models.PushMessage

/**
 * The push side of a phone (docs/notifications.md, "Push"): the installation's FCM token and its P-256 key pair. The
 * private key never leaves the device's secure store (the Android Keystore-sealed preferences, the iOS Keychain shared
 * with the notification service extension); servers encrypt each notification for the public key. Absent on the
 * desktop, which notifies from the event feed instead.
 */
interface PushPlatform {
    /** The installation's current FCM token: null until the system gave one, or when this build has no Firebase project. */
    val token: StateFlow<String?>

    /** The public key (SubjectPublicKeyInfo, DER, base64), made on first use and the same afterwards. */
    suspend fun publicKey(): String

    /** Decrypts and reads a push's payload (docs/notifications.md, "Push payload"); throws when it is not for this key. */
    suspend fun open(payload: String): PushMessage
}
