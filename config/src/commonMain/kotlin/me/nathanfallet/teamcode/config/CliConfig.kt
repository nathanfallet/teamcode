package me.nathanfallet.teamcode.config

import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/** The account of the CLI on this machine: `config.json`, read and written by the CLI and the desktop app alike. */
@Serializable
class CliConfig(val serverUrl: String, val accessToken: String) {
    init {
        validateServerUrl(serverUrl)
        if (accessToken.isBlank()) throw UserFacingException("An access token is required. Run teamcode login, or pass a token on stdin to teamcode configure.")
    }
    override fun toString(): String = "CliConfig(serverUrl=$serverUrl, accessToken=[REDACTED])"
}

/** Messages are TeamCode's own and never repeat the value, which may be a mistyped secret. */
fun validateServerUrl(value: String): String {
    fun refuse(message: String): Nothing = throw UserFacingException(message)
    if (value.isBlank()) refuse("A TeamCode server URL is required, for example https://teamcode.example.com.")
    val url = try { Url(value) } catch (error: kotlin.coroutines.cancellation.CancellationException) { throw error }
        catch (_: Exception) { refuse("The TeamCode server URL is not a valid URL. Use an origin such as https://teamcode.example.com.") }
    if (url.protocol != URLProtocol.HTTPS && !(url.protocol == URLProtocol.HTTP && url.host in setOf("localhost", "127.0.0.1", "::1")))
        refuse("Use HTTPS for the TeamCode server URL; unencrypted HTTP is allowed only on loopback.")
    if (!url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() || url.encodedPath !in setOf("", "/") ||
        !url.parameters.isEmpty() || url.fragment.isNotEmpty()) refuse("Use a server origin without credentials, path, query or fragment, such as https://teamcode.example.com.")
    return value.trimEnd('/')
}

/** `$XDG_CONFIG_HOME/teamcode/config.json`, else `~/.config/teamcode/config.json` (`USERPROFILE` when `HOME` is unset). */
fun configPath(env: (String) -> String?): String {
    val root = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: run {
        val home = env("HOME") ?: env("USERPROFILE") ?: throw UserFacingException("Cannot locate your home directory: set HOME (USERPROFILE on Windows) or XDG_CONFIG_HOME.")
        "$home/.config"
    }
    if (!root.toPath().isAbsolute) throw UserFacingException("XDG_CONFIG_HOME must be an absolute path.")
    return "$root/teamcode/config.json"
}

/** A file next to `config.json` in the same private directory (`installation-id`, `config.lock`). */
fun configSibling(configPath: String, name: String): String = requireNotNull(configPath.toPath().parent).resolve(name).toString()

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class ConfigStore(val path: String, private val files: PrivateFiles) {
    val lockPath: String get() = configSibling(path, "config.lock")

    fun load(): CliConfig? {
        val file = path.toPath()
        val metadata = FileSystem.SYSTEM.metadataOrNull(file) ?: return null
        if (metadata.symlinkTarget != null || !metadata.isRegularFile || (metadata.size ?: 0) > 65_536)
            throw UserFacingException("The TeamCode configuration $path must be a small regular file, not a symbolic link. Remove it, then run teamcode login.")
        return try { json.decodeFromString<CliConfig>(FileSystem.SYSTEM.read(file) { readUtf8() }) }
        // The file may hold a token: never repeat its content or the refusal, only where it is and how to replace it.
        catch (_: IllegalArgumentException) { unreadable() }
        catch (_: UserFacingException) { unreadable() }
    }
    private fun unreadable(): Nothing = throw UserFacingException("Cannot read the TeamCode configuration $path. Run teamcode login (or teamcode configure) to replace it.")
    fun delete() { FileSystem.SYSTEM.delete(path.toPath(), mustExist = false) }
    fun save(config: CliConfig) { files.write(path, json.encodeToString(CliConfig.serializer(), config)) }

    /** Read-modify-write under `config.lock`; keep [block] short and free of network calls. */
    fun <T> locked(timeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MILLIS, block: () -> T): T = withFileLock(lockPath, timeoutMillis, block)

    /**
     * Save [config] under the lock and return the configuration it replaced, if it was readable. The caller revokes a
     * replaced session (outside the lock) so that two concurrent sign-ins leave no orphan session on the server.
     */
    fun replace(config: CliConfig): CliConfig? = locked {
        val previous = try { load() } catch (_: UserFacingException) { null }
        save(config)
        previous
    }

    /** Delete the configuration under the lock only if it still holds [expected]; false when another command replaced it. */
    fun deleteIf(expected: CliConfig): Boolean = locked {
        val current = try { load() } catch (_: UserFacingException) { null }
        if (current == null || current.serverUrl != expected.serverUrl || current.accessToken != expected.accessToken) return@locked false
        delete()
        true
    }
}
