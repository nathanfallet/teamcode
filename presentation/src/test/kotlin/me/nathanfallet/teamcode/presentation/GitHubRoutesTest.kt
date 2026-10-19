package me.nathanfallet.teamcode.presentation

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.emptyFlow
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.github.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

/** Transport contract of the GitHub link routes over in-memory ports; MySQL behavior is covered in infrastructure. */
class GitHubRoutesTest {
    private val admin = Member("admin-id", "admin", Role.ADMIN, false, false)
    private val colleague = Member("colleague-id", "colleague", Role.MEMBER, false, false)
    private val secret = "synthetic-client-secret-0123456789"
    private val key = "-----BEGIN RSA PRIVATE KEY-----\nsynthetic\n-----END RSA PRIVATE KEY-----"

    private class Store : GitHubAppStore, GitHubLinkStore {
        var clock = 1_000_000L
        var app: GitHubApp? = null
        var credentials: GitHubAppCredentials? = null
        val attempts = mutableMapOf<String, GitHubLinkAttempt>()
        val links = mutableMapOf<String, Pair<GitHubLink, String>>()
        override suspend fun now() = clock
        override suspend fun app(teamId: String) = app
        override suspend fun saveApp(actor: Principal, expectedVersion: Int?, input: GitHubAppInput, metadata: GitHubAppMetadata): GitHubApp {
            credentials = GitHubAppCredentials(input.appId, input.clientId, input.clientSecret ?: credentials!!.clientSecret, input.privateKey ?: credentials!!.privateKey)
            return GitHubApp(input.appId, input.clientId, metadata.slug, metadata.name, (expectedVersion ?: 0) + 1, clock).also { app = it }
        }
        override suspend fun credentials(teamId: String) = credentials
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun member(teamId: String, memberId: String) = listOf("admin-id", "colleague-id").find { it == memberId }?.let { Member(it, it, Role.MEMBER, false, false) }
        override suspend fun startAttempt(actor: Principal, attempt: GitHubLinkAttempt) { attempts[attempt.id] = attempt }
        override suspend fun attempt(id: String) = attempts[id]
        override suspend fun consumeAttempt(id: String) = attempts.remove(id) != null
        override suspend fun deferPoll(id: String, intervalSeconds: Int, nextPollAt: Long) = Unit
        override suspend fun saveLink(teamId: String, memberId: String, actor: Principal?, identity: GitHubIdentity, clientId: String, tokens: GitHubTokens) =
            GitHubLink(memberId, identity, clientId, GitHubLinkState.LINKED, tokens.accessExpiresAt, null, clock, clock).also { links[memberId] = it to tokens.accessToken }
        override suspend fun link(teamId: String, memberId: String) = links[memberId]?.first
        override suspend fun links(teamId: String) = links.values.map { it.first }
        override suspend fun removeLink(teamId: String, memberId: String, actor: Principal) = links.remove(memberId)?.second
        override suspend fun claim(teamId: String, memberId: String, clientId: String, validUntil: Long, staleAfterMillis: Long) = GitHubTokenClaim.NotLinked
        override suspend fun completeRefresh(teamId: String, memberId: String, claim: GitHubTokenClaim.Refresh, tokens: GitHubTokens) = false
        override suspend fun abandonRefresh(teamId: String, memberId: String, claim: GitHubTokenClaim.Refresh, reconnect: Boolean) = Unit
    }
    private class FakeGitHub : GitHubPort {
        val revoked = mutableListOf<String>()
        override suspend fun verifyApp(appId: Long, clientId: String, privateKey: String) = GitHubAppMetadata(appId, clientId, "teamcode-test", "TeamCode test")
        override suspend fun startDevice(clientId: String) = GitHubDeviceAuthorization("synthetic-device-code", "ABCD-1234", "https://github.com/login/device", 900, 5)
        override suspend fun pollDevice(clientId: String, deviceCode: String) = GitHubDevicePoll.Authorized(GitHubTokens("ghu_synthetic_device", null, null, null))
        override fun authorizationUrl(clientId: String, redirectUri: String, state: String, codeChallenge: String) =
            "https://github.com/login/oauth/authorize?client_id=$clientId&state=$state&redirect_uri=${redirectUri.encodeURLParameter()}"
        override suspend fun exchangeCode(credentials: GitHubAppCredentials, code: String, redirectUri: String, codeVerifier: String) = GitHubTokens("ghu_synthetic_web", null, null, null)
        override suspend fun refresh(credentials: GitHubAppCredentials, refreshToken: String) = GitHubRefresh.Rejected
        override suspend fun user(accessToken: String) = GitHubIdentity(42, "octo")
        override suspend fun revoke(credentials: GitHubAppCredentials, accessToken: String) { revoked += accessToken }
    }
    private fun team(): TeamService = object : TeamService by FakeTeam() {
        override suspend fun authenticate(token: String): Principal? = when (token) {
            "admin" -> Principal(admin, "admin-session", "default")
            "colleague" -> Principal(colleague, "colleague-session", "default")
            "service-colleague" -> Principal(colleague, "colleague-key", "default", ServiceScope.entries.toSet())
            else -> null
        }
        override suspend fun members(principal: Principal): List<Member> { principal.requireAdmin(); return listOf(admin, colleague) }
    }
    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) =
            receive(GenerationHead(200, "text/event-stream"), emptyFlow())
    }

    @Test fun administratorsConfigureTheAppAndMembersLinkThroughTheDeviceFlow() = testApplication {
        val store = Store(); val github = FakeGitHub()
        application { configureGateway(gateway, team = team(), github = GitHubLinkService(store, store, github), publicOrigin = "https://teamcode.test") }
        assertTrue("github.link.v1" in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("colleague") }.bodyAsText()).features)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/github/link") { bearerAuth("service-colleague") }.status, "Service keys never reach GitHub routes")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/github/app") { bearerAuth("colleague") }.status)
        val body = Serialization.json.encodeToString(SaveGitHubAppRequest(123, "Iv23liSynthetic", secret, key))
        assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/github/app") { bearerAuth("colleague"); contentType(ContentType.Application.Json); setBody(body) }.status)
        val saved = client.put("/api/v1/github/app") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.OK, saved.status)
        val text = saved.bodyAsText()
        assertFalse(text.contains(secret) || text.contains("synthetic\n") || text.contains("PRIVATE KEY"), "No secret is ever returned")
        val view = Serialization.json.decodeFromString<GitHubAppView>(text)
        assertEquals(GitHubAppView(true, 123, "Iv23liSynthetic", "teamcode-test", "TeamCode test", 1, 1_000_000, "https://teamcode.test/dashboard/github/callback"), view)
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/github/app") {
            bearerAuth("admin"); contentType(ContentType.Application.Json); setBody(Serialization.json.encodeToString(SaveGitHubAppRequest(123, "Iv23liOtherApp", version = 1)))
        }.status, "Another App needs its own client secret")

        val sdk = TeamCodeClient("http://localhost", { "colleague" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        try {
            assertEquals(GitHubLinkStates.NOT_LINKED, sdk.githubLink().state)
            val device = sdk.startGitHubDeviceLink()
            assertEquals("ABCD-1234", device.userCode); assertEquals("https://github.com/login/device", device.verificationUri)
            assertEquals("pending", sdk.pollGitHubDeviceLink(device.id).status, "Polled before the interval: GitHub is not asked")
            store.clock += 5_000
            val raw = client.post("/api/v1/github/link/device/${device.id}") { bearerAuth("colleague") }.bodyAsText()
            assertFalse(raw.contains("ghu_"), "Tokens never leave the server through the API")
            val poll = Serialization.json.decodeFromString<GitHubDeviceLinkPollView>(raw)
            assertEquals("linked", poll.status); assertEquals("octo", poll.link?.login)
            assertEquals(GitHubLinkStates.LINKED, sdk.githubLink().state)
            assertEquals(403, assertFailsWith<TeamCodeException> { sdk.githubLinks() }.statusCode)
            assertEquals(403, assertFailsWith<TeamCodeException> { sdk.unlinkGitHub("admin-id") }.statusCode)
            sdk.unlinkGitHub()
            assertEquals(listOf("ghu_synthetic_device"), github.revoked)
            assertEquals(GitHubLinkStates.NOT_LINKED, sdk.githubLink().state)
        } finally { sdk.close() }
    }

    @Test fun dashboardWebFlowIsCsrfProtectedAndBoundToTheBrowser() = testApplication {
        val store = Store(); val github = FakeGitHub(); val service = GitHubLinkService(store, store, github)
        application { configureGateway(gateway, team = team(), github = service, publicOrigin = "http://localhost") }
        kotlinx.coroutines.runBlocking { service.saveApp(Principal(admin, "admin-session", "default"), null, GitHubAppInput(123, "Iv23liSynthetic", secret, key)) }
        val page = client.get("/dashboard/github") { header(HttpHeaders.Cookie, "teamcode_session=colleague") }.bodyAsText()
        assertTrue(page.contains("Link GitHub")); assertFalse(page.contains("Team GitHub App"), "Members do not see the App configuration")
        val adminPage = client.get("/dashboard/github") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertTrue(adminPage.contains("Team GitHub App") && adminPage.contains("http://localhost/dashboard/github/callback") && adminPage.contains("colleague"))
        assertFalse(adminPage.contains(secret))

        assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/github/link") {
            header(HttpHeaders.Cookie, "teamcode_session=colleague"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=wrong")
        }.status)
        val start = client.post("/dashboard/github/link") {
            header(HttpHeaders.Cookie, "teamcode_session=colleague"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=colleague-session")
        }
        assertEquals(HttpStatusCode.OK, start.status)
        val cookie = start.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("teamcode_github_link=") }
        assertTrue(cookie.contains("Path=/dashboard/github/callback") && cookie.contains("SameSite=Lax") && cookie.contains("HttpOnly"))
        val nonce = cookie.substringAfter("=").substringBefore(";")
        val authorize = Regex("href=\"([^\"]+)\"").findAll(start.bodyAsText()).map { it.groupValues[1].replace("&amp;", "&") }.single { it.startsWith("https://github.com/") }
        val state = Url(authorize).parameters["state"]!!

        // GitHub's redirect: no session cookie, only the browser cookie.
        val stranger = client.get("/dashboard/github/callback?state=$state&code=code-1")
        assertEquals(HttpStatusCode.Forbidden, stranger.status, "Another browser cannot complete someone's link")
        val done = client.get("/dashboard/github/callback?state=$state&code=code-1") { header(HttpHeaders.Cookie, "teamcode_github_link=$nonce") }
        assertEquals(HttpStatusCode.OK, done.status)
        assertTrue(done.bodyAsText().contains("Linked as <strong>octo</strong>"))
        assertEquals("ghu_synthetic_web", store.links.getValue("colleague-id").second)
        assertEquals(HttpStatusCode.NotFound, client.get("/dashboard/github/callback?state=$state&code=code-1") { header(HttpHeaders.Cookie, "teamcode_github_link=$nonce") }.status, "Single use")

        assertEquals(HttpStatusCode.Found, client.post("/dashboard/github/links/colleague-id/unlink") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=admin-session")
        }.status)
        assertTrue(store.links.isEmpty())
        assertEquals(listOf("ghu_synthetic_web"), github.revoked)
    }
}
