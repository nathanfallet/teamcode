@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.config

import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.cinterop.*
import okio.FileSystem
import platform.posix.*

class PosixConfigLockTest {
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "teamcode-lock-${Uuid.random()}"
    private val lock = (directory / "teamcode" / "config.lock").toString()
    @AfterTest fun clean() { FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false) }

    /** Exit status of a forked child that only tries the record lock without waiting (0: it was free, 1: held). */
    private fun probeFromAnotherProcess(): Int = memScoped {
        // Allocated before fork: the child makes only async-signal-safe calls before _exit.
        val request = alloc<flock>()
        request.l_type = F_WRLCK.convert()
        request.l_whence = SEEK_SET.convert()
        request.l_start = 0
        request.l_len = 0
        val status = alloc<IntVar>()
        val child = fork()
        if (child == 0) {
            val fd = open(lock, O_RDWR)
            _exit(if (fd >= 0 && fcntl(fd, F_SETLK, request.ptr) == 0) 0 else 1)
        }
        assertTrue(child > 0)
        assertEquals(child, waitpid(child, status.ptr, 0))
        (status.value shr 8) and 0xff
    }

    @Test fun `another process sees the lock while it is held and not after`() {
        withFileLock(lock) { assertEquals(1, probeFromAnotherProcess()) }
        assertEquals(0, probeFromAnotherProcess())
    }

    @Test fun `the lock file is private and never deleted`() {
        withFileLock(lock) { }
        val metadata = memScoped { alloc<stat>().also { assertEquals(0, stat(lock, it.ptr)) }.st_mode.toInt() }
        assertEquals(384, metadata and 511)
    }
}
