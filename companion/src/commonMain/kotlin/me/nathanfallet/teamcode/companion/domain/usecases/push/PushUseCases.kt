package me.nathanfallet.teamcode.companion.domain.usecases.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.PushRepository
import me.nathanfallet.teamcode.companion.domain.services.PushPlatform

/**
 * Keeps every signed-in account's server told where to push: at each start of the app, when FCM rotates the token, and
 * when an account signs in. Each server receives the same token and key; a notification names its server ([origin]) and
 * member, so a phone signed in to several accounts tells them apart. A failure is left for the next start or change: the
 * server keeps the last token it had.
 */
class SyncPushUseCase(
    private val accounts: AccountRepository,
    private val features: FeaturesRepository,
    private val push: PushRepository,
    private val platform: PushPlatform,
) {
    /** Runs until cancelled. */
    suspend operator fun invoke() {
        val registered = mutableSetOf<Pair<String, String>>()
        combine(platform.token, accounts.accounts) { token, list -> token to list.filter { it.signedIn }.map { it.id }.toSet() }
            .distinctUntilChanged()
            .collect { (token, ids) ->
                if (token == null) return@collect
                registered.retainAll { it.second == token && it.first in ids }
                for (id in ids) {
                    if (id to token in registered) continue
                    if (register(id, token)) registered += id to token
                }
            }
    }

    private suspend fun register(accountId: String, token: String): Boolean = try {
        if (Features.PUSH !in features.features(accountId)) false
        else { push.register(accountId, token, platform.publicKey()); true }
    } catch (error: CancellationException) { throw error } catch (_: Exception) { false }
}

/** Before a sign-out: the server stops pushing to this phone for the account. Best effort, never blocks the sign-out. */
class ClearPushUseCase(private val features: FeaturesRepository, private val push: PushRepository) {
    suspend operator fun invoke(accountId: String) {
        try {
            if (Features.PUSH in features.features(accountId)) push.clear(accountId)
        } catch (error: CancellationException) { throw error } catch (_: Exception) { }
    }
}
