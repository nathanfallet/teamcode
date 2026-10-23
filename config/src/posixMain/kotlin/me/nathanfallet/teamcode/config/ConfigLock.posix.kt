@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.config

import kotlin.concurrent.AtomicInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.cinterop.*
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.*

// POSIX record locks belong to the process, and closing any descriptor of the file releases them: one acquisition per
// process at a time, so a second thread waits here instead of silently sharing (and then dropping) the lock.
private val held = AtomicInt(0)

actual fun <T> withFileLock(path: String, timeoutMillis: Long, block: () -> T): T {
    val deadline = TimeSource.Monotonic.markNow() + timeoutMillis.milliseconds
    while (!held.compareAndSet(0, 1)) {
        if (deadline.hasPassedNow()) lockBusy(path)
        usleep(25_000u)
    }
    try {
        val parent = requireNotNull(path.toPath().parent)
        if (FileSystem.SYSTEM.metadataOrNull(parent) == null) {
            parent.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
            check(mkdir(parent.toString(), 448u.convert()) == 0 || errno == EEXIST) { "Cannot create the configuration directory." }
        }
        require(FileSystem.SYSTEM.metadataOrNull(parent)?.symlinkTarget == null) { "Configuration directory cannot be a symbolic link." }
        // An empty file without secrets; O_NOFOLLOW refuses a planted symbolic link.
        val fd = open(path, O_RDWR or O_CREAT or O_NOFOLLOW or O_CLOEXEC, 384u)
        check(fd >= 0) { "Cannot open the configuration lock." }
        try {
            memScoped {
                val request = alloc<flock>()
                request.l_type = F_WRLCK.convert()
                request.l_whence = SEEK_SET.convert()
                request.l_start = 0
                request.l_len = 0
                while (fcntl(fd, F_SETLK, request.ptr) != 0) {
                    val error = errno
                    check(error == EACCES || error == EAGAIN || error == EINTR) { "Cannot lock the configuration." }
                    if (deadline.hasPassedNow()) lockBusy(path)
                    usleep(25_000u)
                }
            }
            return block()
        } finally { close(fd) } // Closing the descriptor releases the lock.
    } finally { held.value = 0 }
}
