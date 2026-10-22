package me.nathanfallet.teamcode.companion.infrastructure.system

import android.os.Build
import me.nathanfallet.teamcode.api.BuildInfo
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo

object AndroidPlatformInfo : PlatformInfo {
    override val platform = "Android"
    override val deviceModel: String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device"
    override val appVersion: String = BuildInfo.VERSION
}
