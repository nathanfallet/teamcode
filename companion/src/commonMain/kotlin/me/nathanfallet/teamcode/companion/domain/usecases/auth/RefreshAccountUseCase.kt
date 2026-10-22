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
            // Only if nothing signed the account in again while the call was in flight (its session would be new).
            val current = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return null
            return if (current.signedIn && current.expiresAt == account.expiresAt) signOutLocally(current) else current
        }
        // Read after the call: the account may have been renamed, signed out or removed meanwhile.
        val current = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return null
        if (!current.signedIn) return current
        val updated = current.copy(username = member.username, role = member.role)
        if (updated != current) accounts.update(updated)
        return updated
    }

    private suspend fun signOutLocally(account: Account): Account {
        // The token is dead on the server either way; a store refusing the delete must not keep the account signed in.
        try { credentials.delete(account.id) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
        auth.release(account.id)
        // Read again after the delete: a sign-in that completed meanwhile (new session) is not undone.
        val current = accounts.accounts.value.firstOrNull { it.id == account.id } ?: return account.copy(signedIn = false)
        if (current.expiresAt != account.expiresAt) return current
        val signedOut = current.copy(signedIn = false)
        accounts.update(signedOut)
        return signedOut
    }
}
