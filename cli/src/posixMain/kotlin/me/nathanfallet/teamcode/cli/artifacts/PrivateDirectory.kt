@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.artifacts

import kotlinx.cinterop.*
import platform.posix.*

internal actual fun ensurePrivateDirectory(path: String): Boolean {
    if (mkdir(path, 448.convert()) != 0 && errno != EEXIST) return false
    memScoped {
        val info = alloc<stat>()
        // Never a link, and never a directory someone else created first.
        if (lstat(path, info.ptr) != 0 || (info.st_mode.toInt() and S_IFMT) != S_IFDIR || info.st_uid != getuid()) return false
    }
    return chmod(path, 448.convert()) == 0
}

internal actual fun flushStandardOutput() { fflush(null) }
