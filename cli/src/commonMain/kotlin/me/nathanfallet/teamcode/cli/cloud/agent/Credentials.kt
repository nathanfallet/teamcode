package me.nathanfallet.teamcode.cli.cloud.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.cli.platform.*

/** What `git` asks a credential helper for: the remote it is about to contact. */
@Serializable
internal data class CredentialRequest(val protocol: String? = null, val host: String? = null, val path: String? = null)

/** A username and secret for one remote. The secret never appears in `toString`. */
@Serializable
internal data class Credential(val username: String, val password: String) {
    override fun toString() = "Credential(username=$username, password=[REDACTED])"
}

/**
 * Where the runner finds a token for a remote. Each source answers only for the hosts it owns and returns null for
 * the others, so the broker can ask them in turn. The GitHub source comes with the GitHub link.
 */
internal fun interface CredentialSource {
    suspend fun credential(request: CredentialRequest): Credential?
}

/**
 * Serves credentials to the in-pod `git` helper over a Unix socket on a memory volume. The runner holds the tokens;
 * the agent's container only ever receives the one credential a command asked for, at the moment it asks. One
 * request per connection, small and quick: a slow source cannot hold the agent's `git` for more than
 * [timeoutMillis], and at most [MAX_CONCURRENT] requests run at once.
 */
internal class CredentialBroker(
    private val listener: UnixListener,
    private val sources: List<CredentialSource>,
    private val timeoutMillis: Long = 15_000,
    private val log: (String) -> Unit = {},
) {
    private val permits = Semaphore(MAX_CONCURRENT)

    suspend fun serve() = coroutineScope {
        try {
            while (true) {
                val connection = listener.accept() ?: break
                launch { permits.withPermit { handle(connection) } }
            }
        } finally { listener.close() }
    }

    private suspend fun handle(connection: UnixConnection) {
        try {
            val request = try { codec.decodeFromString(CredentialRequest.serializer(), connection.readLine(MAX_REQUEST_LINE) ?: return) } catch (_: Exception) { return }
            val answer = withTimeoutOrNull(timeoutMillis) {
                sources.firstNotNullOfOrNull { source ->
                    try { source.credential(request) } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { log("A credential source failed."); null }
                }
            }
            connection.writeLine(if (answer == null) "{}" else codec.encodeToString(Credential.serializer(), answer))
        } finally { connection.close() }
    }

    companion object {
        const val MAX_CONCURRENT = 8
        const val MAX_REQUEST_LINE = 16 * 1024
        internal val codec = Json { ignoreUnknownKeys = true }
    }
}

/** Asks the broker at [socket] for a credential; null when it has none or cannot be reached. Nothing is logged. */
internal suspend fun requestCredential(socket: String, request: CredentialRequest, connect: (String) -> UnixConnection = ::connectUnix): Credential? {
    val connection = try { connect(socket) } catch (_: Exception) { return null }
    try {
        if (!connection.writeLine(CredentialBroker.codec.encodeToString(CredentialRequest.serializer(), request))) return null
        val line = withTimeoutOrNull(CLIENT_TIMEOUT_MILLIS) { connection.readLine(CredentialBroker.MAX_REQUEST_LINE) } ?: return null
        return try { CredentialBroker.codec.decodeFromString(Credential.serializer(), line) } catch (_: Exception) { null }
    } finally { connection.close() }
}
private const val CLIENT_TIMEOUT_MILLIS = 20_000L

/** Parses git's credential-helper input (`key=value` lines, ended by a blank line or the end of the stream). */
internal fun parseCredentialInput(lines: Sequence<String>): CredentialRequest {
    val values = HashMap<String, String>()
    for (line in lines) {
        if (line.isEmpty()) break
        val separator = line.indexOf('=')
        if (separator > 0) values[line.substring(0, separator)] = line.substring(separator + 1)
    }
    return CredentialRequest(values["protocol"], values["host"], values["path"])
}

/** The helper's answer to `get`: nothing at all when there is no credential, so git moves on to its next helper. */
internal fun formatCredentialOutput(credential: Credential?): String =
    if (credential == null) "" else "username=${credential.username}\npassword=${credential.password}\n"
