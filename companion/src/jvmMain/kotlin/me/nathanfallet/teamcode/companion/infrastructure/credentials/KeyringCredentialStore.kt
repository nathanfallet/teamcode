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
 * Service), or when a write fails, tokens stay in memory: the app signs in again at each launch and says so. Never a file.
 */
class KeyringCredentialStore(private val backend: KeyringBackend?, private val service: String = SERVICE) : CredentialStore {
    private val memory = MemoryCredentialStore()
    @Volatile private var writeFailed = false
    override val persistent: Boolean get() = backend != null && !writeFailed

    // Memory first: a token kept there after a failed keyring write is newer than any entry the keyring still holds.
    // A keyring that fails to answer throws (the caller reports it) rather than reading as "no token", which would sign
    // the account out for a keyring that is only locked.
    override suspend fun read(accountId: String): String? = memory.read(accountId) ?: backend?.let { keyring ->
        withContext(Dispatchers.IO) { keyring.get(service, accountId) }
    }

    override suspend fun write(accountId: String, token: String) {
        if (keyring { it.set(service, accountId, token); Unit } != null) { memory.delete(accountId); writeFailed = false; return }
        // A locked or broken keyring: keep the token in memory for this run instead of failing the sign-in.
        if (backend != null) writeFailed = true
        memory.write(accountId, token)
    }

    override suspend fun delete(accountId: String) {
        keyring { it.delete(service, accountId) }
        memory.delete(accountId)
    }

    /** One keyring call off the main thread; a failure is that call's alone (a locked keyring may open later). */
    private suspend fun <T : Any> keyring(call: (KeyringBackend) -> T?): T? {
        val keyring = backend ?: return null
        return withContext(Dispatchers.IO) {
            try { call(keyring) } catch (_: Exception) { null }
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
        try { keyring.getPassword(service, account) } catch (error: PasswordAccessException) {
            // java-keyring uses one exception for a missing entry and a refused access (a locked keyring). Only the
            // first is "no token"; the backends say it as "not found" (Windows, the macOS Keychain status text) or
            // "No stored credentials match" (Secret Service).
            if (error.message.orEmpty().let { "found" in it.lowercase() || "no stored credentials" in it.lowercase() }) null else throw error
        }
    override fun set(service: String, account: String, secret: String) = keyring.setPassword(service, account, secret)
    override fun delete(service: String, account: String) {
        try { keyring.deletePassword(service, account) } catch (_: PasswordAccessException) { /* Already absent. */ }
    }
}
