package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

@Serializable
data class DeviceView(
    val id: String, val memberId: String, val name: String,
    val createdAt: Long, val lastLoginAt: Long, val activeSessions: Int, val current: Boolean,
    /** `macos`, `windows`, `linux`, `ios` or `android`, as reported at the installation's latest login; null until one did. */
    val platform: String? = null, val osVersion: String? = null,
    /** The device's TeamCode daemon (`cloud.sessions.remote.daemon.v1`); null when it never connected or said it stopped. */
    val daemon: DaemonPresenceView? = null,
)
/**
 * [online]: the daemon polled less than a minute ago; only an online device accepts a session started from elsewhere.
 * [features]: what the daemon announced at its last poll ([DaemonFeatures]); empty for a daemon older than them.
 */
@Serializable
data class DaemonPresenceView(val online: Boolean, val lastSeenAt: Long, val version: String? = null, val features: List<String> = emptyList())
/** What a daemon announces in [DaemonPollRequest.features]. */
object DaemonFeatures {
    /** It lists the agent conversations of a folder it could continue, and starts a session from one (`cloud.sessions.remote.resume.v1`). */
    const val RESUME = "resume.v1"
}
/**
 * A folder a device's daemon offers: [id] is the daemon's own key, [name] the folder's last segment, [label] how the
 * owner recognizes it (for example `~/git/teamcode`). Never a full path the server relies on.
 */
@Serializable
data class DeviceFolderView(val id: String, val name: String, val label: String? = null)
@Serializable
data class PublishDeviceFoldersRequest(val folders: List<DeviceFolderView>)
@Serializable
data class DeviceFoldersView(val deviceId: String, val daemon: DaemonPresenceView? = null, val folders: List<DeviceFolderView>)
@Serializable
data class DaemonPollRequest(val waitMillis: Long = 0, val version: String? = null, val features: List<String> = emptyList())
/**
 * A session the daemon is asked to host: start it in [folderId] (checking it against its own configuration first)
 * with `POST /cloud/sessions/{sessionId}/host`, or refuse it. [state] is `creating`, `resuming` or `preparing`.
 */
@Serializable
data class DaemonOrderView(
    val sessionId: String, val folderId: String, val directoryName: String, val agent: String,
    val permissionMode: String, val state: String, val requestedAt: Long,
    /**
     * The agent conversation of [folderId] the session continues (`cloud.sessions.remote.resume.v1`): a Claude Code
     * session id the daemon listed. The daemon checks again that it is one of that folder's conversations.
     */
    val resumeSessionId: String? = null,
)
/**
 * Poll again at once after a response; [onlineTtlMillis] is how long the device stays online without a poll. [listings]:
 * members waiting for the conversations of a folder ([DaemonFeatures.RESUME]), answered with
 * `POST /devices/current/daemon/listings/{requestId}`.
 */
@Serializable
data class DaemonPollView(val orders: List<DaemonOrderView>, val onlineTtlMillis: Long, val maxWaitMillis: Long, val listings: List<DaemonListingView> = emptyList())
/** List at most [limit] conversations of [folderId], the most recent first, older than [before] (milliseconds) when set. */
@Serializable
data class DaemonListingView(val requestId: String, val folderId: String, val limit: Int, val before: Long? = null)
/**
 * An agent conversation a session can continue (`cloud.sessions.remote.resume.v1`): its id, a short title (the name
 * given to it, its summary, or the start of its first message; at most [ResumableSessions.TITLE_LENGTH] characters),
 * when it was last active, and how many messages the member sent in it. Nothing else of the conversation leaves the
 * device.
 */
@Serializable
data class ResumableSessionView(val id: String, val title: String, val lastActiveAt: Long, val turns: Int)
/** [nextBefore]: pass it as `before` for the next page; null on the last one. */
@Serializable
data class ResumableSessionsView(val sessions: List<ResumableSessionView>, val nextBefore: Long? = null)
/**
 * What `teamcode remote resumable DIRECTORY --json` prints for the desktop app's "On this device": the directory's
 * conversations, read on this device by the CLI with the daemon's own code and never sent to the server, or
 * [unavailable], a [LocalResumeReasons] code, when this device cannot start a session that continues one.
 */
@Serializable
data class LocalResumableSessionsView(val sessions: List<ResumableSessionView> = emptyList(), val nextBefore: Long? = null, val unavailable: String? = null)

/** Why this device cannot continue one of its own conversations in a new session (`teamcode remote start --resume`). */
object LocalResumeReasons {
    /** The server does not announce `cloud.sessions.remote.resume.v1` (or does not start sessions on devices). */
    const val SERVER_UNSUPPORTED = "server_unsupported"
    /** The device's daemon hosts no sessions on this system (Windows). */
    const val PLATFORM_UNSUPPORTED = "platform_unsupported"
    /** The device's daemon is not running. */
    const val DAEMON_OFFLINE = "daemon_offline"
    /** The running daemon did not announce [DaemonFeatures.RESUME]: an older TeamCode until it restarts. */
    const val DAEMON_TOO_OLD = "daemon_too_old"
}

/** The daemon's answer to a [DaemonListingView]: the page, or [refused] with a reason code (`folder_not_configured`…). */
@Serializable
data class DaemonListingAnswerRequest(val sessions: List<ResumableSessionView> = emptyList(), val nextBefore: Long? = null, val refused: String? = null)
object ResumableSessions {
    const val CAPABILITY = "cloud.sessions.remote.resume.v1"
    const val TITLE_LENGTH = 80
    const val DEFAULT_LIMIT = 20
    const val MAX_LIMIT = 50
    /** A Claude Code session id: a lowercase UUID, the name of its transcript. */
    val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}
@Serializable
data class RefuseDaemonOrderRequest(val reason: String)
@Serializable
data class AuthSessionView(
    val id: String, val memberId: String, val name: String, val deviceId: String?,
    val createdAt: Long?, val expiresAt: Long, val current: Boolean,
)
@Serializable
data class RenameDeviceRequest(val name: String)
