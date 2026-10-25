package me.nathanfallet.teamcode.cli.cloud.agent

import kotlin.time.Clock
import kotlinx.coroutines.*
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * The `gh` configuration directory of the agent's container (`GH_CONFIG_DIR`), on a memory-backed volume that only
 * this runner writes and the agent's container mounts read-only. It holds one file, `hosts.yml`, with the execution
 * owner's token for `github.com`, or, when the owner has no usable link, a short notice ([NOTICE_FILE]) that tells an
 * agent wondering why `gh` is signed out what the owner does, and never a token. So every `gh` the agent runs (by
 * name or by path, from any shell, script or tool) reads the current token itself, and `gh auth git-credential`
 * answers with the same token as the runner's git helper.
 *
 * The file gets the runner's default mode (0644 under the usual umask): the volume is mounted in the pod's two
 * containers only, and the agent's UID, the one reader it is for, shares no user with the runner.
 */
internal class GitHubCliConfig(directory: String, private val fileSystem: FileSystem = FileSystem.SYSTEM) {
    private val directory = directory.toPath()
    val hosts = this.directory / HOSTS_FILE
    val notice = this.directory / NOTICE_FILE

    /**
     * Replaces `hosts.yml` atomically with [token], or removes it when [token] is null and leaves the notice in its
     * place. Returns false when the token is not safe to write (the file is then removed too, so `gh` is
     * unauthenticated rather than stale).
     */
    fun write(token: GitHubToken?): Boolean {
        val content = token?.let(::hostsYaml)
        if (content == null) {
            fileSystem.delete(hosts, mustExist = false)
            if (token == null) fileSystem.write(notice) { writeUtf8(NOTICE) }
            return token == null
        }
        val temporary = directory / "$HOSTS_FILE.tmp"
        fileSystem.write(temporary) { writeUtf8(content) }
        fileSystem.atomicMove(temporary, hosts)
        fileSystem.delete(notice, mustExist = false)
        return true
    }

    /** Removes everything this runner wrote; called on its way out. */
    fun clear() {
        fileSystem.delete(hosts, mustExist = false)
        fileSystem.delete(notice, mustExist = false)
    }

    companion object {
        const val HOSTS_FILE = "hosts.yml"
        /** Read by no tool: `gh` only reads `hosts.yml` and `config.yml` here. */
        const val NOTICE_FILE = "TEAMCODE-GITHUB-NOT-LINKED.txt"
        internal val NOTICE = """
            gh is not signed in in this TeamCode cloud session.
            The session owner's GitHub access is not linked, must be renewed, or could not be fetched right now.
            The owner links GitHub again with `teamcode github link`, or Link GitHub in the companion app or on the
            dashboard's GitHub page. This session picks it up within 5 minutes, without a restart.
            Until then gh is unauthenticated and git reaches public repositories only.

        """.trimIndent()
        private val tokenPattern = Regex("[A-Za-z0-9_]{1,1024}")
        /** GitHub logins, including Enterprise Managed Users' `_shortcode` suffix; always written quoted. */
        private val loginPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")

        /**
         * `gh`'s multi-account `hosts.yml` for github.com over HTTPS. A token or login with any other character is
         * refused rather than escaped: GitHub's never contain one. The login is quoted, so one such as `123` or
         * `true` stays a string. Without a login (an older server), the single-account form, which `gh` reads as
         * "logged in using token".
         */
        internal fun hostsYaml(token: GitHubToken): String? {
            if (!tokenPattern.matches(token.token)) return null
            val login = token.login?.takeIf { loginPattern.matches(it) }?.let { "\"$it\"" }
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
 * Keeps [config] in step with [source] for the whole execution: written once before the agent starts ([refresh]),
 * rewritten when the source renews the token (shortly before it expires), removed when the owner has no usable link,
 * and removed when the runner stops. After a refusal the server is asked again every [idleMillis], so a member who
 * links GitHub during the session gets an authenticated `gh` within that delay. A failed call or write is retried
 * after [retryMillis], then every [idleMillis] while it keeps failing; the last token written stays until it
 * expires, then the file is removed.
 */
internal class GitHubCliRenewal(
    private val source: GitHubCredentialSource,
    private val config: GitHubCliConfig,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val idleMillis: Long = IDLE_MILLIS,
    private val retryMillis: Long = GitHubCredentialSource.RETRY_MILLIS,
    private val log: (String) -> Unit = {},
    /** Where the blocking file calls run, off the threads of the runner loop, the broker and the proxy. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private var failures = 0
    private var refused = false
    private var writtenExpiresAt: Long? = null
    private var written = false
    private var unlinked = false

    /** One pass: asks the source, writes or removes the file, and returns how long to wait before the next pass. */
    suspend fun refresh(): Long = try {
        val token = source.token()
        val accepted = write(token)
        if (!accepted && !refused) log("The GitHub token was not written to the gh configuration: unexpected format.")
        refused = !accepted
        failures = 0
        // Once per streak, so an empty gh configuration is never silent in the pod's log either.
        if (token == null && !unlinked) log("The session owner has no usable GitHub link: gh and git run unauthenticated until they link GitHub again (teamcode github link).")
        unlinked = token == null
        if (token == null || !accepted) idleMillis else (source.renewsAt() - now()).coerceAtLeast(retryMillis)
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) {
        // Logged once per streak, without the cause; a server without GitHub fails every call.
        if (failures++ == 0) log("The GitHub CLI configuration could not be renewed.")
        // An expired token is worse than none: gh would claim a login and fail every call.
        val expiry = writtenExpiresAt
        if (written && expiry != null && now() >= expiry) try { write(null) } catch (_: Exception) { }
        if (failures == 1) retryMillis else idleMillis
    }

    /** Renews until cancelled, starting with a wait of [firstWait] (a [refresh] already done) or a pass at once. */
    suspend fun run(firstWait: Long? = null) {
        try {
            var wait = firstWait ?: refresh()
            while (true) { sleep(wait); wait = refresh() }
        } finally { withContext(NonCancellable) { clear() } }
    }

    /** Removes the file; the runner calls it on its way out. */
    suspend fun clear() {
        try { withContext(io) { config.clear() }; written = false; writtenExpiresAt = null } catch (_: Exception) { }
    }

    private suspend fun write(token: GitHubToken?): Boolean = withContext(io) {
        config.write(token).also { accepted ->
            written = token != null && accepted
            writtenExpiresAt = if (written) token?.expiresAt else null
        }
    }

    companion object { const val IDLE_MILLIS = 5 * 60_000L }
}
