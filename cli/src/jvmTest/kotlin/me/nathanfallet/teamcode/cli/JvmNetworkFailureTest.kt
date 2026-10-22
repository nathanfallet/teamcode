package me.nathanfallet.teamcode.cli

import kotlin.test.*

/** The JVM types an OkHttp engine raises, and local I/O errors that share java.io.IOException with them. */
class JvmNetworkFailureTest {
    @Test fun `JVM network types map to their family`() {
        assertEquals(NetworkFailure.DNS, networkFailure(java.net.UnknownHostException("team.example")))
        assertEquals(NetworkFailure.REFUSED, networkFailure(java.net.ConnectException("Connection refused")))
        assertEquals(NetworkFailure.TLS, networkFailure(javax.net.ssl.SSLHandshakeException("PKIX path building failed")))
        assertEquals(NetworkFailure.TIMEOUT, networkFailure(java.net.SocketTimeoutException("Read timed out")))
    }

    @Test fun `local disk and permission failures are file errors and not network errors`() {
        for (error in listOf(java.io.IOException("No space left on device"), java.io.EOFException("truncated"),
            java.nio.file.AccessDeniedException("/home/me/.config/teamcode/config.json"))) {
            assertNull(networkFailure(error), error.toString())
            assertContains(describeFailure(error, "https://team.example").message, "private TeamCode file")
        }
    }
}
