package me.nathanfallet.teamcode.companion.infrastructure.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.nathanfallet.teamcode.companion.domain.models.PushMessage
import me.nathanfallet.teamcode.companion.domain.services.PushPlatform

/**
 * The push key pair, implemented in Swift (PushKeys.swift): CryptoKit makes it and keeps the private key in a Keychain
 * access group the notification service extension shares, since the extension decrypts each push before iOS shows it.
 */
interface IosPushKeys {
    /** The public key (SubjectPublicKeyInfo, DER, base64), made on first use; empty when the Keychain refused. */
    fun publicKey(): String

    /** Asks the member to allow notifications (iOS asks once; later calls do nothing). */
    fun requestAuthorization()
}

class IosPushPlatform(private val keys: IosPushKeys) : PushPlatform {
    private val current = MutableStateFlow<String?>(null)
    override val token: StateFlow<String?> = current.asStateFlow()
    private var asked = false

    fun updateToken(token: String?) { current.value = token?.takeIf { it.isNotBlank() } }

    // Called when an account whose server offers push is registered: the right time to ask, not at the first launch.
    override suspend fun publicKey(): String {
        if (!asked) { asked = true; keys.requestAuthorization() }
        return keys.publicKey().ifEmpty { error("The push key could not be read from the Keychain.") }
    }

    override suspend fun open(payload: String): PushMessage = throw UnsupportedOperationException("On iOS the notification service extension decrypts.")
}
