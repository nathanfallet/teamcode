@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.config

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.cinterop.*
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.windows.*

actual fun <T> withFileLock(path: String, timeoutMillis: Long, block: () -> T): T {
    val deadline = TimeSource.Monotonic.markNow() + timeoutMillis.milliseconds
    val parent = requireNotNull(path.toPath().parent)
    // The directory is restricted to the user by the first private write; the lock file is empty and holds no secret.
    FileSystem.SYSTEM.createDirectories(parent)
    val attributes = GetFileAttributesW(path)
    require(attributes == INVALID_FILE_ATTRIBUTES || (attributes.toInt() and FILE_ATTRIBUTE_REPARSE_POINT) == 0) {
        "The configuration lock cannot be a reparse point."
    }
    val access = GENERIC_READ.toUInt() or GENERIC_WRITE.toUInt()
    val sharing = FILE_SHARE_READ.toUInt() or FILE_SHARE_WRITE.toUInt()
    val flags = FILE_ATTRIBUTE_NORMAL.toUInt() or FILE_FLAG_OPEN_REPARSE_POINT.toUInt()
    val handle = CreateFileW(path, access, sharing, null, OPEN_ALWAYS.toUInt(), flags, null)
    check(handle != INVALID_HANDLE_VALUE) { "Cannot open the configuration lock." }
    try {
        // LockFileEx locks belong to the handle, so a second acquisition in this process waits like another process.
        memScoped {
            val overlapped = alloc<OVERLAPPED>()
            while (LockFileEx(handle, LOCKFILE_EXCLUSIVE_LOCK.toUInt() or LOCKFILE_FAIL_IMMEDIATELY.toUInt(), 0u, 1u, 0u, overlapped.ptr) == 0) {
                check(GetLastError() == ERROR_LOCK_VIOLATION.toUInt()) { "Cannot lock the configuration." }
                if (deadline.hasPassedNow()) lockBusy(path)
                Sleep(25u)
            }
            try { return block() } finally { UnlockFileEx(handle, 0u, 1u, 0u, overlapped.ptr) }
        }
    } finally { CloseHandle(handle) }
}
