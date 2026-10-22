package me.nathanfallet.teamcode.companion.infrastructure.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.UserAgent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo

/**
 * One [TeamCodeClient] per account, created on first use and closed on sign-out. Its token provider reads the
 * [CredentialStore] on every call, so a token written or deleted there takes effect at once; the SDK itself never
 * holds a keychain. Every HTTP client it creates is independently owned, as the SDK requires.
 */
class TeamCodeClientProvider(
    private val engine: HttpClientEngineFactory<*>,
    private val credentials: CredentialStore,
    private val platform: PlatformInfo,
) {
    private val lock = Mutex()
    private val clients = mutableMapOf<String, Pair<String, TeamCodeClient>>()

    suspend fun forAccount(accountId: String, origin: String): TeamCodeClient = lock.withLock {
        clients[accountId]?.takeIf { it.first == origin }?.second ?: create(origin) { credentials.read(accountId) }.also {
            clients.remove(accountId)?.second?.close()
            clients[accountId] = origin to it
        }
    }

    /** A client for one call before an account exists (capabilities, login, the first password change); close it after use. */
    fun detached(origin: String, token: String? = null): TeamCodeClient = create(origin) { token }

    suspend fun release(accountId: String) {
        lock.withLock { clients.remove(accountId) }?.second?.close()
    }

    private fun create(origin: String, token: suspend () -> String?) = TeamCodeClient(origin, token) { configure ->
        HttpClient(engine) {
            configure(this)
            install(UserAgent) { agent = "TeamCode-Companion/${platform.appVersion} (${platform.platform})" }
        }
    }
}
