package me.nathanfallet.teamcode.infrastructure

import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.github.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.github.GitHubApiException
import me.nathanfallet.teamcode.infrastructure.github.GitHubAppClient
import me.nathanfallet.teamcode.infrastructure.github.GitHubEndpoints
import kotlin.test.*

/** GitHub's documented request and response shapes over a mock engine; no real GitHub call. */
class GitHubAppClientTest {
    private val endpoints = GitHubEndpoints("https://github.test", "https://api.github.test")
    private val credentials = GitHubAppCredentials(123, "Iv23liSynthetic", "synthetic-client-secret-0123456789", "unused")
    private val requests = mutableListOf<HttpRequestData>()
    private var reply: (HttpRequestData) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to "{}" }
    private val client = GitHubAppClient(endpoints, MockEngine { request ->
        requests += request
        val (status, body) = reply(request)
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }, clock = { 1_000_000L })

    private fun HttpRequestData.form() = parseQueryString(((body as OutgoingContent.ByteArrayContent).bytes()).decodeToString())

    @Test fun verifiesTheAppWithAJwtSignedByItsPkcs1PrivateKey() = runBlocking {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pkcs8 = pair.private.encoded
        assertEquals(0x04, pkcs8[22].toInt()); assertEquals(0x82.toByte(), pkcs8[23])
        // GitHub's downloaded keys are PKCS#1: the RSAPrivateKey inside the PKCS#8 wrapper.
        val pkcs1 = pkcs8.copyOfRange(26, pkcs8.size)
        val pem = "-----BEGIN RSA PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs1) + "\n-----END RSA PRIVATE KEY-----\n"
        reply = { HttpStatusCode.OK to """{"id":123,"slug":"teamcode-test","name":"TeamCode test","client_id":"Iv23liSynthetic"}""" }
        val metadata = client.verifyApp(123, "Iv23liSynthetic", pem)
        assertEquals(GitHubAppMetadata(123, "Iv23liSynthetic", "teamcode-test", "TeamCode test"), metadata)
        val request = requests.single()
        assertEquals("https://api.github.test/app", request.url.toString())
        assertEquals("2022-11-28", request.headers["X-GitHub-Api-Version"])
        val (header, payload, signature) = request.headers[HttpHeaders.Authorization]!!.removePrefix("Bearer ").split('.')
        val decoder = Base64.getUrlDecoder()
        assertTrue(Signature.getInstance("SHA256withRSA").apply { initVerify(pair.public as RSAPublicKey); update("$header.$payload".encodeToByteArray()) }.verify(decoder.decode(signature)))
        val claims = Json.parseToJsonElement(decoder.decode(payload).decodeToString()).jsonObject
        assertEquals("Iv23liSynthetic", claims["iss"]!!.jsonPrimitive.content)
        assertEquals(940L, claims["iat"]!!.jsonPrimitive.long); assertEquals(1540L, claims["exp"]!!.jsonPrimitive.long)

        val pkcs8Pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pkcs8) + "\n-----END PRIVATE KEY-----"
        reply = { HttpStatusCode.OK to """{"id":999,"client_id":"Iv23liSynthetic"}""" }
        assertEquals("github_app_mismatch", assertFailsWith<TeamFailure> { client.verifyApp(123, "Iv23liSynthetic", pkcs8Pem) }.code)
        reply = { HttpStatusCode.Unauthorized to """{"message":"A JSON web token could not be decoded"}""" }
        assertEquals("github_app_rejected", assertFailsWith<TeamFailure> { client.verifyApp(123, "Iv23liSynthetic", pem) }.code)
        assertEquals("invalid_github_app", assertFailsWith<TeamFailure> { client.verifyApp(123, "Iv23liSynthetic", "-----BEGIN RSA PRIVATE KEY-----\nnot-base64!\n-----END RSA PRIVATE KEY-----") }.code)
    }

    @Test fun deviceFlowFollowsGitHubsPollingAnswers() = runBlocking {
        reply = { HttpStatusCode.OK to """{"device_code":"dc-secret","user_code":"WDJB-MJHT","verification_uri":"https://github.test/login/device","expires_in":899,"interval":5}""" }
        val device = client.startDevice("Iv23liSynthetic")
        assertEquals("WDJB-MJHT", device.userCode); assertEquals(899, device.expiresInSeconds); assertFalse(device.toString().contains("dc-secret"))
        assertEquals("https://github.test/login/device/code", requests.last().url.toString())
        assertEquals("Iv23liSynthetic", requests.last().form()["client_id"])
        assertEquals("application/json", requests.last().headers[HttpHeaders.Accept])
        reply = { HttpStatusCode.OK to """{"device_code":"dc","user_code":"WDJB-MJHT","verification_uri":"https://evil.test/login/device","expires_in":899,"interval":5}""" }
        assertFailsWith<GitHubApiException> { client.startDevice("Iv23liSynthetic") }
        reply = { HttpStatusCode.OK to """{"error":"device_flow_disabled"}""" }
        assertEquals("github_device_flow_disabled", assertFailsWith<TeamFailure> { client.startDevice("Iv23liSynthetic") }.code)

        fun answer(json: String) { reply = { HttpStatusCode.OK to json } }
        answer("""{"error":"authorization_pending"}"""); assertEquals(GitHubDevicePoll.Pending, client.pollDevice("Iv23liSynthetic", "dc-secret"))
        val form = requests.last().form()
        assertEquals("dc-secret", form["device_code"]); assertEquals("urn:ietf:params:oauth:grant-type:device_code", form["grant_type"]); assertNull(form["client_secret"])
        answer("""{"error":"slow_down","interval":10}"""); assertEquals(GitHubDevicePoll.SlowDown(10), client.pollDevice("Iv23liSynthetic", "dc"))
        answer("""{"error":"expired_token"}"""); assertEquals(GitHubDevicePoll.Expired, client.pollDevice("Iv23liSynthetic", "dc"))
        answer("""{"error":"access_denied"}"""); assertEquals(GitHubDevicePoll.Denied, client.pollDevice("Iv23liSynthetic", "dc"))
        answer("""{"access_token":"ghu_synthetic","expires_in":28800,"refresh_token":"ghr_synthetic","refresh_token_expires_in":15897600,"token_type":"bearer","scope":""}""")
        val tokens = assertIs<GitHubDevicePoll.Authorized>(client.pollDevice("Iv23liSynthetic", "dc")).tokens
        assertEquals("ghu_synthetic", tokens.accessToken); assertEquals(1_000_000L + 28_800_000, tokens.accessExpiresAt)
        assertEquals("ghr_synthetic", tokens.refreshToken); assertEquals(1_000_000L + 15_897_600_000, tokens.refreshExpiresAt)
        answer("""{"access_token":"gho_oauth_app_token","token_type":"bearer"}""")
        assertEquals("github_app_misconfigured", assertFailsWith<TeamFailure> { client.pollDevice("Iv23liSynthetic", "dc") }.code, "Only GitHub App user tokens")
    }

    @Test fun webExchangeRefreshAndRevocationSendTheAppCredentials() = runBlocking {
        reply = { HttpStatusCode.OK to """{"access_token":"ghu_web","expires_in":28800,"refresh_token":"ghr_web","refresh_token_expires_in":15897600}""" }
        assertEquals("ghu_web", client.exchangeCode(credentials, "code-1", "https://teamcode.test/dashboard/github/callback", "verifier-1").accessToken)
        val exchange = requests.last().form()
        assertEquals(listOf("code-1", "verifier-1", "https://teamcode.test/dashboard/github/callback", "synthetic-client-secret-0123456789"),
            listOf(exchange["code"], exchange["code_verifier"], exchange["redirect_uri"], exchange["client_secret"]))
        reply = { HttpStatusCode.OK to """{"error":"bad_verification_code"}""" }
        assertEquals("github_link_not_found", assertFailsWith<TeamFailure> { client.exchangeCode(credentials, "c", "u", "v") }.code)
        val url = Url(client.authorizationUrl("Iv23liSynthetic", "https://teamcode.test/cb", "state-1", "challenge-1"))
        assertEquals("https://github.test/login/oauth/authorize", url.toString().substringBefore('?'))
        assertEquals(listOf("Iv23liSynthetic", "https://teamcode.test/cb", "state-1", "challenge-1", "S256"),
            listOf("client_id", "redirect_uri", "state", "code_challenge", "code_challenge_method").map { url.parameters[it] })

        reply = { HttpStatusCode.OK to """{"access_token":"ghu_next","expires_in":28800,"refresh_token":"ghr_next"}""" }
        assertEquals("ghu_next", assertIs<GitHubRefresh.Refreshed>(client.refresh(credentials, "ghr_old")).tokens.accessToken)
        assertEquals(listOf("refresh_token", "ghr_old"), requests.last().form().let { listOf(it["grant_type"], it["refresh_token"]) })
        reply = { HttpStatusCode.OK to """{"error":"bad_refresh_token"}""" }
        assertEquals(GitHubRefresh.Rejected, client.refresh(credentials, "ghr_old"))
        reply = { HttpStatusCode.OK to """{"error":"incorrect_client_credentials"}""" }
        assertEquals("github_app_misconfigured", assertFailsWith<TeamFailure> { client.refresh(credentials, "ghr_old") }.code, "A wrong secret does not end the member's link")
        reply = { HttpStatusCode.BadGateway to """{"echo":"ghr_old"}""" }
        val outage = assertFailsWith<GitHubApiException> { client.refresh(credentials, "ghr_old") }
        assertFalse(outage.message!!.contains("ghr_old"), "Response bodies never surface")
        reply = { HttpStatusCode.OK to "{\"padding\":\"" + "x".repeat(70_000) + "\"}" }
        assertFailsWith<GitHubApiException> { client.refresh(credentials, "ghr_old") }

        reply = { HttpStatusCode.OK to """{"id":42,"login":"octo"}""" }
        assertEquals(GitHubIdentity(42, "octo"), client.user("ghu_web"))
        assertEquals("Bearer ghu_web", requests.last().headers[HttpHeaders.Authorization])

        reply = { HttpStatusCode.NoContent to "" }
        client.revoke(credentials, "ghu_web")
        val revoke = requests.last()
        assertEquals(HttpMethod.Delete, revoke.method)
        assertEquals("https://api.github.test/applications/Iv23liSynthetic/token", revoke.url.toString())
        assertEquals("Basic " + Base64.getEncoder().encodeToString("Iv23liSynthetic:synthetic-client-secret-0123456789".toByteArray()), revoke.headers[HttpHeaders.Authorization])
        assertEquals("ghu_web", Json.parseToJsonElement((revoke.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject["access_token"]!!.jsonPrimitive.content)
        reply = { HttpStatusCode.NotFound to "" }
        client.revoke(credentials, "ghu_already_invalid")
    }
}
