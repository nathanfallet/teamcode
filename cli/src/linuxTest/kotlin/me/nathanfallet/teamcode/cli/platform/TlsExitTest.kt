@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import io.ktor.client.HttpClient
import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            withTimeout(60_000) {
                runInteractiveProcess(
                    executable,
                    listOf("--ktest_filter=${TlsExitTest::class.qualifiedName}.${::exitingProcess.name}"),
                    mapOf("TEAMCODE_TLS_EXIT_PROBE" to "1"),
                    quiet = false,
                )
            }
        }
        assertNotEquals(teamcode_tls_exit_probe_stopped_code(), code, "OpenSSL was cleaned up while the process was still exiting")
        assertEquals(0, code)
    }

    /** Runs alone in the child: it uses and closes the Curl engine like a command, exits, then the probe checks OpenSSL. */
    @Test fun exitingProcess() {
        if (environment("TEAMCODE_TLS_EXIT_PROBE") == null) return
        HttpClient().close()
    }
}
