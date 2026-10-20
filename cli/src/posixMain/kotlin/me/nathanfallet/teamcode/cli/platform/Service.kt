@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import me.nathanfallet.teamcode.cli.interop.process.*
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.getuid
import platform.posix.getpwuid
import kotlin.native.Platform
import kotlin.native.OsFamily

actual fun servicePlatform(): ServicePlatform = memScoped {
    val buffer = allocArray<ByteVar>(16_384)
    check(teamcode_executable(buffer, 16_384u) == 0) { "Cannot locate the native executable." }
    val executable = FileSystem.SYSTEM.canonicalize(buffer.toKString().toPath()).toString()
    val identity = checkNotNull(getpwuid(getuid())).pointed
    ServicePlatform(if (Platform.osFamily == OsFamily.MACOSX) "macos" else "linux", executable, getuid().toString(), identity.pw_name?.toKString(), identity.pw_dir?.toKString())
}
private var serviceStopFlag: COpaquePointer? = null
actual fun installServiceSignalHandlers() { serviceStopFlag = teamcode_service_signals() }
actual fun serviceStopRequested(): Boolean = teamcode_service_stop_requested(checkNotNull(serviceStopFlag)) != 0
