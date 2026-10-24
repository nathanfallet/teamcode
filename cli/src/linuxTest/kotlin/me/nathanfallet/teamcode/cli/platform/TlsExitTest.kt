@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.cli.interop.process.teamcode_executable
import me.nathanfallet.teamcode.cli.interop.tlsexitprobe.teamcode_tls_exit_probe_stopped_code
import kotlin.test.*

/**
 * Ktor's Curl engine closes asynchronously: HttpClient.close() returns while its worker thread still runs
 * curl_multi_cleanup(), which frees TLS contexts. If OpenSSL's atexit() cleanup has already destroyed its locks by
 * then, that worker crashes after the command's output (a segmentation fault seen after `teamcode update` failed).
 */
class TlsExitTest {
    @Test fun tlsLibraryStaysUsableUntilTheProcessHasExited() {
        val executable = memScoped {
            val buffer = allocArray<ByteVar>(16_384)
            check(teamcode_executable(buffer, 16_384u) == 0)
            buffer.toKString()
        }
        val code = runBlocking {
            runInteractiveProcess(
                executable,
                listOf("--ktest_filter=${TlsExitTest::class.qualifiedName}.${::exitingProcess.name}"),
                mapOf("TEAMCODE_TLS_EXIT_PROBE" to "1"),
                quiet = true,
            )
        }
        assertNotEquals(teamcode_tls_exit_probe_stopped_code(), code, "OpenSSL was cleaned up while the process was still exiting")
        assertEquals(0, code)
    }

    /** Runs alone in the child: it exits normally, then the probe checks the TLS library from a destructor. */
    @Test fun exitingProcess() {}
}
