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
    /** Whether the server session is gone, and if not, why. */
    enum class Result { REVOKED, SERVER_UNREACHABLE, STORAGE_REFUSED }

    suspend operator fun invoke(accountId: String): Result {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return Result.REVOKED
        var result = if (!account.signedIn) Result.REVOKED else try {
            auth.logout(accountId)
            Result.REVOKED
        } catch (error: CancellationException) {
            throw error
        } catch (_: CompanionException.Unauthorized) {
            Result.REVOKED // The server no longer knows this session: nothing left to revoke.
        } catch (_: CompanionException.CredentialStorage) {
            Result.STORAGE_REFUSED // The token could not even be read to revoke it.
        } catch (_: Exception) {
            Result.SERVER_UNREACHABLE
        }
        try {
            credentials.delete(accountId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The account is removed anyway, as the member asked. A token left behind in a refusing secure store is
            // harmless when the server confirmed the logout; otherwise it lasts until its expiry, like an offline
            // sign-out's session, and the member is told to revoke it.
            if (result != Result.REVOKED) result = Result.STORAGE_REFUSED
        }
        auth.release(accountId)
        accounts.remove(accountId)
        return result
    }
}
