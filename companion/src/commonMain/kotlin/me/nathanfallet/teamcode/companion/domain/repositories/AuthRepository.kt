package me.nathanfallet.teamcode.companion.domain.repositories

import me.nathanfallet.teamcode.companion.domain.models.LoginSession
import me.nathanfallet.teamcode.companion.domain.models.Member
import me.nathanfallet.teamcode.companion.domain.models.ServerCapabilities
import me.nathanfallet.teamcode.companion.domain.models.ServerInfo

/**
 * Authentication calls of a TeamCode server. Failures are [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s.
 * Calls with an explicit token run before an account exists; the others use the token stored for the account.
 */
interface AuthRepository {
    /**
     * Checks, without any credential, that [origin] answers like a TeamCode server. Its capabilities are behind
     * authentication: an anonymous refusal carrying a TeamCode error code is a TeamCode server asking for a sign-in.
     */
    suspend fun discover(origin: String): ServerInfo

    /** `GET /api/v1/capabilities` with a session that was just created and is not stored yet. */
    suspend fun capabilities(origin: String, token: String): ServerCapabilities

    /** `GET /api/v1/capabilities` with the token stored for the account. */
    suspend fun capabilities(accountId: String): ServerCapabilities

    suspend fun login(origin: String, username: String, password: String, deviceName: String, installationId: String): LoginSession
    suspend fun changePassword(origin: String, token: String, current: String, replacement: String)
    suspend fun me(accountId: String): Member
    /** Revokes the account's login session on the server. */
    suspend fun logout(accountId: String)
    /** Renames this installation on the server's Devices page; returns the name the server kept. */
    suspend fun renameCurrentDevice(accountId: String, name: String): String
    /** Closes what was opened for the account (its HTTP client). */
    suspend fun release(accountId: String)

    /**
     * Revokes a session that was never stored (the temporary one a password change starts from), best-effort and in
     * the background: the screen that held it is going away.
     */
    fun abandon(origin: String, token: String)
}
