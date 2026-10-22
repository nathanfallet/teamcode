package me.nathanfallet.teamcode.cli.config

import io.ktor.http.*
import kotlinx.serialization.Serializable
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.platform.*
import okio.FileSystem
import okio.Path.Companion.toPath

@Serializable
class CliConfig(val serverUrl: String, val accessToken: String) {
    init {
        validateServerUrl(serverUrl)
        require(accessToken.isNotBlank()) { "An access token is required." }
    }
    override fun toString(): String = "CliConfig(serverUrl=$serverUrl, accessToken=[REDACTED])"
}

/** Messages are TeamCode's own and never repeat the value, which may be a mistyped secret. */
fun validateServerUrl(value: String): String {
    fun refuse(message: String): Nothing = throw CliUserException(message)
    if (value.isBlank()) refuse("A TeamCode server URL is required, for example https://teamcode.example.com.")
    val url = try { Url(value) } catch (error: kotlin.coroutines.cancellation.CancellationException) { throw error }
        catch (_: Exception) { refuse("The TeamCode server URL is not a valid URL. Use an origin such as https://teamcode.example.com.") }
    if (url.protocol != URLProtocol.HTTPS && !(url.protocol == URLProtocol.HTTP && url.host in setOf("localhost", "127.0.0.1", "::1")))
        refuse("Use HTTPS for the TeamCode server URL; unencrypted HTTP is allowed only on loopback.")
    if (!url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() || url.encodedPath !in setOf("", "/") ||
        !url.parameters.isEmpty() || url.fragment.isNotEmpty()) refuse("Use a server origin without credentials, path, query or fragment, such as https://teamcode.example.com.")
    return value.trimEnd('/')
}

fun configPath(env: (String) -> String? = ::environment): String {
    val root = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: run {
        val home = env("HOME") ?: env("USERPROFILE") ?: error("Cannot locate the user home directory.")
        "$home/.config"
    }
    require(root.toPath().isAbsolute) { "The configuration directory must be absolute." }
    return "$root/teamcode/config.json"
}

class ConfigStore(private val path: String = configPath()) {
    fun load(): CliConfig? {
        val file = path.toPath()
        val metadata = FileSystem.SYSTEM.metadataOrNull(file) ?: return null
        require(metadata.symlinkTarget == null && metadata.isRegularFile && (metadata.size ?: 0) <= 65_536) {
            "The configuration must be a small regular file, not a symbolic link."
        }
        return try { Serialization.json.decodeFromString<CliConfig>(FileSystem.SYSTEM.read(file) { readUtf8() }) }
        catch (_: IllegalArgumentException) { throw me.nathanfallet.teamcode.cli.CliUserException("Cannot read TeamCode configuration. Run configure to replace it.") }
    }
    fun delete() { FileSystem.SYSTEM.delete(path.toPath(), mustExist = false) }
    fun save(config: CliConfig) {
        writePrivateFile(path, Serialization.json.encodeToString(CliConfig.serializer(), config))
    }
}
