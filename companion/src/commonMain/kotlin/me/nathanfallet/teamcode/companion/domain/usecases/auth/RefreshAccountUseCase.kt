package me.nathanfallet.teamcode.companion.domain.usecases.auth

import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore

/**
 * Re-reads the member behind an account (`GET /auth/me`). An expired token, a missing one or a `401` (device revoked on
 * the Devices page, password changed elsewhere) marks the account signed out and forgets the token, once: the app then
 * asks for the password. Other failures (offline) leave the account as it is and are thrown.
 */
class RefreshAccountUseCase(
    private val auth: AuthRepository,
    private val accounts: AccountRepository,
    private val credentials: CredentialStore,
    private val clock: Clock,
) {
    suspend operator fun invoke(accountId: String): Account? {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return null
        if (!account.signedIn) return account
        val token = try {
            credentials.read(accountId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A locked or refusing secure store: the token may well be there, so the account stays signed in.
            throw CompanionException.CredentialStorage()
        }
        if (account.expiresAt <= clock.nowMillis() || token == null) return signOutLocally(account)
        val member = try {
            auth.me(accountId)
        } catch (_: CompanionException.Unauthorized) {
            return signOutLocally(account)
        }
        // Read after the call: the account may have been renamed, signed out or removed meanwhile.
        val current = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return null
        if (!current.signedIn) return current
        val updated = current.copy(username = member.username, role = member.role)
        if (updated != current) accounts.update(updated)
        return updated
    }

    private suspend fun signOutLocally(account: Account): Account {
        credentials.delete(account.id)
        auth.release(account.id)
        val signedOut = account.copy(signedIn = false)
        accounts.update(signedOut)
        return signedOut
    }
}
