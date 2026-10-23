@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.artifacts

import kotlinx.cinterop.*
import platform.posix.*

/**
 * Creates [path] `0700`, or accepts an existing real directory of this user that is already `0700`. Never a link, a
 * directory someone else created first, or one whose permissions would have to be narrowed (a shared directory passed
 * by mistake keeps them).
 */
internal actual fun ensurePrivateDirectory(path: String): Boolean {
    val created = mkdir(path, 448.convert()) == 0
    if (!created && errno != EEXIST) return false
    memScoped {
        val info = alloc<stat>()
        if (lstat(path, info.ptr) != 0 || (info.st_mode.toInt() and S_IFMT) != S_IFDIR || info.st_uid != getuid()) return false
        // mkdir applies the umask, which only removes bits: a fresh directory is at most 0700.
        if (created) return chmod(path, 448.convert()) == 0
        return (info.st_mode.toInt() and 511) == 448
    }
}

internal actual fun flushStandardOutput() { fflush(null) }
