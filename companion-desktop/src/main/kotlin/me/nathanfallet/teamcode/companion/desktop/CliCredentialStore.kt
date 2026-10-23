package me.nathanfallet.teamcode.companion.desktop

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import me.nathanfallet.teamcode.config.CliConfig
import me.nathanfallet.teamcode.config.ConfigStore
import me.nathanfallet.teamcode.config.JvmPrivateFiles
import me.nathanfallet.teamcode.config.UserFacingException

/**
 * The CLI's `config.json`, through the same `:config` code as the CLI: written owner-only by atomic rename, replaced and
 * deleted under `config.lock`. A read is served from memory while the file's modification time, size and identity stay
 * the same, so the app follows a `teamcode login` or `logout` in a terminal without reading the file at every request.
 */
class CliConfigFile(val path: String, private val store: ConfigStore = ConfigStore(path, JvmPrivateFiles)) {
    /** What identifies one version of the file; null when there is none. */
    data class Stamp(val modified: Long, val size: Long, val key: Any?)

    private var cached: Pair<Stamp, CliConfig?>? = null

    fun stamp(): Stamp? = try {
        val attributes = Files.readAttributes(Path.of(path), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        Stamp(attributes.lastModifiedTime().toMillis(), attributes.size(), attributes.fileKey())
    } catch (_: NoSuchFileException) { null }

    /** The CLI's sign-in, or null without one. Throws [UserFacingException] for a file the CLI could not read either. */
    @Synchronized
    fun read(): CliConfig? {
        val stamp = stamp() ?: return null.also { cached = null }
        cached?.takeIf { it.first == stamp }?.let { return it.second }
        val config = store.load()
        cached = stamp to config
        return config
    }

    /** As `teamcode login`: saves [config] under the lock and returns the sign-in it replaced, which the caller revokes. */
    @Synchronized
    fun replace(config: CliConfig): CliConfig? = store.replace(config).also { cached = null }

    /** As `teamcode logout`: deletes the file only if it still holds [expected]; false when the CLI signed in again meanwhile. */
    @Synchronized
    fun deleteIf(expected: CliConfig): Boolean = store.deleteIf(expected).also { cached = null }
}

/**
 * Tokens of the desktop's accounts: the CLI's account ([Account.CLI_ACCOUNT_ID]) in `config.json`, shared with the CLI;
 * every other account in the operating system's keyring ([keyring]), as before. [originOf] gives an account's server:
 * a token is only ever handed out for the server the account describes, so a `teamcode login` to another server never
 * sends its token to the previous one while the account is being refreshed.
 */
class CliCredentialStore(
    private val keyring: CredentialStore,
    private val file: CliConfigFile,
    private val originOf: (String) -> String?,
) : CredentialStore {
    /** The CLI token handed out last: signing out deletes `config.json` only if it still holds that one. */
    @Volatile private var served: CliConfig? = null

    override val persistent: Boolean get() = keyring.persistent

    override suspend fun read(accountId: String): String? {
        if (accountId != Account.CLI_ACCOUNT_ID) return keyring.read(accountId)
        val config = withContext(Dispatchers.IO) { file.read() } ?: return null
        val origin = originOf(accountId)
        // Thrown, not null: null would sign the account out, while it only waits to be described again.
        if (origin != null && config.serverUrl != origin) throw IllegalStateException("The CLI is now signed in to another server.")
        served = config
        return config.accessToken
    }

    override suspend fun write(accountId: String, token: String) {
        if (accountId != Account.CLI_ACCOUNT_ID) return keyring.write(accountId, token)
        val origin = originOf(accountId) ?: throw IllegalStateException("The CLI's account has no server.")
        val config = CliConfig(origin, token)
        withContext(Dispatchers.IO) { file.replace(config) }
        served = config
    }

    override suspend fun delete(accountId: String) {
        if (accountId != Account.CLI_ACCOUNT_ID) return keyring.delete(accountId)
        withContext(Dispatchers.IO) {
            val expected = served ?: try { file.read() } catch (_: UserFacingException) { null } ?: return@withContext
            file.deleteIf(expected)
        }
        served = null
    }
}
