package me.nathanfallet.teamcode.companion.domain.usecases.auth

import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import kotlinx.coroutines.CancellationException

/** Renames this installation on the account's server (its Devices page) and keeps the new name locally. */
class RenameDeviceUseCase(
    private val auth: AuthRepository,
    private val accounts: AccountRepository,
    private val credentials: CredentialStore,
) {
    suspend operator fun invoke(accountId: String, name: String): Account {
        val before = accounts.accounts.value.firstOrNull { it.id == accountId }
        val kept = try {
            auth.renameCurrentDevice(accountId, validDeviceName(name))
        } catch (error: CompanionException.Unauthorized) {
            // The device was revoked on the server: the account is signed out here too, as a refresh would, unless a
            // sign-in completed meanwhile (a new session).
            val current = accounts.accounts.value.firstOrNull { it.id == accountId }
            if (current != null && current.signedIn && current.expiresAt == before?.expiresAt) {
                accounts.update(current.copy(signedIn = false))
                auth.release(accountId)
                try { credentials.delete(accountId) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
            }
            throw error
        }
        // Read after the call: a refresh may have changed the account meanwhile (signed out, new role).
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: throw CompanionException.Unauthorized()
        return account.copy(deviceName = kept).also(accounts::update)
    }
}
