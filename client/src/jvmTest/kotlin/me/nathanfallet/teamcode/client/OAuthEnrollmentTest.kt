package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class OAuthEnrollmentTest {
    @Test fun authorizationParametersMatchEachRegisteredPublicClient() {
        for (provider in listOf(ClaudeOAuthEnrollment, CodexOAuthEnrollment)) {
            val url = Url(provider.authorizationUrl("unique-state", "pkce-challenge"))
            assertEquals(URLProtocol.HTTPS, url.protocol)
            assertEquals("unique-state", url.parameters["state"])
            assertEquals("pkce-challenge", url.parameters["code_challenge"])
            assertEquals("S256", url.parameters["code_challenge_method"])
            assertEquals(provider.redirectUri, url.parameters["redirect_uri"])
            assertEquals(provider.clientId, url.parameters["client_id"])
        }
        val codex = Url(CodexOAuthEnrollment.authorizationUrl("state", "challenge"))
        assertEquals("openid profile email offline_access", codex.parameters["scope"])
        assertEquals("true", codex.parameters["id_token_add_organizations"])
        assertEquals("http://localhost:1455/auth/callback", codex.parameters["redirect_uri"])
    }

    @Test fun pastedCodesRequireTheCurrentStateAndCodexCallbackOrigin() {
        assertEquals("code", ClaudeOAuthEnrollment.parseCode("code#state", "state"))
        assertFailsWith<IllegalArgumentException> { ClaudeOAuthEnrollment.parseCode("code#other", "state") }
        val callback = "http://localhost:1455/auth/callback"
        assertEquals("a+b", CodexOAuthEnrollment.parseCode("$callback?code=a%2Bb&state=state", "state"))
        assertEquals("code", CodexOAuthEnrollment.parseCode("localhost:1455/auth/callback?code=code&state=state", "state"))
        for (invalid in listOf(
            "$callback?code=code", "$callback?code=code&state=other", "$callback?state=state",
            "$callback?code=code&state=state&state=state", "$callback?code=code&code=other&state=state",
            "$callback?error=denied&state=state", "https://attacker.example/auth/callback?code=code&state=state",
            "http://localhost:1456/auth/callback?code=code&state=state",
            CodexOAuthEnrollment.authorizationUrl("state", "challenge"),
        )) assertFailsWith<IllegalArgumentException> { CodexOAuthEnrollment.parseCode(invalid, "state") }
    }

    @Test fun exchangeIsBoundedProviderSpecificAndDoesNotSendTeamAuthentication() = runBlocking {
        for (provider in listOf(ClaudeOAuthEnrollment, CodexOAuthEnrollment)) {
            OAuthEnrollmentClient(provider) { configure ->
                HttpClient(MockEngine { request ->
                    assertEquals(provider.tokenEndpoint, request.url.toString())
                    assertNull(request.headers[HttpHeaders.Authorization])
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("authorization_code", body["grant_type"]!!.jsonPrimitive.content)
                    assertEquals("verifier", body["code_verifier"]!!.jsonPrimitive.content)
                    assertEquals(provider.clientId, body["client_id"]!!.jsonPrimitive.content)
                    assertEquals(provider.redirectUri, body["redirect_uri"]!!.jsonPrimitive.content)
                    if (provider == ClaudeOAuthEnrollment) assertEquals("state", body["state"]!!.jsonPrimitive.content)
                    else assertNull(body["state"])
                    respond("""{"access_token":"access-secret","refresh_token":"refresh-secret","expires_in":3600}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }) { configure(this) }
            }.use { client ->
                val result = client.exchange("code", "state", "verifier", "operation-id")
                assertEquals(provider.provider, result.provider)
                assertEquals("access-secret", result.accessToken)
                assertEquals("operation-id", result.operationId)
                assertFalse(result.toString().contains("access-secret"))
            }
        }
    }

    @Test fun redirectOrInvalidCredentialsCannotBeEnrolled() = runBlocking {
        for ((status, body) in listOf(
            HttpStatusCode.Found to "",
            HttpStatusCode.Unauthorized to "provider-secret-diagnostic",
            HttpStatusCode.OK to """{"access_token":"secret","expires_in":3600}""",
            HttpStatusCode.OK to "x".repeat(65_537),
            HttpStatusCode.OK to "malformed-provider-secret",
            HttpStatusCode.OK to """{"access_token":true,"refresh_token":"refresh","expires_in":3600}""",
        )) {
            var requests = 0
            OAuthEnrollmentClient(CodexOAuthEnrollment) { configure ->
                HttpClient(MockEngine {
                    requests++
                    respond(body, status, headersOf(HttpHeaders.Location, "https://attacker.example"))
                }) { configure(this) }
            }.use { client ->
                val error = assertFails { client.exchange("code", "state", "verifier", "operation-id") }
                assertFalse(error.message.orEmpty().contains("provider-secret"))
                assertEquals(1, requests, "OAuth exchange must not follow a redirect")
            }
        }
    }

    @Test fun signInFailuresCarryAReasonAndNeverThePasteOrProviderAnswer() = runBlocking {
        fun reason(block: () -> Unit) = assertFailsWith<OAuthEnrollmentException> { block() }.reason
        assertEquals(OAuthEnrollmentException.Reason.STATE_MISMATCH, reason { ClaudeOAuthEnrollment.parseCode("secret-code#other", "state") })
        assertEquals(OAuthEnrollmentException.Reason.INVALID_PASTE, reason { ClaudeOAuthEnrollment.parseCode("secret-code", "state") })
        assertEquals(OAuthEnrollmentException.Reason.INVALID_PASTE, reason { ClaudeOAuthEnrollment.parseCode("", "state") })
        assertEquals(OAuthEnrollmentException.Reason.STATE_MISMATCH, reason { CodexOAuthEnrollment.parseCode("http://localhost:1455/auth/callback?code=c&state=x", "state") })
        assertEquals(OAuthEnrollmentException.Reason.PROVIDER_REFUSED, reason { CodexOAuthEnrollment.parseCode("http://localhost:1455/auth/callback?error=denied&state=state", "state") })
        assertEquals(OAuthEnrollmentException.Reason.INVALID_PASTE, reason { CodexOAuthEnrollment.parseCode("https://example.com/?code=c&state=state", "state") })
        for ((engine, expected) in listOf(
            MockEngine { respond("provider-secret-diagnostic", HttpStatusCode.BadRequest) } to OAuthEnrollmentException.Reason.EXCHANGE_REJECTED,
            MockEngine { respond("provider-secret-diagnostic", HttpStatusCode.OK) } to OAuthEnrollmentException.Reason.INVALID_TOKEN_RESPONSE,
            MockEngine { throw java.net.ConnectException("connect to provider-secret-host failed") } to OAuthEnrollmentException.Reason.EXCHANGE_UNREACHABLE,
        )) {
            OAuthEnrollmentClient(ClaudeOAuthEnrollment) { configure -> HttpClient(engine) { configure(this) } }.use { client ->
                val error = assertFailsWith<OAuthEnrollmentException> { client.exchange("code", "state", "verifier", "operation-id") }
                assertEquals(expected, error.reason)
                assertEquals("Claude", error.provider)
                assertFalse(error.message.orEmpty().contains("secret"))
                assertNull(error.cause)
                if (expected == OAuthEnrollmentException.Reason.EXCHANGE_REJECTED) assertEquals(400, error.status)
            }
        }
    }
}
