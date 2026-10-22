package me.nathanfallet.teamcode.cli

import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlinx.serialization.SerializationException
import me.nathanfallet.teamcode.cli.platform.ProcessStartException
import me.nathanfallet.teamcode.client.OAuthEnrollmentException
import me.nathanfallet.teamcode.client.ReleaseDownloadException
import me.nathanfallet.teamcode.client.TeamCodeException
import kotlin.test.*

// Stand-ins named like each native engine's error, so the classifier is exercised on every target.
private class CurlIllegalStateException(message: String) : IllegalStateException(message)
private class WinHttpIllegalStateException(message: String) : IllegalStateException(message)
private class DarwinHttpRequestException(message: String) : kotlinx.io.IOException(message)
private class SSLHandshakeException(message: String) : kotlinx.io.IOException(message)
private class UnknownHostException(message: String) : kotlinx.io.IOException(message)

class CliErrorsTest {
    private val server = "https://team.example"
    private fun message(error: Throwable) = describeFailure(error, server).message
    private fun http(status: Int, code: String? = null, retryAfter: Long? = null, requestId: String? = null) =
        TeamCodeException(status, "TeamCode returned HTTP $status.", code, retryAfter, requestId)

    @Test fun `401 asks to sign in again`() {
        val text = message(http(401, "authentication_required"))
        assertContains(text, "session has expired or was revoked"); assertContains(text, "teamcode login"); assertContains(text, "HTTP 401")
        assertContains(message(http(401, "invalid_credentials")), "username or password is incorrect")
    }

    @Test fun `403 explains the server's reason and what to do`() {
        val admin = message(http(403, "forbidden"))
        assertContains(admin, "needs a team administrator"); assertContains(admin, "disabled"); assertContains(admin, "Ask an administrator")
        assertContains(message(http(403, "credential_scope")), "service key without the needed scope")
        assertContains(message(http(403, "owner_required")), "team owner")
        assertContains(message(http(403, "password_change_required")), "teamcode login")
        assertContains(message(http(403)), "Ask a team administrator for access")
        assertFalse(admin.contains("Check the server URL, authentication"), "The old catch-all text is gone")
    }

    @Test fun `404 distinguishes a missing resource from an older server`() {
        assertContains(message(http(404, "member_not_found")), "check the id or name")
        val old = message(http(404))
        assertContains(old, "older TeamCode version"); assertContains(old, VERSION)
    }

    @Test fun `409 and 429 carry Retry-After`() {
        assertContains(message(http(409, "session_busy", retryAfter = 30)), "Retry in 30 seconds.")
        val limited = message(http(429, "queue_turn", retryAfter = 300))
        assertContains(limited, "Retry in 5 minutes."); assertContains(limited, "queue_turn")
        assertContains(message(http(429)), "Wait a moment")
    }

    @Test fun `5xx says the server is unavailable with the request id`() {
        val text = message(http(503, "queue_full", requestId = "req-42"))
        assertContains(text, "unavailable or failed"); assertContains(text, "Try again"); assertContains(text, "Request id: req-42.")
        assertContains(message(http(502)), "Try again")
        val delayed = message(http(503, "queue_full", retryAfter = 30))
        assertContains(delayed, "Retry in 30 seconds."); assertFalse(delayed.contains("Try again in a moment"))
    }

    @Test fun `a redirect is explained instead of followed`() {
        assertContains(message(http(301)), "final https:// origin")
    }

    @Test fun `network failures name the server URL and the family`() {
        val cases = mapOf(
            UnknownHostException("team.example") to NetworkFailure.DNS,
            CurlIllegalStateException("Connection failed for request. Reason: Could not resolve hostname") to NetworkFailure.DNS,
            DarwinHttpRequestException("Error Domain=NSURLErrorDomain Code=-1004 \"Could not connect to the server.\"") to NetworkFailure.REFUSED,
            WinHttpIllegalStateException("Error 12029: A connection with the server could not be established") to NetworkFailure.REFUSED,
            ConnectTimeoutException("Connect timeout has expired", null) to NetworkFailure.TIMEOUT,
            CurlIllegalStateException("Timeout was reached") to NetworkFailure.TIMEOUT,
            kotlinx.io.IOException("socket closed") to NetworkFailure.OTHER,
        )
        for ((error, family) in cases) {
            assertEquals(family, networkFailure(error), error.toString())
            assertContains(message(error), server)
        }
        assertContains(message(UnknownHostException("team.example")), "does not resolve")
        assertContains(message(DarwinHttpRequestException("Code=-1004")), "Cannot reach the TeamCode server at $server")
        assertContains(describeFailure(kotlinx.io.IOException("x"), null).message, "Cannot reach the TeamCode server.")
    }

