@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import platform.windows.*

actual fun holdExclusiveLock(path: String): AutoCloseable? {
    val attributes = GetFileAttributesW(path)
    require(attributes == INVALID_FILE_ATTRIBUTES || (attributes.toInt() and FILE_ATTRIBUTE_REPARSE_POINT) == 0) { "The lock file cannot be a reparse point." }
    val access = GENERIC_READ.toUInt() or GENERIC_WRITE.toUInt()
    val sharing = FILE_SHARE_READ.toUInt() or FILE_SHARE_WRITE.toUInt()
    val flags = FILE_ATTRIBUTE_NORMAL.toUInt() or FILE_FLAG_OPEN_REPARSE_POINT.toUInt()
    // No security attributes: the handle is not inheritable, so a child process never holds the lock.
    val handle = CreateFileW(path, access, sharing, null, OPEN_ALWAYS.toUInt(), flags, null)
    check(handle != INVALID_HANDLE_VALUE) { "Cannot open the lock file." }
    val locked = memScoped {
        val overlapped = alloc<OVERLAPPED>()
        LockFileEx(handle, LOCKFILE_EXCLUSIVE_LOCK.toUInt() or LOCKFILE_FAIL_IMMEDIATELY.toUInt(), 0u, 1u, 0u, overlapped.ptr) != 0
    }
    if (!locked) {
        val error = GetLastError()
        CloseHandle(handle)
        if (error == ERROR_LOCK_VIOLATION.toUInt()) return null
        error("Cannot lock the lock file.")
    }
    // Closing the handle releases its locks.
    return AutoCloseable { CloseHandle(handle) }
}

actual fun setEnvironment(name: String, value: String) {
    check(SetEnvironmentVariableW(name, value) != 0) { "Cannot set $name." }
}
