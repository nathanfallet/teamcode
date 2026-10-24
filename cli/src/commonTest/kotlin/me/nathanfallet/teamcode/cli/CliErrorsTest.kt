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

    @Test fun `a missing cloud profile is told from an unassigned one and both point at the list`() {
        val missing = message(http(404, "profile_not_found"))
        assertContains(missing, "No cloud profile you can use"); assertContains(missing, "teamcode cloud profiles"); assertContains(missing, "--member")
        val unassigned = message(http(403, "profile_unassigned"))
        assertContains(unassigned, "session owner is not assigned"); assertContains(unassigned, "teamcode cloud profiles"); assertContains(unassigned, "--member <owner id>")
        assertFalse("paused" in unassigned, "No longer read as a paused group")
        assertContains(message(http(403, "group_paused")), "paused")
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
            CurlIllegalStateException("Connection failed for request. Reason: Could not resolve hostname") to NetworkFailure.DNS,
            DarwinHttpRequestException("Error Domain=NSURLErrorDomain Code=-1004 \"Could not connect to the server.\"") to NetworkFailure.REFUSED,
            WinHttpIllegalStateException("Error 12029: A connection with the server could not be established") to NetworkFailure.REFUSED,
            WinHttpIllegalStateException("Error 12007") to NetworkFailure.DNS,
            ConnectTimeoutException("Connect timeout has expired", null) to NetworkFailure.TIMEOUT,
            CurlIllegalStateException("Timeout was reached") to NetworkFailure.TIMEOUT,
            CurlIllegalStateException("Reason: Send failure") to NetworkFailure.OTHER,
        )
        for ((error, family) in cases) {
            assertEquals(family, networkFailure(error), error.toString())
            assertContains(message(error), server)
        }
        assertContains(message(CurlIllegalStateException("Could not resolve hostname")), "does not resolve")
        assertContains(message(DarwinHttpRequestException("Code=-1004")), "Cannot reach the TeamCode server at $server")
        assertContains(describeFailure(CurlIllegalStateException("x"), null).message, "Cannot reach the TeamCode server.")
        // A port number in another engine's message is not a WinHTTP code, and a plain I/O error is not a network failure.
        assertEquals(NetworkFailure.OTHER, networkFailure(CurlIllegalStateException("https://team.example:12002/ failed")))
        assertNull(networkFailure(kotlinx.io.IOException("No space left on device")))
    }

    @Test fun `TLS failures are reported as TLS and not as an unreachable server`() {
        for (error in listOf(
            CurlIllegalStateException("SSL peer certificate or SSH remote key was not OK"),
            DarwinHttpRequestException("Error Domain=NSURLErrorDomain Code=-1202 \"The certificate for this server is invalid.\""),
            WinHttpIllegalStateException("Error 12175: A security error occurred"),
            IllegalStateException("wrapper", CurlIllegalStateException("SSL connect error")),
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
        assertContains(oauth(OAuthEnrollmentException.Reason.EXCHANGE_UNAVAILABLE, status = 503), "Wait a moment")
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
        assertContains(message(ReleaseDownloadException("release_network_failure")), "Cannot reach GitHub's release servers")
        assertContains(message(CliOperationException("release_writer_busy")), "another TeamCode update")
        assertContains(message(IllegalStateException("boom")), "--debug")
    }

    /** A release failure as `teamcode update` reports it (converted by releaseOperation) and as `teamcode ui` does (raw). */
    private fun release(code: String, detail: String? = null): String {
        val update = message(CliOperationException(code, detail))
        assertEquals(update, message(ReleaseDownloadException(code, detail)))
        assertContains(update, "($code)")
        detail?.let { assertContains(update, "Cause: $it.") }
        return update
    }

    @Test fun `a release timeout says the download was too slow and offers the install script`() {
        val text = release("release_timeout", "the download did not finish within its time budget")
        assertContains(text, "did not finish in time"); assertContains(text, INSTALL_SCRIPT_COMMAND); assertContains(text, "keeps your login")
        assertContains(release("release_operation_timeout"), "did not finish in time")
    }

    @Test fun `a release dns failure names the hosts and the curl test`() {
        val text = release("release_dns_failure", "UnknownHostException: api.github.com")
        assertContains(text, "does not resolve"); assertContains(text, "curl -sSI https://api.github.com"); assertContains(text, "DNS")
    }

    @Test fun `a release tls failure points at certificates clock and intercepting proxies`() {
        val text = release("release_tls_failure", "CurlIllegalStateException: SSL certificate problem: unable to get local issuer certificate")
        assertContains(text, "certificate"); assertContains(text, "ca-certificates"); assertContains(text, "system date")
        assertContains(text, "curl -sSI https://release-assets.githubusercontent.com")
    }

    @Test fun `a release proxy failure points at the proxy settings`() {
        val text = release("release_proxy_failure", "CurlIllegalStateException: Received HTTP code 407 from proxy after CONNECT")
        assertContains(text, "proxy"); assertContains(text, "HTTPS_PROXY"); assertFalse(text.contains("Check connectivity"))
    }

    @Test fun `a release connection failure lists what to check and how to test`() {
        val text = release("release_connection_failure", "ConnectException: Connection refused")
        assertContains(text, "refused or reset"); assertContains(text, "firewall"); assertContains(text, "curl -sSI")
        assertContains(release("release_connection_closed"), "closed before the release download finished")
    }

    @Test fun `an unclassified release network failure keeps its code and offers the install script`() {
        val text = release("release_network_failure", "CurlIllegalStateException: Connection failed for request. Reason: Send failure")
        assertContains(text, "Cannot reach GitHub's release servers"); assertContains(text, INSTALL_SCRIPT_COMMAND)
    }

    @Test fun `an unreadable release answer is not called a network failure`() {
        val text = release("release_response_invalid", "IllegalStateException: Header line length limit exceeded")
        assertContains(text, "cannot read"); assertFalse(text.contains("Cannot reach")); assertContains(text, INSTALL_SCRIPT_COMMAND)
    }

    @Test fun `a github rate limit says to wait and how long`() {
        val text = release("release_rate_limited", "GitHub API rate limit reached, it resets in about 10 minutes")
        assertContains(text, "60 per hour"); assertContains(text, "about 10 minutes"); assertContains(text, "Wait")
        assertEquals("", releaseCause("")); assertEquals(" Cause: cut short…", releaseCause("cut short…"))
    }

    @Test fun `a local write failure is about disk space and permissions not the network`() {
        val text = release("release_write_failure", "IOException: No space left on device")
        assertContains(text, "Cannot save"); assertContains(text, "disk space"); assertContains(text, "df -h")
        assertFalse(text.contains("network")); assertFalse(text.contains("Cannot reach"))
    }

    @Test fun `an unexpected release failure asks for the debug output`() {
        val text = release("release_unexpected_failure", "UnsupportedOperationException: boom")
        assertContains(text, "--debug"); assertContains(text, INSTALL_SCRIPT_COMMAND)
    }

    @Test fun `missing releases and github outages say so`() {
        assertContains(release("release_http_404"), "not published")
        assertContains(release("release_http_503"), "unavailable")
    }

    @Test fun `debug output carries the release cause`() {
        assertEquals("Debug: type=CliOperationException code=release_dns_failure cause=\"UnknownHostException: api.github.com\" version=$VERSION",
            describeFailure(CliOperationException("release_dns_failure", "UnknownHostException: api.github.com"), server).diagnostic)
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
            IllegalStateException("outer", IllegalArgumentException("refresh_token=$secret", CurlIllegalStateException("SSL $secret"))),
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
