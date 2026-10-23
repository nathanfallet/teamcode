package me.nathanfallet.teamcode.companion.infrastructure.repositories

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository

/** Capabilities per account, read once and again on refresh (each time a list comes back to the foreground). */
class CachedFeaturesRepository(private val auth: AuthRepository, private val accounts: AccountRepository) : FeaturesRepository {
    private val lock = Mutex()
    private val cache = mutableMapOf<String, Pair<String, Set<String>>>()

    override suspend fun features(accountId: String, refresh: Boolean): Set<String> {
        val origin = accounts.accounts.value.firstOrNull { it.id == accountId }?.origin ?: throw CompanionException.Unauthorized()
        if (!refresh) lock.withLock { cache[accountId]?.takeIf { it.first == origin }?.let { return it.second } }
        // With the account's token: the server announces its capabilities to signed-in callers only.
        val features = auth.capabilities(accountId).features
        lock.withLock { cache[accountId] = origin to features }
        return features
    }
}
