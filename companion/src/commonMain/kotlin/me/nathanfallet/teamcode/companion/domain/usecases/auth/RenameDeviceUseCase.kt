package me.nathanfallet.teamcode.companion.domain.usecases.auth

import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository

/** Renames this installation on the account's server (its Devices page) and keeps the new name locally. */
class RenameDeviceUseCase(
    private val auth: AuthRepository,
    private val accounts: AccountRepository,
) {
    suspend operator fun invoke(accountId: String, name: String): Account {
        val kept = try {
            auth.renameCurrentDevice(accountId, validDeviceName(name))
        } catch (error: CompanionException.Unauthorized) {
            // The device was revoked on the server: the account is signed out here too, as a refresh would.
            accounts.accounts.value.firstOrNull { it.id == accountId }?.takeIf { it.signedIn }?.let { accounts.update(it.copy(signedIn = false)) }
            auth.release(accountId)
            throw error
        }
        // Read after the call: a refresh may have changed the account meanwhile (signed out, new role).
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: throw CompanionException.Unauthorized()
        return account.copy(deviceName = kept).also(accounts::update)
    }
}
