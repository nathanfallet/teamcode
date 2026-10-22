package me.nathanfallet.teamcode.companion.infrastructure.credentials

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
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
        // A key lost with a device restore, or a damaged entry: the token is gone, the member signs in again.
        TokenCipher.open(key(), accountId, stored) ?: run { preferences.edit().remove(accountId).commit(); null }
    }

    override suspend fun write(accountId: String, token: String) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString(accountId, TokenCipher.seal(key(), accountId, token)).commit()) { "The token could not be saved." }
    }

    override suspend fun delete(accountId: String) = withContext(Dispatchers.IO) {
        preferences.edit().remove(accountId).commit()
        Unit
    }

    companion object {
        /** The preference file of the ciphertexts; `backup_rules.xml` and `data_extraction_rules.xml` exclude it. */
        const val FILE = "teamcode_credentials"
        private const val ALIAS = "me.nathanfallet.teamcode.credentials"

        // Reads and writes run concurrently on the IO dispatcher: two first uses must not each generate a key.
        @Synchronized
        private fun keystoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
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
            return try {
                generate(strongBox = Build.VERSION.SDK_INT >= 28)
            } catch (_: StrongBoxUnavailableException) {
                generate(strongBox = false)
            }
        }
    }
}