    @Test fun `TLS failures are reported as TLS and not as an unreachable server`() {
        for (error in listOf(
            SSLHandshakeException("PKIX path building failed"),
            CurlIllegalStateException("SSL peer certificate or SSH remote key was not OK"),
            DarwinHttpRequestException("Error Domain=NSURLErrorDomain Code=-1202 \"The certificate for this server is invalid.\""),
            WinHttpIllegalStateException("Error 12175: A security error occurred"),
            IllegalStateException("wrapper", SSLHandshakeException("handshake")),
        )) {
            assertEquals(NetworkFailure.TLS, networkFailure(error), error.toString())
            assertContains(message(error), "TLS error")
        }
        // A lowercase host such as tls.example.com in an engine message is not a TLS failure.
        assertEquals(NetworkFailure.REFUSED, networkFailure(CurlIllegalStateException("https://tls.example.com: Could not connect to server")))
    }

    @Test fun `OAuth failures ask to run the command again with the complete code`() {
        fun oauth(reason: OAuthEnrollmentException.Reason, provider: String = "Claude", status: Int? = null) =
            message(OAuthEnrollmentException(reason, provider, "internal", status))
        for (reason in listOf(OAuthEnrollmentException.Reason.INVALID_PASTE, OAuthEnrollmentException.Reason.STATE_MISMATCH, OAuthEnrollmentException.Reason.EXCHANGE_REJECTED)) {
            val text = oauth(reason, status = 400)
            assertContains(text, "teamcode accounts add"); assertContains(text, "complete code#state")
        }
        assertContains(oauth(OAuthEnrollmentException.Reason.EXCHANGE_REJECTED, status = 400), "expired, was already used")
        assertContains(oauth(OAuthEnrollmentException.Reason.STATE_MISMATCH, "Codex"), "localhost:1455/auth/callback URL")
        assertContains(oauth(OAuthEnrollmentException.Reason.EXCHANGE_UNREACHABLE), "Cannot reach the Claude sign-in service")
    }

    @Test fun `private file failures point at the configuration directory`() {
        for (error in listOf(okio.FileNotFoundException("/home/me/.config/teamcode/config.json"), CliOperationException("configuration_write_IOException"),
            CliOperationException("windows_private_file_5"))) {
            val text = message(error)
            assertTrue(text.contains("private TeamCode file"), text)
        }
    }

    @Test fun `other families keep an actionable message`() {
        assertContains(message(SerializationException("Unexpected JSON token")), "cannot read")
        assertContains(message(ProcessStartException(ProcessStartException.Reason.NotFound, "codex")), "Cannot find the codex executable")
        assertContains(message(CliUserException("No configuration found. Run teamcode login first.")), "Run teamcode login first")
        assertContains(message(ReleaseDownloadException("release_network_failure")), "release server")
        assertContains(message(CliOperationException("release_writer_busy")), "another TeamCode update")
        assertContains(message(IllegalStateException("boom")), "--debug")
    }

    @Test fun `debug output names only the type status and codes`() {
        val diagnostic = describeFailure(http(403, "forbidden", 5, "req-1"), server).diagnostic
        assertEquals("Debug: type=TeamCodeException status=403 code=forbidden retry_after=5 request_id=req-1 version=$VERSION", diagnostic)
        assertContains(describeFailure(IllegalStateException("x", DarwinHttpRequestException("Code=-1004")), server).diagnostic,
            "network=refused causes=DarwinHttpRequestException")
        assertTrue(debugRequested(listOf("--debug", "accounts", "add")) { null })
        assertFalse(debugRequested(listOf("run", "--", "codex", "--debug")) { null })
        assertTrue(debugRequested(listOf("status")) { if (it == "TEAMCODE_DEBUG") "1" else null })
        assertFalse(debugRequested(listOf("status")) { if (it == "TEAMCODE_DEBUG") "0" else null })
    }

    @Test fun `no secret from a message body cause or header ever reaches the output`() {
        val secret = "tc_live_SECRET_9f8e7d"
        val errors = listOf(
            TeamCodeException(401, "Bearer $secret", null), TeamCodeException(500, secret, null, null, null),
            OAuthEnrollmentException(OAuthEnrollmentException.Reason.INVALID_PASTE, "Claude", "pasted $secret#state"),
            CurlIllegalStateException("Connection failed for request: Authorization: Bearer $secret. Reason: Could not connect to server"),
            DarwinHttpRequestException("Code=-1202 NSErrorFailingURLStringKey=https://u:$secret@team.example"),
            IllegalStateException("outer", IllegalArgumentException("refresh_token=$secret", SSLHandshakeException(secret))),
            SerializationException("""Unexpected JSON at {"accessToken":"$secret"}"""),
            okio.IOException("cannot open /tmp/$secret"),
            IllegalArgumentException("Fail to parse url: https://$secret"),
            RuntimeException(secret),
        )
        for (error in errors) {
            val failure = describeFailure(error, server)
            assertFalse(failure.message.contains(secret), "${error::class.simpleName}: ${failure.message}")
            assertFalse(failure.diagnostic.contains(secret), "${error::class.simpleName}: ${failure.diagnostic}")
            assertFalse(failure.diagnostic.contains("Bearer"))
        }
    }
}
