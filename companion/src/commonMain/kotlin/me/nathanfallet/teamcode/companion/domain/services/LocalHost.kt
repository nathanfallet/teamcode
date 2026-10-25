package me.nathanfallet.teamcode.companion.domain.services

import me.nathanfallet.teamcode.companion.domain.models.ResumableSessions

/**
 * Sessions hosted on this device (`cloud.sessions.remote.v1`), through the installed CLI (`teamcode remote`): only the
 * desktop app provides it, the mobile apps have none. The app never runs an agent itself: it starts the CLI's host,
 * then follows and drives the session through the server like a cloud session.
 */
interface LocalHost {
    /** Whether [accountId] can start a session here, and if not, what the member can do about it. */
    suspend fun availability(accountId: String): LocalAvailability

    /** The operating system's folder picker; null when the member cancels. */
    suspend fun pickDirectory(): String?

    /**
     * Creates the session on the server and starts its host in the background (`teamcode remote start --detach`);
     * returns its id. A refusal is a [LocalHostException] with the CLI's own explanation.
     */
    suspend fun start(accountId: String, request: LocalSessionRequest): String

    /**
     * Installs the CLI of the app's version when [LocalAvailability.CliMissing.canInstall] (its install script, checked
     * against the hash the app was built with); [output] receives the installer's lines. A refusal is a [LocalHostException].
     */
    suspend fun installCli(output: (String) -> Unit): Unit = throw LocalHostException("Install the TeamCode CLI with the command shown.")

    /**
     * The Claude Code conversations of [directory] on this device that a new session can continue
     * (`teamcode remote resumable`), the most recent first, older than [before] when set: read here by the CLI with its
     * daemon's code, never through the server. [LocalResumables.Unavailable] when this device cannot continue one.
     */
    suspend fun resumable(accountId: String, directory: String, before: Long?): LocalResumables = LocalResumables.Unavailable(LocalResumeBlock.CLI_TOO_OLD)

    /** The folder the app was opened with (`teamcode ui DIRECTORY`), given once; null otherwise. */
    fun takeInitialDirectory(): String? = null
}

/** What "On this device" needs, in the order the app checks it. */
sealed interface LocalAvailability {
    data object Ready : LocalAvailability
    /** No TeamCode CLI found; [installCommand] is the command for this system, and [canInstall] whether the app runs it itself. */
    data class CliMissing(val installCommand: String, val canInstall: Boolean = false) : LocalAvailability
    /** The CLI is older than this app's minor version: [updateCommand] updates it. */
    data class CliTooOld(val found: String, val required: String, val updateCommand: String) : LocalAvailability
    /** Sessions here run with the CLI's sign-in, and the CLI is signed in elsewhere (or not at all). */
    data class OtherAccount(val cliAccount: String?) : LocalAvailability
    /** The server does not announce `cloud.sessions.remote.v1`. */
    data object ServerUnsupported : LocalAvailability
}

/** A folder's conversations to continue on this device, or why there are none to offer. */
sealed interface LocalResumables {
    data class Page(val page: ResumableSessions) : LocalResumables
    data class Unavailable(val block: LocalResumeBlock) : LocalResumables
}

/**
 * Why "On this device" cannot continue a conversation: such a session goes through the device's daemon, which checks
 * the conversation and forks it, and the CLI never hosts one itself.
 */
enum class LocalResumeBlock {
    /** The server does not announce `cloud.sessions.remote.resume.v1`. */
    SERVER,
    /** The daemon hosts no sessions on this system (Windows). */
    PLATFORM,
    /** The device's daemon (the TeamCode service) is not running. */
    DAEMON_OFFLINE,
    /** The running daemon is older than the feature, until it restarts on a newer TeamCode. */
    DAEMON_TOO_OLD,
    /** The CLI has no `teamcode remote resumable`. */
    CLI_TOO_OLD,
}

/**
 * A session to start in [directory] (an absolute path, which never leaves the device: the server gets its name only).
 * [agent] is `claude` or `codex`; blank [model] and [effort] keep the agent's defaults.
 */
data class LocalSessionRequest(
    val directory: String,
    val agent: String,
    val name: String,
    val model: String,
    val effort: String,
    val bypassPermissions: Boolean,
    /** One of [directory]'s Claude Code conversations the session continues (`--resume`, forked by the daemon); null starts a new one. */
    val resumeSessionId: String? = null,
)

/** A refusal with a message for the member (the CLI's explanation, never a request or response body). */
class LocalHostException(override val message: String) : Exception(message)

/**
 * The desktop's link with the CLI's sign-in (`config.json`): absent on the mobile apps. [canLink] is true while the
 * CLI has no account, so a sign-in in the app can give it one.
 */
interface CliAccountLink {
    /** Where the CLI's configuration is, shown on the Account tab. */
    val configPath: String
    suspend fun canLink(): Boolean
    /** Makes [accountId] the CLI's account as well: its session is written to `config.json`, as `teamcode login` would. */
    suspend fun link(accountId: String)
}
