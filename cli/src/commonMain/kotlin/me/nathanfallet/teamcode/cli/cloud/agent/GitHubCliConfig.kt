package me.nathanfallet.teamcode.cli.cloud.agent

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * The `gh` configuration directory of the agent's container (`GH_CONFIG_DIR`), on a memory-backed volume that only
 * this runner writes and the agent's container mounts read-only. It holds one file, `hosts.yml`, with the execution
 * owner's token for `github.com`, or nothing when the owner has no usable link, so every `gh` the agent runs (by
 * name or by path, from any shell, script or tool) reads the current token itself, and `gh auth git-credential`
 * answers with the same token as the runner's git helper.
 */
internal class GitHubCliConfig(directory: String, private val fileSystem: FileSystem = FileSystem.SYSTEM) {
    private val directory = directory.toPath()
    val hosts = this.directory / HOSTS_FILE

    /** Replaces `hosts.yml` atomically with [token], or removes it when [token] is null or not safe to write. */
    fun write(token: GitHubToken?) {
        val content = token?.let(::hostsYaml)
        if (content == null) { fileSystem.delete(hosts, mustExist = false); return }
        val temporary = directory / "$HOSTS_FILE.tmp"
        fileSystem.write(temporary) { writeUtf8(content) }
        fileSystem.atomicMove(temporary, hosts)
    }

    companion object {
        const val HOSTS_FILE = "hosts.yml"
        private val tokenPattern = Regex("[A-Za-z0-9_]{1,1024}")
        private val loginPattern = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")

        /**
         * `gh`'s multi-account `hosts.yml` for github.com over HTTPS. A token or login with any other character is
         * refused rather than quoted: GitHub's never contain one. Without a login (an older server), the
         * single-account form, which `gh` reads as "logged in using token".
         */
        internal fun hostsYaml(token: GitHubToken): String? {
            if (!tokenPattern.matches(token.token)) return null
            val login = token.login?.takeIf { loginPattern.matches(it) }
            return buildString {
                appendLine("github.com:")
                if (login != null) {
                    appendLine("    users:")
                    appendLine("        $login:")
                    appendLine("            oauth_token: ${token.token}")
                }
                appendLine("    git_protocol: https")
                appendLine("    oauth_token: ${token.token}")
                if (login != null) appendLine("    user: $login")
            }
        }
    }
}

/**
 * Keeps [config] in step with [source] for the whole execution: written at start, rewritten when the source renews
 * the token (shortly before it expires), removed when the owner has no usable link, and removed when the runner
 * stops. After a refusal the server is asked again every [idleMillis], so a member who links GitHub during the
 * session gets an authenticated `gh` within that delay. A failed call or write is retried after [retryMillis], then
 * every [idleMillis] while it keeps failing; the file it last wrote stays until then (that token is still the
 * owner's and usually still valid).
 */
internal class GitHubCliRenewal(
    private val source: GitHubCredentialSource,
    private val config: GitHubCliConfig,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val idleMillis: Long = IDLE_MILLIS,
    private val retryMillis: Long = GitHubCredentialSource.RETRY_MILLIS,
    private val log: (String) -> Unit = {},
) {
    suspend fun run() {
        var failures = 0
        try {
            while (true) {
                val wait = try {
                    val token = source.token()
                    config.write(token)
                    failures = 0
                    if (token == null) idleMillis else (source.renewsAt() - now()).coerceAtLeast(retryMillis)
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) {
                    // Logged once per streak, without the cause; a server without GitHub fails every call.
                    if (failures++ == 0) log("The GitHub CLI configuration could not be renewed.")
                    if (failures == 1) retryMillis else idleMillis
                }
                sleep(wait)
            }
        } finally {
            withContext(NonCancellable) { try { config.write(null) } catch (_: Exception) { } }
        }
    }

    companion object { const val IDLE_MILLIS = 5 * 60_000L }
}
