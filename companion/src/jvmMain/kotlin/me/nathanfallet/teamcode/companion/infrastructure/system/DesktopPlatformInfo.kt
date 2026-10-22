package me.nathanfallet.teamcode.companion.infrastructure.system

import java.net.InetAddress
import me.nathanfallet.teamcode.api.BuildInfo
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo

object DesktopPlatformInfo : PlatformInfo {
    override val platform: String = System.getProperty("os.name").orEmpty().let { name ->
        when {
            name.startsWith("Mac", ignoreCase = true) -> "macOS"
            name.startsWith("Windows", ignoreCase = true) -> "Windows"
            else -> "Linux"
        }
    }
    override val deviceModel: String by lazy {
        // The host name, as a member recognizes the machine; never blocking the UI for long on a broken resolver.
        System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
            ?: try { InetAddress.getLocalHost().hostName } catch (_: Exception) { null }
            ?: "Desktop"
    }
    override val appVersion: String = BuildInfo.VERSION
}
