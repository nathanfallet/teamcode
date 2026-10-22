package me.nathanfallet.teamcode.companion.domain.repositories

import me.nathanfallet.teamcode.companion.domain.models.LoginSession
import me.nathanfallet.teamcode.companion.domain.models.Member
import me.nathanfallet.teamcode.companion.domain.models.ServerInfo

/**
 * Authentication calls of a TeamCode server. Failures are [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s.
 * Calls with an explicit token run before an account exists; the others use the token stored for the account.
 */
interface AuthRepository {
    suspend fun serverInfo(origin: String): ServerInfo
    suspend fun login(origin: String, username: String, password: String, deviceName: String, installationId: String): LoginSession
    suspend fun changePassword(origin: String, token: String, current: String, replacement: String)
    suspend fun me(accountId: String): Member
    /** Revokes the account's login session on the server. */
    suspend fun logout(accountId: String)
    /** Renames this installation on the server's Devices page; returns the name the server kept. */
    suspend fun renameCurrentDevice(accountId: String, name: String): String
    /** Closes what was opened for the account (its HTTP client). */
    fun release(accountId: String)
}
