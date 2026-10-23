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
    // The lock file is empty and holds no secret, but it is often the first thing created: the directory must already be
    // the private one the CLI's writes accept (they refuse a directory owned by someone else).
    parent.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
    createPrivateDirectory(parent.toString())
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

/**
 * Create [path] if missing with the descriptor the native CLI's private writes use (`O:<user>D:P(A;OICI;FA;;;<user>)`):
 * owned by the token user and one protected allow entry for that user. A plain CreateDirectory from an elevated process
 * gives the directory to Administrators, and the next private write then refuses it as belonging to another user. An
 * existing directory is left to the private write, which validates its owner and restricts it.
 */
internal fun createPrivateDirectory(path: String): Unit = memScoped {
    val token = alloc<HANDLEVar>()
    check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY.toUInt(), token.ptr) != 0) { "Cannot read the current user." }
    try {
        val size = alloc<DWORDVar>()
        GetTokenInformation(token.value, TokenUser, null, 0u, size.ptr)
        val buffer = allocArray<ByteVar>(size.value.toInt())
        check(GetTokenInformation(token.value, TokenUser, buffer, size.value, size.ptr) != 0) { "Cannot read the current user." }
        val user = requireNotNull(buffer.reinterpret<TOKEN_USER>().pointed.User.Sid)
        val aclSize = sizeOf<ACL>() + sizeOf<ACCESS_ALLOWED_ACE>() - sizeOf<DWORDVar>() + GetLengthSid(user).toLong()
        val acl = allocArray<ByteVar>(aclSize).reinterpret<ACL>()
        val descriptor = alloc<SECURITY_DESCRIPTOR>()
        val built = InitializeAcl(acl, aclSize.toUInt(), ACL_REVISION.toUInt()) != 0 &&
            AddAccessAllowedAceEx(acl, ACL_REVISION.toUInt(), (OBJECT_INHERIT_ACE or CONTAINER_INHERIT_ACE).toUInt(), FILE_ALL_ACCESS.toUInt(), user) != 0 &&
            InitializeSecurityDescriptor(descriptor.ptr, SECURITY_DESCRIPTOR_REVISION.toUInt()) != 0 &&
            SetSecurityDescriptorOwner(descriptor.ptr, user, FALSE) != 0 &&
            SetSecurityDescriptorDacl(descriptor.ptr, TRUE, acl, FALSE) != 0 &&
            SetSecurityDescriptorControl(descriptor.ptr, SE_DACL_PROTECTED.convert(), SE_DACL_PROTECTED.convert()) != 0
        check(built) { "Cannot prepare the private configuration directory." }
        val attributes = alloc<SECURITY_ATTRIBUTES>().apply {
            nLength = sizeOf<SECURITY_ATTRIBUTES>().toUInt()
            lpSecurityDescriptor = descriptor.ptr
            bInheritHandle = FALSE
        }
        if (CreateDirectoryW(path, attributes.ptr) == 0) {
            val error = GetLastError()
            check(error == ERROR_ALREADY_EXISTS.toUInt()) { "Cannot create the configuration directory (error $error)." }
        }
    } finally { CloseHandle(token.value) }
}
