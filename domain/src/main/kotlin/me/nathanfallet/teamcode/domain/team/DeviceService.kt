package me.nathanfallet.teamcode.domain.team

data class Device(
    val id: String, val memberId: String, val name: String,
    val createdAt: Long, val lastLoginAt: Long, val activeSessions: Int, val current: Boolean,
    /** The operating system the installation reported at its latest login (`macos`, `windows`, `linux`, `ios`, `android`); null until one did. */
    val platform: String? = null, val osVersion: String? = null,
    /** The device's daemon (`cloud.sessions.remote.daemon.v1`); null when it never connected or said it stopped. */
    val daemon: DaemonPresence? = null,
)
data class MemberSession(
    val id: String, val memberId: String, val name: String, val deviceId: String?,
    val createdAt: Long?, val expiresAt: Long, val current: Boolean,
)

/** Session metadata only. Credential hashes and installation identifiers never leave persistence. */
interface DeviceService {
    suspend fun devices(actor: Principal, memberId: String = actor.member.id): List<Device>
    suspend fun sessions(actor: Principal, memberId: String = actor.member.id): List<MemberSession>
    suspend fun rename(actor: Principal, deviceId: String, name: String): Device
    /** Unregisters the device and revokes all its sessions; returns the affected member ID. */
    suspend fun revokeDevice(actor: Principal, deviceId: String): String
    suspend fun revokeSession(actor: Principal, sessionId: String): String

    companion object {
        const val DEVICE_LIMIT = 50L
        private val installationPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun validateInstallation(id: String?) {
            if (id != null && !installationPattern.matches(id))
                throw TeamFailure("invalid_installation", 400, "An installation identifier must be a lowercase UUID.")
        }
        fun validateName(name: String) {
            if (name.isBlank() || name.length > 120 || name.any(Char::isISOControl))
                throw TeamFailure("invalid_device_name", 400, "Use a device name of 1–120 characters without control characters.")
        }
        val PLATFORMS = setOf("macos", "windows", "linux", "ios", "android")
        /** An installation reports its operating system with its login; both values are optional, for older clients. */
        fun validatePlatform(platform: String?, osVersion: String?) {
            if (platform != null && platform !in PLATFORMS)
                throw TeamFailure("invalid_platform", 400, "Use a platform among ${PLATFORMS.joinToString(", ")}.")
            if (osVersion != null && (platform == null || osVersion.isBlank() || osVersion.length > 64 || osVersion.any { it.isISOControl() || it.isWhitespace() && it != ' ' }))
                throw TeamFailure("invalid_os_version", 400, "Send an operating system version of 1–64 printable characters, with its platform.")
        }
    }
}
