package me.nathanfallet.teamcode.companion.infrastructure.credentials

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore

/** Tokens for this run of the app only: the fallback when the device has no usable secure store. */
class MemoryCredentialStore : CredentialStore {
    private val lock = Mutex()
    private val tokens = mutableMapOf<String, String>()
    override val persistent = false
    override suspend fun read(accountId: String): String? = lock.withLock { tokens[accountId] }
    override suspend fun write(accountId: String, token: String) = lock.withLock { tokens[accountId] = token }
    override suspend fun delete(accountId: String) = lock.withLock { tokens.remove(accountId); Unit }
}
