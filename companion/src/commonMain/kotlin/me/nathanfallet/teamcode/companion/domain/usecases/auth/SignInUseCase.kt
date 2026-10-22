package me.nathanfallet.teamcode.companion.domain.usecases.auth

import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.LoginSession
import me.nathanfallet.teamcode.companion.domain.models.ServerInfo
import me.nathanfallet.teamcode.companion.domain.models.SignInResult
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import me.nathanfallet.teamcode.companion.domain.services.IdGenerator
import me.nathanfallet.teamcode.companion.domain.services.InstallationIdentity

/**
 * Password login, as `teamcode login`: the installation id registers the app as a device. The password is never
 * stored; the token goes to the [CredentialStore] and the account metadata to the [AccountRepository]. Signing in
 * again as the same member on the same server replaces that account instead of adding a second one.
 */
class SignInUseCase(
    private val auth: AuthRepository,
    private val accounts: AccountRepository,
    private val credentials: CredentialStore,
    private val installation: InstallationIdentity,
    private val ids: IdGenerator,
) {
    suspend operator fun invoke(server: ServerInfo, username: String, password: String, deviceName: String): SignInResult {
        val name = username.trim()
        val device = validDeviceName(deviceName)
        val session = auth.login(server.origin, name, password, device, installation.id())
        if (session.member.passwordChangeRequired) return SignInResult.PasswordChangeRequired(server, name, device, session.accessToken)
        return SignInResult.SignedIn(store(server, session, device))
    }

    private suspend fun store(server: ServerInfo, session: LoginSession, deviceName: String): Account {
        val existing = accounts.accounts.value.firstOrNull { it.origin == server.origin && it.memberId == session.member.id }
        val account = Account(
            id = existing?.id ?: ids.newId(),
            origin = server.origin,
            username = session.member.username,
            memberId = session.member.id,
            role = session.member.role,
            // The server keeps the name of a known installation (it may have been renamed since): so does the app.
            deviceName = existing?.deviceName ?: deviceName,
            expiresAt = session.expiresAt,
            serverVersion = server.serverVersion,
        )
        // The token first: an account is never listed as signed in without its credential.
        try {
            credentials.write(account.id, session.accessToken)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The session exists on the server but cannot be kept: it shows on the Devices page until it expires.
            throw CompanionException.CredentialStorage()
        }
        accounts.save(account)
        return account
    }
}

/** A device name the server accepts: 1 to 120 characters without control characters. */
fun validDeviceName(name: String): String {
    val trimmed = name.trim()
    require(trimmed.length in 1..120 && trimmed.none { it.isISOControl() }) { "Use 1 to 120 characters without control characters." }
    return trimmed
}
