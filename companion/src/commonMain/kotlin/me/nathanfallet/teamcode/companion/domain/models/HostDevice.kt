package me.nathanfallet.teamcode.companion.domain.models

/**
 * One of the member's own devices whose TeamCode daemon is online (`cloud.sessions.remote.daemon.v1`): a session can be
 * started on it from this app. [current] is the device this app signed in from.
 */
data class HostDevice(
    val id: String,
    val name: String,
    val platform: String?,
    val osVersion: String?,
    val current: Boolean,
)

/**
 * A folder a device's daemon offers: [id] is the daemon's own key, [name] the folder's last segment, [label] how the
 * owner recognizes it (`~/git/teamcode`). The app never sees nor sends a path.
 */
data class DeviceFolder(val id: String, val name: String, val label: String?)

/**
 * A device's folders, read now; [online] is false once its daemon stopped answering. [resumes]: its daemon lists a
 * folder's conversations to continue (`cloud.sessions.remote.resume.v1`); false for an older TeamCode on the device.
 */
data class DeviceFolders(val online: Boolean, val folders: List<DeviceFolder>, val resumes: Boolean = false)

/**
 * A Claude Code conversation of a device's folder that a new session can continue: its id, a short title (its name,
 * summary or first message, cut by the device), its last activity and the number of messages sent in it. Nothing
 * else of the conversation leaves the device.
 */
data class ResumableSession(val id: String, val title: String, val lastActiveAt: Long, val turns: Int)

/** A page of [sessions], the most recent first; [nextBefore] reads the next one, null on the last. */
data class ResumableSessions(val sessions: List<ResumableSession>, val nextBefore: Long? = null)

/** How the agent of a session on a device acts: it asks before acting, or acts without asking. */
enum class PermissionMode(val wire: String) {
    ASK(SessionHost.ASK), BYPASS(SessionHost.BYPASS);

    companion object {
        fun of(wire: String?): PermissionMode? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * What the form sends to start a session on one of the member's devices: a folder that device published, never a path.
 * [agent] is `claude` or `codex`; optional fields are left out when empty.
 */
data class NewDeviceSession(
    val name: String,
    val deviceId: String,
    val folderId: String,
    val agent: String,
    val model: String?,
    val effort: String?,
    val permissionMode: PermissionMode,
    val initialMessage: String?,
    /** A conversation of the folder the session continues (Claude Code only); null starts a new one. */
    val resumeSessionId: String? = null,
) {
    fun normalized(): NewDeviceSession = copy(
        name = name.trim(), model = model?.trim()?.ifEmpty { null }, effort = effort?.trim()?.ifEmpty { null },
        initialMessage = initialMessage?.takeIf { it.isNotBlank() }, resumeSessionId = resumeSessionId?.takeIf { agent == "claude" },
    )
}
