package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.teamcode.api.models.AccountEnrollmentPolicyView
import kotlin.test.*

/** The sign-in pieces the CLI and the companion app share: PKCE, random codes, account types and the enrollment check. */
class OAuthSignInTest {
    /** Deterministic "random" bytes: each call returns 32 bytes of the next value. */
    private class CountingBytes {
        var calls = 0
        fun next(count: Int): ByteArray { calls++; return ByteArray(count) { calls.toByte() } }
    }

    @Test fun theChallengeIsTheRfc7636Example() {
        // RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun randomCodesAreUnpaddedBase64UrlOf32Bytes() {
        val code = randomOAuthCode { ByteArray(it) { 0xFB.toByte() } }
        assertEquals(43, code.length)
        assertTrue(code.none { it == '+' || it == '/' || it == '=' }, code)
        assertEquals("-_v7-_v7-_v7-_v7-_v7-_v7-_v7-_v7-_v7-_v7-_s", code)
        assertFailsWith<IllegalArgumentException> { randomOAuthCode { ByteArray(16) } }
    }

    @Test fun anAttemptDrawsItsSecretsOnceAndShowsOnlyTheLink() {
        val bytes = CountingBytes()
        val signIn = OAuthSignIn.start(ClaudeOAuthEnrollment, bytes::next)
        assertEquals(3, bytes.calls, "state, verifier and operation id")
        val url = Url(signIn.authorizationUrl)
        val state = randomOAuthCode { ByteArray(it) { 1 } }
        val verifier = randomOAuthCode { ByteArray(it) { 2 } }
        assertEquals(state, url.parameters["state"])
        assertEquals(pkceChallenge(verifier), url.parameters["code_challenge"])
        assertFalse(signIn.authorizationUrl.contains(verifier), "The verifier never leaves the device")
        assertEquals("OAuthSignIn(anthropic, [REDACTED])", signIn.toString())
        assertEquals("abc", signIn.code("abc#$state"))
        assertEquals(OAuthEnrollmentException.Reason.STATE_MISMATCH, assertFailsWith<OAuthEnrollmentException> { signIn.code("abc#other") }.reason)
    }

    @Test fun theExchangeSendsThisAttemptsVerifierAndOperation() = runTest {
        var body = ""
        val engine = MockEngine { request ->
            body = (request.body as TextContent).text
            respond("""{"access_token":"access","refresh_token":"refresh","expires_in":3600}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val signIn = OAuthSignIn.start(CodexOAuthEnrollment, CountingBytes()::next)
        val state = randomOAuthCode { ByteArray(it) { 1 } }
        val request = signIn.exchange("http://localhost:1455/auth/callback?code=the-code&state=$state") { configure -> HttpClient(engine) { configure(this) } }
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals("the-code", sent["code"]?.jsonPrimitive?.content)
        assertEquals(randomOAuthCode { ByteArray(it) { 2 } }, sent["code_verifier"]?.jsonPrimitive?.content)
        assertEquals(randomOAuthCode { ByteArray(it) { 3 } }, request.operationId)
        assertEquals("codex", request.provider)
        assertTrue(request.freshSignIn)
    }

    @Test fun anInvalidPasteIsRefusedBeforeAnyRequest() = runTest {
        val engine = MockEngine { error("no request for an invalid paste") }
        val signIn = OAuthSignIn.start(ClaudeOAuthEnrollment, CountingBytes()::next)
        val refused = assertFailsWith<OAuthEnrollmentException> { signIn.exchange("not a code") { configure -> HttpClient(engine) { configure(this) } } }
        assertEquals(OAuthEnrollmentException.Reason.INVALID_PASTE, refused.reason)
    }

    @Test fun theMenuListsTwoSubscriptionsThenTheUnavailableApiKey() {
        assertEquals(listOf("anthropic-subscription", "codex-subscription", "anthropic-api"), AccountType.flags)
        assertEquals(ClaudeOAuthEnrollment, AccountType.ANTHROPIC_SUBSCRIPTION.enrollment)
        assertEquals(CodexOAuthEnrollment, AccountType.CODEX_SUBSCRIPTION.enrollment)
        assertNull(AccountType.ANTHROPIC_API.enrollment)
        assertEquals(listOf(true, true, false), AccountType.entries.map { it.supported })
        assertEquals(AccountType.entries.filter { it.supported }.map { it.enrollment?.provider }, AccountType.entries.filter { it.supported }.map { it.provider })
        assertEquals(AccountType.CODEX_SUBSCRIPTION, AccountType.fromProvider("codex"))
        assertEquals(AccountType.ANTHROPIC_SUBSCRIPTION, AccountType.fromProvider("claude-max"))
    }

    @Test fun theEnrollmentCheckFollowsTheServersPolicyOrTheRoleOnAnOlderServer() = runTest {
        val features = listOf(ACCOUNT_ENROLLMENT_POLICY_FEATURE)
        val noRole: suspend () -> String = { error("the policy answer is enough") }
        assertEquals(AccountEnrollmentAccess.ALLOWED, accountEnrollmentAccess(features, { AccountEnrollmentPolicyView("members", true) }, noRole))
        assertEquals(AccountEnrollmentAccess.ADMINISTRATORS_ONLY, accountEnrollmentAccess(features, { AccountEnrollmentPolicyView("administrators", false) }, noRole))
        assertEquals(AccountEnrollmentAccess.SESSION_CANNOT_ENROLL, accountEnrollmentAccess(features, { AccountEnrollmentPolicyView("members", false) }, noRole))
        val noPolicy: suspend () -> AccountEnrollmentPolicyView = { error("an older server has no policy endpoint") }
        assertEquals(AccountEnrollmentAccess.ALLOWED, accountEnrollmentAccess(emptyList(), noPolicy) { "OWNER" })
        assertEquals(AccountEnrollmentAccess.ALLOWED, accountEnrollmentAccess(emptyList(), noPolicy) { "ADMIN" })
        assertEquals(AccountEnrollmentAccess.ADMINISTRATORS_ONLY, accountEnrollmentAccess(emptyList(), noPolicy) { "MEMBER" })
    }
}
