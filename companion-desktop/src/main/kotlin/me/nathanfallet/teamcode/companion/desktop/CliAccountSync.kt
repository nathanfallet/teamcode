package me.nathanfallet.teamcode.companion.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.client.TeamCodeException
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository
import me.nathanfallet.teamcode.companion.domain.services.CliAccountLink
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.config.CliConfig
import me.nathanfallet.teamcode.config.UserFacingException

/** The member and server behind the CLI's token, read with that token. */
data class CliIdentity(val username: String, val memberId: String, val role: String, val serverVersion: String, val deviceName: String?)

/**
 * Keeps the desktop's CLI account ([Account.CLI_ACCOUNT_ID]) equal to what `config.json` says: added when the CLI signs
 * in (in a terminal or from the app), described again when it signs in elsewhere, removed when it signs out. The file is
 * checked every [pollMillis]: its modification time, size and identity, a few bytes of metadata. A member already
 * listed with the same server is merged into the CLI account, so one member and server stay one account.
 *
 * It is also the [CliAccountLink]: a sign-in in the app can become the CLI's while the CLI has none.
 */
class CliAccountSync(
    private val file: CliConfigFile,
    private val accounts: AccountRepository,
    private val credentials: CredentialStore,
    private val auth: AuthRepository,
    private val clock: Clock,
    private val describe: suspend (CliConfig) -> CliIdentity,
    private val defaultDeviceName: String,
    private val pollMillis: Long = 2_000,
    private val retryMillis: Long = 60_000,
) : CliAccountLink {
    private val mutex = Mutex()
    private var seen: CliFileState? = null
    private var retryAt = 0L

    /** What was last applied: the file's version and whether describing it must be tried again. */
    private data class CliFileState(val stamp: CliConfigFile.Stamp?, val config: CliConfig?)

    override val configPath: String get() = file.path

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            try { sync() } catch (error: CancellationException) { throw error } catch (_: Exception) { /* The next tick tries again. */ }
            delay(pollMillis)
        }
    }

    /** Applies the file once if it changed since the last call (or a failed description is due again). */
    suspend fun sync() = mutex.withLock {
        val stamp = file.stamp()
        val previous = seen
        if (previous != null && previous.stamp == stamp && clock.nowMillis() < retryAt) return@withLock
        if (previous != null && previous.stamp == stamp && retryAt == 0L) return@withLock
        val config = try { withContext(Dispatchers.IO) { file.read() } } catch (_: UserFacingException) {
            // A file the CLI cannot read either: the CLI says how to replace it; the account stays as it was.
            seen = CliFileState(stamp, null); retryAt = 0L
            return@withLock
        }
        apply(config)
        seen = CliFileState(stamp, config)
    }

    private suspend fun apply(config: CliConfig?) {
        val current = accounts.accounts.value.firstOrNull { it.usedByCli }
        if (config == null) {
            retryAt = 0L
            if (current != null) {
                auth.release(Account.CLI_ACCOUNT_ID)
                accounts.remove(Account.CLI_ACCOUNT_ID)
            }
            return
        }
        val identity = try {
            describe(config)
        } catch (error: CancellationException) {
            throw error
        } catch (error: TeamCodeException) {
            if (error.statusCode == 401) {
                // A token the server no longer knows: listed as signed out if the account exists, retried once the file changes.
                retryAt = 0L
                current?.takeIf { it.origin == config.serverUrl && it.signedIn }?.let { accounts.update(it.copy(signedIn = false)) }
            } else retryAt = clock.nowMillis() + retryMillis
            return
        } catch (_: Exception) {
            retryAt = clock.nowMillis() + retryMillis
            return
        }
        retryAt = 0L
        if (current != null && current.origin != config.serverUrl) auth.release(Account.CLI_ACCOUNT_ID)
        val account = Account(
            id = Account.CLI_ACCOUNT_ID,
            origin = config.serverUrl,
            username = identity.username,
            memberId = identity.memberId,
            role = identity.role,
            deviceName = identity.deviceName ?: current?.deviceName ?: defaultDeviceName,
            // config.json has no expiry: the server's 401 is what signs the account out.
            expiresAt = Long.MAX_VALUE,
            serverVersion = identity.serverVersion,
        )
        mergeDuplicate(account, config.accessToken)
        if (current == null) {
            val active = accounts.activeAccountId.value
            accounts.save(account)
            // The CLI's account joins the list; the account the member was looking at stays the active one.
            active?.let(accounts::setActive)
        } else accounts.update(account)
    }

    /** The same member on the same server, signed in in the app before the CLI was: one account, the CLI's. */
    private suspend fun mergeDuplicate(account: Account, cliToken: String) {
        val duplicate = accounts.accounts.value.firstOrNull { !it.usedByCli && it.origin == account.origin && it.memberId == account.memberId } ?: return
        val token = try { credentials.read(duplicate.id) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        try { credentials.delete(duplicate.id) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
        auth.release(duplicate.id)
        val wasActive = accounts.activeAccountId.value == duplicate.id
        accounts.remove(duplicate.id)
        if (wasActive) accounts.save(account)
        // Its own login session is no longer needed: revoked, unless it is the very session the CLI now uses.
        token?.takeIf { it != cliToken }?.let { auth.abandon(duplicate.origin, it) }
    }

    override suspend fun canLink(): Boolean = withContext(Dispatchers.IO) {
        try { file.stamp() == null && file.read() == null } catch (_: UserFacingException) { false }
    }

    override suspend fun link(accountId: String) = mutex.withLock {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return@withLock
        if (account.usedByCli) return@withLock
        val token = credentials.read(accountId) ?: return@withLock
        val config = CliConfig(account.origin, token)
        val replaced = withContext(Dispatchers.IO) { file.replace(config) }
        // As teamcode login: a session the file held before (a login that raced this one) is revoked.
        replaced?.takeIf { it.accessToken != token }?.let { auth.abandon(it.serverUrl, it.accessToken) }
        accounts.save(account.copy(id = Account.CLI_ACCOUNT_ID, expiresAt = Long.MAX_VALUE))
        accounts.remove(accountId)
        auth.release(accountId)
        try { credentials.delete(accountId) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
        seen = CliFileState(file.stamp(), config)
        retryAt = 0L
    }

    companion object {
        /** Reads the member, the server's version and this device's name with the CLI's token, then closes the client. */
        fun describeWith(clients: TeamCodeClientProvider): suspend (CliConfig) -> CliIdentity = { config ->
            clients.detached(config.serverUrl, config.accessToken).use { client ->
                val member = client.me()
                val capabilities = client.capabilities()
                val device = try { client.devices().firstOrNull { it.current }?.name } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
                CliIdentity(member.username, member.id, member.role, capabilities.serverVersion, device)
            }
        }
    }
}
