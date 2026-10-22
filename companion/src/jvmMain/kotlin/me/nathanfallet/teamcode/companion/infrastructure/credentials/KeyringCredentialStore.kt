package me.nathanfallet.teamcode.companion.infrastructure.credentials

import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore

/** The operations of an OS keyring, for one service name; implemented by java-keyring, faked in tests. */
interface KeyringBackend {
    /** Null when there is no entry for [account]. */
    fun get(service: String, account: String): String?
    fun set(service: String, account: String, secret: String)
    fun delete(service: String, account: String)
}

/**
 * Tokens in the operating system's keyring (Keychain on macOS, Credential Manager on Windows, Secret Service on Linux),
 * service `me.nathanfallet.teamcode`, one entry per account id. Without a keyring (a Linux desktop without Secret
 * Service), or when it fails, tokens stay in memory: the app signs in again at each launch and says so. Never a file.
 */
class KeyringCredentialStore(private val backend: KeyringBackend?, private val service: String = SERVICE) : CredentialStore {
    private val memory = MemoryCredentialStore()
    @Volatile private var usable = backend != null
    override val persistent: Boolean get() = usable

    override suspend fun read(accountId: String): String? = keyring { it.get(service, accountId) } ?: memory.read(accountId)

    override suspend fun write(accountId: String, token: String) {
        if (keyring { it.set(service, accountId, token); Unit } == null) memory.write(accountId, token) else memory.delete(accountId)
    }

    override suspend fun delete(accountId: String) {
        keyring { it.delete(service, accountId) }
        memory.delete(accountId)
    }

    private suspend fun <T : Any> keyring(call: (KeyringBackend) -> T?): T? {
        val keyring = backend?.takeIf { usable } ?: return null
        return withContext(Dispatchers.IO) {
            try {
                call(keyring)
            } catch (_: Exception) {
                // A locked or broken keyring: fall back to memory for the rest of this run instead of failing sign-in.
                usable = false
                null
            }
        }
    }

    companion object {
        const val SERVICE = "me.nathanfallet.teamcode"

        fun open(): KeyringCredentialStore = KeyringCredentialStore(
            try { JavaKeyringBackend(Keyring.create()) } catch (_: BackendNotSupportedException) { null },
        )
    }
}

private class JavaKeyringBackend(private val keyring: Keyring) : KeyringBackend {
    override fun get(service: String, account: String): String? =
        try { keyring.getPassword(service, account) } catch (_: PasswordAccessException) { null }
    override fun set(service: String, account: String, secret: String) = keyring.setPassword(service, account, secret)
    override fun delete(service: String, account: String) {
        try { keyring.deletePassword(service, account) } catch (_: PasswordAccessException) { /* Already absent. */ }
    }
}
