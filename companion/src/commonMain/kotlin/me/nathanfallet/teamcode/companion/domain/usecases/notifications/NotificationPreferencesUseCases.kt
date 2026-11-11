package me.nathanfallet.teamcode.companion.domain.usecases.notifications

import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.NotificationKind
import me.nathanfallet.teamcode.companion.domain.models.NotificationPreferences
import me.nathanfallet.teamcode.companion.domain.models.PushTestOutcome
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.NotificationPreferencesRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard

/**
 * Whether Settings offers the notifications screen: a signed-in account on a server with the push channel. Offered on
 * every platform: the switches are the member's and hold on all their phones, the desktop included as a place to set them.
 */
class NotificationPreferencesAvailabilityUseCase(private val accounts: AccountRepository, private val features: FeaturesRepository) {
    suspend fun available(accountId: String): Boolean {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return false
        if (!account.signedIn) return false
        return try {
            Features.PUSH in features.features(accountId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Unknown for now (offline): hidden rather than offered and refused.
            false
        }
    }
}

/** The switches, and whether this member may send a test (any member on a server that says so, administrators always). */
class LoadNotificationPreferencesUseCase(
    private val repository: NotificationPreferencesRepository,
    private val accounts: AccountRepository,
    private val features: FeaturesRepository,
    private val guard: AccountGuard,
) {
    suspend operator fun invoke(accountId: String): NotificationPreferences {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId }?.takeIf { it.signedIn } ?: throw CompanionException.Unauthorized()
        return guard(accountId) {
            val offered = features.features(accountId, refresh = true)
            if (Features.PUSH !in offered) throw CompanionException.Unsupported(Features.PUSH)
            NotificationPreferences(repository.switches(accountId), canTest = Features.PUSH_TEST in offered || account.isAdministrator)
        }
    }
}

/** Turns one kind on or off for the member; the answer is every switch as the server holds them. */
class SetNotificationPreferenceUseCase(private val repository: NotificationPreferencesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, kind: NotificationKind, enabled: Boolean): Map<NotificationKind, Boolean> =
        guard(accountId) { repository.set(accountId, kind, enabled) }
}

/** Sends a test notification to the member's own phones, at once. */
class SendTestPushUseCase(private val repository: NotificationPreferencesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String): PushTestOutcome = guard(accountId) { repository.test(accountId) }
}
