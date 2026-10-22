package me.nathanfallet.teamcode.companion.infrastructure.credentials

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore

/**
 * Tokens encrypted with AES-256-GCM by a non-exportable Android Keystore key (in StrongBox when the device has one).
 * Only the ciphertext is written, in its own preference file, which the manifest excludes from backups and device
 * transfers. `security-crypto` (EncryptedSharedPreferences) is deprecated, hence these few lines.
 */
class KeystoreCredentialStore(
    private val preferences: SharedPreferences,
    private val key: () -> SecretKey = ::keystoreKey,
) : CredentialStore {
    override val persistent = true

    override suspend fun read(accountId: String): String? = withContext(Dispatchers.IO) {
        val stored = preferences.getString(accountId, null) ?: return@withContext null
        // A key lost with a device restore, a key the system invalidated, or a damaged entry: the token is gone, the
        // member signs in again.
        val token = try { TokenCipher.open(key(), accountId, stored) } catch (_: KeyPermanentlyInvalidatedException) { deleteKey(); null }
        token ?: run { preferences.edit().remove(accountId).commit(); null }
    }

    override suspend fun write(accountId: String, token: String) = withContext(Dispatchers.IO) {
        val sealed = try { TokenCipher.seal(key(), accountId, token) } catch (_: KeyPermanentlyInvalidatedException) { deleteKey(); TokenCipher.seal(key(), accountId, token) }
        check(preferences.edit().putString(accountId, sealed).commit()) { "The token could not be saved." }
    }

    override suspend fun delete(accountId: String) = withContext(Dispatchers.IO) {
        check(preferences.edit().remove(accountId).commit()) { "The token could not be deleted." }
    }

    companion object {
        /** The preference file of the ciphertexts; `backup_rules.xml` and `data_extraction_rules.xml` exclude it. */
        const val FILE = "teamcode_credentials"
        private const val ALIAS = "me.nathanfallet.teamcode.credentials"

        /**
         * The key was permanently invalidated by the system (other InvalidKeyExceptions may be transient and propagate):
         * every token sealed with it is lost anyway, and the next use generates a new key.
         */
        @Synchronized
        private fun deleteKey() {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        }

        // Reads and writes run concurrently on the IO dispatcher: two first uses must not each generate a key.
        @Synchronized
        private fun keystoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            try {
                (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            } catch (_: UnrecoverableKeyException) {
                keyStore.deleteEntry(ALIAS) // Unusable after an OS update or a restore: replaced below.
            }
            fun generate(strongBox: Boolean): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
                        .build(),
                )
                generateKey()
            }
            if (Build.VERSION.SDK_INT < 28) return generate(strongBox = false)
            return try {
                generate(strongBox = true)
            } catch (_: ProviderException) {
                // StrongBoxUnavailableException, or a StrongBox that fails to generate: the TEE key is still hardware-backed.
                generate(strongBox = false)
            }
        }
    }
}
