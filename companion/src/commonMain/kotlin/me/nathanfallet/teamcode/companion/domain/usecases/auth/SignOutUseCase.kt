package me.nathanfallet.teamcode.companion.domain.usecases.auth

import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore

/**
 * Revokes the login session on the server, then forgets the token and the account. Offline (or refused), the local
 * sign-out still happens; the result says the server session was not revoked, and it expires on its own.
 */
class SignOutUseCase(
    private val auth: AuthRepository,
    private val accounts: AccountRepository,
    private val credentials: CredentialStore,
) {
    /** Returns true when the server confirmed the revocation. */
    suspend operator fun invoke(accountId: String): Boolean {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return true
        val revoked = if (!account.signedIn) true else try {
            auth.logout(accountId)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: CompanionException.Unauthorized) {
            true // The server no longer knows this session: nothing left to revoke.
        } catch (_: Exception) {
            false
        }
        try {
            credentials.delete(accountId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The account is removed anyway, as the member asked. A token left behind in a refusing secure store is
            // revoked when the server confirmed the logout; otherwise it lasts until its expiry, like an offline
            // sign-out's session.
        }
        auth.release(accountId)
        accounts.remove(accountId)
        return revoked
    }
}
