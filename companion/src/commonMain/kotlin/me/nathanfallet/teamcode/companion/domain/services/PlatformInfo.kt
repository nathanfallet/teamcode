package me.nathanfallet.teamcode.companion.domain.services

interface PlatformInfo {
    /** `iOS`, `Android`, `macOS`, `Windows` or `Linux`. */
    val platform: String
    /** The device model or host name shown in the default device name. */
    val deviceModel: String
    val appVersion: String

    /** What the login reports to the server (`ios`, `android`, `macos`, `windows`, `linux`); null for anything else. */
    val reportedPlatform: String? get() = platform.lowercase().takeIf { it in REPORTED_PLATFORMS }

    /** The default name of this installation on the server's Devices page, such as `TeamCode iOS · iPhone`. */
    val defaultDeviceName: String get() = "TeamCode $platform · $deviceModel".take(120)
}

private val REPORTED_PLATFORMS = setOf("ios", "android", "macos", "windows", "linux")
