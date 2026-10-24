package me.nathanfallet.teamcode.companion.domain.usecases.auth

import me.nathanfallet.teamcode.companion.domain.models.InvalidInput
import me.nathanfallet.teamcode.companion.domain.models.requireInput
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
 * Password login, as `teamcode login`: the installation id registers the app as a device. The server's capabilities
 * are read with the new session, and a server whose API this app does not speak is refused before anything is stored. The password is never
 * stored; the token goes to the [CredentialStore] and the account metadata to the [AccountRepository]. Signing in
 * again as the same member on the same server replaces that account instead of adding a second one, and revokes the
 * session it replaces: the server keeps every session of a device until it is logged out or expires.
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
        // Read with the new session: the server announces its version and capabilities to signed-in callers only.
        val capabilities = try {
            auth.capabilities(server.origin, session.accessToken).also { requireSupportedApi(it.apiVersion) }
        } catch (error: Throwable) {
            // The session is not kept (an API this app does not speak, or no answer): revoked instead of left valid.
            auth.abandon(server.origin, session.accessToken)
            throw error
        }
        return SignInResult.SignedIn(store(server, session, device, capabilities.serverVersion))
    }

    private suspend fun store(server: ServerInfo, session: LoginSession, deviceName: String, serverVersion: String): Account {
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
            serverVersion = serverVersion,
        )
        val replaced = existing?.let {
            try { credentials.read(it.id) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        }
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
        replaced?.takeIf { it != session.accessToken }?.let { auth.abandon(server.origin, it) }
        return account
    }
}

/** A device name the server accepts: 1 to 120 characters without control characters. */
fun validDeviceName(name: String): String {
    val trimmed = name.trim()
    requireInput(trimmed.length in 1..120 && trimmed.none { it.isISOControl() }, InvalidInput.Problem.DEVICE_NAME, message = "Use 1 to 120 characters without control characters.")
    return trimmed
}
