package me.nathanfallet.teamcode.companion.domain.usecases.auth

import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository

/** Renames this installation on the account's server (its Devices page) and keeps the new name locally. */
class RenameDeviceUseCase(
    private val auth: AuthRepository,
    private val accounts: AccountRepository,
) {
    suspend operator fun invoke(accountId: String, name: String): Account {
        val account = accounts.accounts.value.first { it.id == accountId }
        val kept = auth.renameCurrentDevice(accountId, validDeviceName(name))
        return account.copy(deviceName = kept).also(accounts::update)
    }
}
