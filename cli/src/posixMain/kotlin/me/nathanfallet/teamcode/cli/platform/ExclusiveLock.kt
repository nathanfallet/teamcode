@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import me.nathanfallet.teamcode.cli.interop.process.teamcode_try_flock
import platform.posix.*

actual fun holdExclusiveLock(path: String): AutoCloseable? {
    // O_NOFOLLOW refuses a planted symbolic link; O_CLOEXEC keeps the descriptor (and so the lock) out of child processes.
    val fd = open(path, O_RDWR or O_CREAT or O_NOFOLLOW or O_CLOEXEC, 384u)
    check(fd >= 0) { "Cannot open the lock file." }
    if (teamcode_try_flock(fd) != 0) {
        val error = errno
        close(fd)
        if (error == EWOULDBLOCK || error == EAGAIN) return null
        error("Cannot lock the lock file.")
    }
    return AutoCloseable { close(fd) }
}

actual fun setEnvironment(name: String, value: String) {
    check(setenv(name, value, 1) == 0) { "Cannot set $name." }
}
