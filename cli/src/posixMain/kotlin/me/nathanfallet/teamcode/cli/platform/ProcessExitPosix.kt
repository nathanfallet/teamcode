@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import platform.posix.EPERM
import platform.posix.errno
import platform.posix.kill

/** Signal 0 only checks that the process exists: EPERM means it exists under another user. */
actual fun processRunning(pid: Long): Boolean {
    if (pid <= 0 || pid > Int.MAX_VALUE) return false
    return kill(pid.toInt(), 0) == 0 || errno == EPERM
}
