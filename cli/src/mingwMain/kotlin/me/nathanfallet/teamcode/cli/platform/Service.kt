@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import me.nathanfallet.teamcode.cli.interop.windows.teamcode_windows_install_control
import me.nathanfallet.teamcode.cli.interop.windows.teamcode_windows_stop_requested
import me.nathanfallet.teamcode.cli.interop.windows.teamcode_windows_user_sid
import platform.windows.*

actual fun servicePlatform(): ServicePlatform = memScoped {
    val buffer = allocArray<UShortVar>(32_768)
    val length = GetModuleFileNameW(null, buffer, 32_768u)
    check(length > 0u && length < 32_768u)
    val sid = checkNotNull(teamcode_windows_user_sid())
    try { ServicePlatform("windows", buffer.toKStringFromUtf16(), sid.toKStringFromUtf16()) }
    finally { LocalFree(sid) }
}

// Task Scheduler stops the service through a private cooperative marker (see stopRequested in the service runtime), with
// no console or privileged process kill. A session host or runner started from a terminal, or from the daemon, also stops
// on Ctrl+C, Ctrl+Break, a closed window, logoff or shutdown: the console handler only raises a flag the loops poll.
actual fun installServiceSignalHandlers() = teamcode_windows_install_control()
actual fun serviceStopRequested(): Boolean = teamcode_windows_stop_requested() != 0
