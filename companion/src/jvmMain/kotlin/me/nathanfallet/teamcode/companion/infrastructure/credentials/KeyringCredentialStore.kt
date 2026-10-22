package me.nathanfallet.teamcode.companion.infrastructure.credentials

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
    /** Accounts whose token lives only in memory, after a keyring write failed. */
    private val memoryOnly = MutableStateFlow(emptySet<String>())
    override val persistent: Boolean get() = backend != null && memoryOnly.value.isEmpty()

    // A keyring that fails to answer throws (the caller reports it) rather than reading as "no token", which would sign
    // the account out for a keyring that is only locked. Memory first: a token kept there is newer.
    override suspend fun read(accountId: String): String? = memory.read(accountId) ?: backend?.let { keyring ->
        withContext(Dispatchers.IO) { keyring.get(service, accountId) }
    }

    override suspend fun write(accountId: String, token: String) {
        if (keyring { it.set(service, accountId, token); Unit } != null) {
            memory.delete(accountId)
            memoryOnly.update { it - accountId }
            return
        }
        // A locked or broken keyring: keep the token in memory for this run instead of failing the sign-in, and drop
        // the older entry when the keyring allows it, so a restart never brings back a replaced token.
        if (backend != null) memoryOnly.update { it + accountId }
        memory.write(accountId, token)
        keyring { it.delete(service, accountId) }
    }

    /** Throws when the keyring refuses: the caller decides whether a token left behind matters. */
    override suspend fun delete(accountId: String) {
        memory.delete(accountId)
        memoryOnly.update { it - accountId }
        backend?.let { keyring -> withContext(Dispatchers.IO) { keyring.delete(service, accountId) } }
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
            // No keyring (BackendNotSupportedException), or one that cannot load (a missing native library or D-Bus):
            // the documented in-memory mode, never a crash.
            try { JavaKeyringBackend(Keyring.create()) } catch (_: Exception) { null } catch (_: LinkageError) { null },
        )
    }
}

private class JavaKeyringBackend(private val keyring: Keyring) : KeyringBackend {
    override fun get(service: String, account: String): String? =
        try { keyring.getPassword(service, account) } catch (error: PasswordAccessException) {
            // java-keyring uses one exception for a missing entry and a refused access (a locked keyring). Only the
            // first is "no token", recognized by the exact phrases of its backends: Windows ("Password not Found"),
            // the macOS Keychain status text ("could not be found in the keychain") and Secret Service ("No stored
            // credentials match"). Anything else is an error, never a silent sign-out.
            if (MISSING_ENTRY.any { it in error.message.orEmpty().lowercase() }) null else throw error
        }
    override fun set(service: String, account: String, secret: String) = keyring.setPassword(service, account, secret)
    override fun delete(service: String, account: String) {
        try { keyring.deletePassword(service, account) } catch (error: PasswordAccessException) {
            // Already absent is success; a refusal (a locked keyring) is reported, like on read.
            if (MISSING_ENTRY.none { it in error.message.orEmpty().lowercase() }) throw error
        }
    }
}

private val MISSING_ENTRY = listOf("password not found", "could not be found in the keychain", "no stored credentials match")
