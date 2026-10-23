@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.config

import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.cinterop.*
import okio.FileSystem
import platform.windows.*

class MingwConfigLockTest {
    private val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "teamcode-lock-${Uuid.random()}"
    private val directory = root / "teamcode"
    @AfterTest fun clean() { FileSystem.SYSTEM.deleteRecursively(root, mustExist = false) }

    /**
     * The lock is often the first file of a first run. On an elevated token (the CI runner, an administrator shell) a
     * plain CreateDirectory gives the directory to Administrators, and the CLI's next private write refuses it.
     */
    @Test fun `the lock creates the directory owned by the user with one protected entry`() {
        withFileLock((directory / "config.lock").toString()) { }
        memScoped {
            val size = alloc<DWORDVar>()
            val information = (OWNER_SECURITY_INFORMATION or DACL_SECURITY_INFORMATION).toUInt()
            GetFileSecurityW(directory.toString(), information, null, 0u, size.ptr)
            val descriptor = allocArray<ByteVar>(size.value.toInt())
            assertNotEquals(0, GetFileSecurityW(directory.toString(), information, descriptor, size.value, size.ptr))
            val owner = alloc<PSIDVar>()
            val defaulted = alloc<WINBOOLVar>()
            assertNotEquals(0, GetSecurityDescriptorOwner(descriptor, owner.ptr, defaulted.ptr))
            assertNotEquals(0, EqualSid(owner.value, currentUser()), "The directory must belong to the token user")
            val present = alloc<WINBOOLVar>()
            val dacl = alloc<PACLVar>()
            assertNotEquals(0, GetSecurityDescriptorDacl(descriptor, present.ptr, dacl.ptr, defaulted.ptr))
            assertEquals(1, dacl.value!!.pointed.AceCount.toInt())
            val control = alloc<SECURITY_DESCRIPTOR_CONTROLVar>()
            val revision = alloc<DWORDVar>()
            assertNotEquals(0, GetSecurityDescriptorControl(descriptor, control.ptr, revision.ptr))
            assertTrue(control.value.toInt() and SE_DACL_PROTECTED != 0, "The entry must not be widened by inheritance")
        }
    }

    @Test fun `an existing directory is reused`() {
        withFileLock((directory / "config.lock").toString()) { }
        assertEquals("again", withFileLock((directory / "config.lock").toString()) { "again" })
    }

    private fun MemScope.currentUser(): PSID {
        val token = alloc<HANDLEVar>()
        check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY.toUInt(), token.ptr) != 0)
        val size = alloc<DWORDVar>()
        GetTokenInformation(token.value, TokenUser, null, 0u, size.ptr)
        val buffer = allocArray<ByteVar>(size.value.toInt())
        check(GetTokenInformation(token.value, TokenUser, buffer, size.value, size.ptr) != 0)
        CloseHandle(token.value)
        return requireNotNull(buffer.reinterpret<TOKEN_USER>().pointed.User.Sid)
    }
}
