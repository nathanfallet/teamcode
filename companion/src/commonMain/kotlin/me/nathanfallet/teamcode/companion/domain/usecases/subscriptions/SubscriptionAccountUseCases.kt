package me.nathanfallet.teamcode.companion.domain.usecases.subscriptions

import me.nathanfallet.teamcode.companion.domain.models.AddedSubscriptionAccount
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.EnrollmentAccess
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionAccount
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionAccountType
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionSignIn
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SubscriptionAccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard

/** What the subscription accounts screen shows: whether adding is allowed, and the pool for an administrator (null otherwise). */
data class SubscriptionAccountsOverview(val access: EnrollmentAccess, val accounts: List<SubscriptionAccount>?)

/** The enrollment check first (as `teamcode accounts add`), then the pool, which only administrators may read. */
class LoadSubscriptionAccountsUseCase(
    private val repository: SubscriptionAccountRepository,
    private val accounts: AccountRepository,
    private val guard: AccountGuard,
) {
    val types: List<SubscriptionAccountType> get() = repository.types

    suspend operator fun invoke(accountId: String): SubscriptionAccountsOverview {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId }?.takeIf { it.signedIn } ?: throw CompanionException.Unauthorized()
        val access = guard(accountId) { repository.access(accountId) }
        val list = if (account.isAdministrator) guard(accountId) { repository.accounts(accountId) } else null
        return SubscriptionAccountsOverview(access, list)
    }
}

/**
 * Starts a provider sign-in, refused before any browser opens when the server would refuse the account: a kind it cannot
 * hold, or a member it does not let add accounts (asked again, the setting may have changed since the screen loaded).
 */
class StartSubscriptionSignInUseCase(
    private val repository: SubscriptionAccountRepository,
    private val guard: AccountGuard,
) {
    suspend operator fun invoke(accountId: String, type: SubscriptionAccountType): SubscriptionSignIn {
        if (!type.available || repository.types.none { it.id == type.id && it.available }) throw CompanionException.UnavailableAccountType()
        when (guard(accountId) { repository.access(accountId) }) {
            EnrollmentAccess.ALLOWED -> Unit
            EnrollmentAccess.ADMINISTRATORS_ONLY -> throw CompanionException.EnrollmentRefused(CompanionException.EnrollmentRefused.ENROLLMENT_FORBIDDEN)
            EnrollmentAccess.SESSION_CANNOT_ENROLL -> throw CompanionException.EnrollmentRefused(CompanionException.EnrollmentRefused.SESSION_CANNOT_ENROLL)
        }
        return repository.startSignIn(accountId, type)
    }
}

/** Sends the pasted code: exchanged at the provider on this device, then the credentials enrolled on the server. */
class CompleteSubscriptionSignInUseCase(private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, signIn: SubscriptionSignIn, paste: String): AddedSubscriptionAccount {
        val text = paste.trim()
        if (text.isEmpty()) throw CompanionException.ProviderSignIn(restart = false, signIn.pasteInstructions)
        return guard(accountId) { signIn.complete(text) }
    }
}
