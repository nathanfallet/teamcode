package me.nathanfallet.teamcode.companion.infrastructure.repositories

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.api.models.Capabilities
import me.nathanfallet.teamcode.api.models.LoginResponse
import me.nathanfallet.teamcode.api.models.MemberView
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.LoginSession
import me.nathanfallet.teamcode.companion.domain.models.Member
import me.nathanfallet.teamcode.companion.domain.models.ServerCapabilities
import me.nathanfallet.teamcode.companion.domain.models.ServerInfo
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider

/** [AuthRepository] over the typed client; maps its DTOs and failures to the domain's, never forwarding a server message. */
class RemoteAuthRepository(
    private val clients: TeamCodeClientProvider,
    private val calls: AccountCalls,
    private val scope: CoroutineScope,
) : AuthRepository {
    override suspend fun discover(origin: String): ServerInfo = detached(origin) { client ->
        val capabilities = try {
            client.capabilities()
        } catch (error: TeamCodeException) {
            // Every TeamCode server keeps its capabilities behind authentication and refuses an anonymous call with a 401
            // carrying its error code: a TeamCode server, the sign-in comes next. Never "signed out": there is no session yet.
            if (error.statusCode == 401) {
                if (error.code == AUTHENTICATION_REQUIRED) return@detached ServerInfo(origin)
                throw CompanionException.NotTeamCode()
            }
            // TeamCode serves this route without redirecting and gives every refusal an error code: a redirect (the
            // client never follows one), a 404, or another 4xx without a code is another server. A 5xx without a code
            // is more likely a proxy in front of a TeamCode server that is down: reported as such, not as "not TeamCode".
            if (error.statusCode in 300..399 || error.statusCode == 404 || error.statusCode in 400..499 && error.statusCode != 429 && error.code == null) throw CompanionException.NotTeamCode()
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A page that is not TeamCode's JSON: Ktor wraps the decoding failure in its own conversion exception.
            if (error.isDecodingFailure()) throw CompanionException.NotTeamCode()
            throw error
        }
        // A server that announces itself to anonymous callers: its API version is checked before any password is asked.
        ServerInfo(origin, capabilities.apiVersion)
    }

    override suspend fun capabilities(origin: String, token: String): ServerCapabilities =
        detached(origin, token) { client -> client.capabilities().domain() }

    override suspend fun capabilities(accountId: String): ServerCapabilities = withAccount(accountId) { it.capabilities().domain() }

    override suspend fun login(origin: String, username: String, password: String, deviceName: String, installationId: String): LoginSession =
        detached(origin, credentialsFailure = true) { client -> client.login(username, password, deviceName, installationId).domain() }

    override suspend fun changePassword(origin: String, token: String, current: String, replacement: String) {
        detached(origin, token, credentialsFailure = true) { client -> client.changePassword(current, replacement) }
    }

    override suspend fun me(accountId: String): Member = withAccount(accountId) { it.me().domain() }

    override suspend fun logout(accountId: String) {
        withAccount(accountId) { it.logout() }
    }

    override suspend fun renameCurrentDevice(accountId: String, name: String): String = withAccount(accountId) { client ->
        val current = client.devices().firstOrNull { it.current } ?: throw CompanionException.Server(404, "device_not_found")
        client.renameDevice(current.id, name).name
    }

    // Closed before returning: a later call for this account can never get the client being closed.
    override suspend fun release(accountId: String) = clients.release(accountId)

    override fun abandon(origin: String, token: String) {
        scope.launch {
            try {
                detached(origin, token) { it.logout() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Unreachable or already ended: the session expires on its own and is listed on the Devices page.
            }
        }
    }

    private suspend fun <T> withAccount(accountId: String, call: suspend (TeamCodeClient) -> T): T = calls.withAccount(accountId, call = call)

    private suspend fun <T> detached(origin: String, token: String? = null, credentialsFailure: Boolean = false, call: suspend (TeamCodeClient) -> T): T =
        translateFailures(credentialsFailure) { clients.detached(origin, token).use { call(it) } }
}

/** The error code of every TeamCode server for a call without a credential. */
private const val AUTHENTICATION_REQUIRED = "authentication_required"

internal fun MemberView.domain() = Member(id, username, role, passwordChangeRequired)
internal fun LoginResponse.domain() = LoginSession(accessToken, expiresAt, member.domain())
internal fun Capabilities.domain() = ServerCapabilities(serverVersion, apiVersion, features.toSet())
