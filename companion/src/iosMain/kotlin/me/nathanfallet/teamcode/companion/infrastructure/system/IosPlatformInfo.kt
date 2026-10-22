package me.nathanfallet.teamcode.companion.infrastructure.system

import me.nathanfallet.teamcode.api.BuildInfo
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import platform.UIKit.UIDevice

object IosPlatformInfo : PlatformInfo {
    override val platform = "iOS"
    /** `iPhone` or `iPad`: iOS does not give apps the name the owner chose. */
    override val deviceModel: String get() = UIDevice.currentDevice.model
    override val appVersion: String = BuildInfo.VERSION
}
