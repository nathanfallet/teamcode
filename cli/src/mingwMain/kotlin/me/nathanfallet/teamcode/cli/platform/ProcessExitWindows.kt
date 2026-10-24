@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import platform.windows.CloseHandle
import platform.windows.ERROR_ACCESS_DENIED
import platform.windows.GetLastError
import platform.windows.OpenProcess
import platform.windows.SYNCHRONIZE
import platform.windows.WAIT_TIMEOUT
import platform.windows.WaitForSingleObject

/** A handle opened for SYNCHRONIZE only: enough to wait on the process, not to act on it. */
actual fun processRunning(pid: Long): Boolean {
    if (pid <= 0 || pid > UInt.MAX_VALUE.toLong()) return false
    val handle = OpenProcess(SYNCHRONIZE.toUInt(), 0, pid.toUInt()) ?: return GetLastError() == ERROR_ACCESS_DENIED.toUInt()
    try {
        return WaitForSingleObject(handle, 0u) == WAIT_TIMEOUT.toUInt()
    } finally {
        CloseHandle(handle)
    }
}
