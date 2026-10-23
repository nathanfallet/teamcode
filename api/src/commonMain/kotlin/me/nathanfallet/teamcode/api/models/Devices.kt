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
/** [online]: the daemon polled less than a minute ago; only an online device accepts a session started from elsewhere. */
@Serializable
data class DaemonPresenceView(val online: Boolean, val lastSeenAt: Long, val version: String? = null)
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
data class DaemonPollRequest(val waitMillis: Long = 0, val version: String? = null)
/**
 * A session the daemon is asked to host: start it in [folderId] (checking it against its own configuration first)
 * with `POST /cloud/sessions/{sessionId}/host`, or refuse it. [state] is `creating`, `resuming` or `preparing`.
 */
@Serializable
data class DaemonOrderView(
    val sessionId: String, val folderId: String, val directoryName: String, val agent: String,
    val permissionMode: String, val state: String, val requestedAt: Long,
)
/** Poll again at once after a response; [onlineTtlMillis] is how long the device stays online without a poll. */
@Serializable
data class DaemonPollView(val orders: List<DaemonOrderView>, val onlineTtlMillis: Long, val maxWaitMillis: Long)
@Serializable
data class RefuseDaemonOrderRequest(val reason: String)
@Serializable
data class AuthSessionView(
    val id: String, val memberId: String, val name: String, val deviceId: String?,
    val createdAt: Long?, val expiresAt: Long, val current: Boolean,
)
@Serializable
data class RenameDeviceRequest(val name: String)
