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

/** A device's folders, read now; [online] is false once its daemon stopped answering. */
data class DeviceFolders(val online: Boolean, val folders: List<DeviceFolder>)

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
) {
    fun normalized(): NewDeviceSession = copy(
        name = name.trim(), model = model?.trim()?.ifEmpty { null }, effort = effort?.trim()?.ifEmpty { null },
        initialMessage = initialMessage?.takeIf { it.isNotBlank() },
    )
}
