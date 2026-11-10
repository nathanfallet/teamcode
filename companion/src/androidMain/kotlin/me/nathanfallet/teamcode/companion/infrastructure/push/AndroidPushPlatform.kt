package me.nathanfallet.teamcode.companion.infrastructure.push

import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.nathanfallet.teamcode.companion.domain.models.PushMessage
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import me.nathanfallet.teamcode.companion.domain.services.PushPlatform

/**
 * The phone's push key pair and FCM token. The key pair is made on first use and kept, PKCS#8 and SPKI, in the
 * Keystore-sealed credential store under [KEY_ENTRY] (never an account id: those are UUIDs and `cli`), so it is excluded
 * from backups with the tokens. The app's messaging service hands the token over through [updateToken].
 */
class AndroidPushPlatform(private val credentials: CredentialStore) : PushPlatform {
    override val platform = "android"
    private val current = MutableStateFlow<String?>(null)
    override val token: StateFlow<String?> = current.asStateFlow()
    private val lock = Mutex()
    private var keys: Pair<PrivateKey, ECPublicKey>? = null

    fun updateToken(token: String?) { current.value = token?.takeIf { it.isNotBlank() } }

    override suspend fun publicKey(): String = PushCipher.encodePublic(keys().second)

    override suspend fun open(payload: String): PushMessage = keys().let { (private, public) -> PushPayloadReader.read(PushCipher.decrypt(private, public, payload)) }

    private suspend fun keys(): Pair<PrivateKey, ECPublicKey> = lock.withLock {
        keys?.let { return it }
        val stored = credentials.read(KEY_ENTRY)?.split(' ')?.takeIf { it.size == 2 }?.let { (private, public) ->
            try { PushCipher.decodePrivate(private) to PushCipher.decodePublic(public) } catch (_: Exception) { null }
        }
        // A new pair (first use, or the sealed one was lost with the Keystore key): the next sync registers its public key.
        val pair = stored ?: PushCipher.generate().let { generated ->
            val public = generated.public as ECPublicKey
            credentials.write(KEY_ENTRY, "${PushCipher.encodePrivate(generated.private)} ${PushCipher.encodePublic(public)}")
            generated.private to public
        }
        keys = pair
        pair
    }

    companion object {
        const val KEY_ENTRY = "push-device-key"
    }
}
