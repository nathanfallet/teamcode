package me.nathanfallet.teamcode.cli.platform

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

@OptIn(ExperimentalNativeApi::class)
internal actual fun hostOs(): String = if (Platform.osFamily == OsFamily.MACOSX) "macos" else "linux"
