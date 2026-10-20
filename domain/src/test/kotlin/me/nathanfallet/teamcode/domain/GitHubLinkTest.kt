package me.nathanfallet.teamcode.domain

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.cloud.GitHubAccess
import me.nathanfallet.teamcode.domain.github.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

/** Link, refresh and revocation rules over in-memory ports; MySQL serialization is covered in infrastructure. */
class GitHubLinkTest {
    private val admin = Principal(Member("admin-id", "admin", Role.ADMIN, false, false), "admin-session", "team")
    private val member = Principal(Member("member-id", "member", Role.MEMBER, false, false), "member-session", "team")
    private val other = Principal(Member("other-id", "other", Role.MEMBER, false, false), "other-session", "team")
    private val key = "-----BEGIN RSA PRIVATE KEY-----\nsynthetic\n-----END RSA PRIVATE KEY-----"
    private val input = GitHubAppInput(123, "Iv23liSynthetic", "synthetic-client-secret-0123456789", key)

    private class Apps : GitHubAppStore {
        var clock = 1_000_000L
        var app: GitHubApp? = null
        var credentials: GitHubAppCredentials? = null
        override suspend fun now() = clock
        override suspend fun app(teamId: String) = app
        override suspend fun saveApp(actor: Principal, expectedVersion: Int?, input: GitHubAppInput, metadata: GitHubAppMetadata): GitHubApp {
            val previous = credentials
            credentials = GitHubAppCredentials(input.appId, input.clientId, input.clientSecret ?: previous!!.clientSecret, input.privateKey ?: previous!!.privateKey)
            return GitHubApp(input.appId, input.clientId, metadata.slug, metadata.name, (expectedVersion ?: 0) + 1, clock).also { app = it }
        }
        override suspend fun credentials(teamId: String) = credentials
    }

    private class Links(val apps: Apps) : GitHubLinkStore {
        val attempts = mutableMapOf<String, GitHubLinkAttempt>()
        val links = mutableMapOf<String, Pair<GitHubLink, GitHubTokens>>()
        var pending: Long? = null
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun member(teamId: String, memberId: String) = listOf("admin-id", "member-id", "other-id").find { it == memberId }?.let { Member(it, it, Role.MEMBER, false, false) }
        override suspend fun startAttempt(actor: Principal, attempt: GitHubLinkAttempt) {
            attempts.values.removeIf { it.memberId == attempt.memberId }
            attempts[attempt.id] = attempt
        }
        override suspend fun attempt(id: String) = attempts[id]
        override suspend fun consumeAttempt(id: String) = attempts.remove(id) != null
        override suspend fun deferPoll(id: String, expectedNextPollAt: Long?, intervalSeconds: Int, nextPollAt: Long): Boolean {
            val a = attempts[id] ?: return false
            if (expectedNextPollAt != null && a.nextPollAt != expectedNextPollAt) return false
            attempts[id] = GitHubLinkAttempt(a.id, a.teamId, a.memberId, a.kind, a.secret, a.browserHash, intervalSeconds, nextPollAt, a.expiresAt)
            return true
        }
        override suspend fun saveLink(teamId: String, memberId: String, actor: Principal?, identity: GitHubIdentity, clientId: String, tokens: GitHubTokens): GitHubLink {
            if (links.values.any { it.first.identity.userId == identity.userId && it.first.memberId != memberId })
                throw TeamFailure("github_account_in_use", 409, "in use")
            val link = GitHubLink(memberId, identity, clientId, GitHubLinkState.LINKED, tokens.accessExpiresAt, tokens.refreshExpiresAt, apps.clock, apps.clock)
            links[memberId] = link to tokens
            return link
        }
        override suspend fun link(teamId: String, memberId: String) = links[memberId]?.first
        override suspend fun links(teamId: String) = links.values.map { it.first }
        override suspend fun removeLink(teamId: String, memberId: String, actor: Principal) = links.remove(memberId)?.let { GitHubRevocable(it.first.clientId, it.second.accessToken) }
        override suspend fun removeLinksOfOtherApps(actor: Principal, clientId: String): List<GitHubRevocable> {
            val stale = links.filterValues { it.first.clientId != clientId }
            stale.keys.forEach(links::remove)
            return stale.values.map { GitHubRevocable(it.first.clientId, it.second.accessToken) }
        }
        override suspend fun claim(teamId: String, memberId: String, clientId: String, validUntil: Long, usableUntil: Long, staleAfterMillis: Long): GitHubTokenClaim {
            val (link, tokens) = links[memberId] ?: return GitHubTokenClaim.NotLinked
            if (link.state != GitHubLinkState.LINKED || link.clientId != clientId) return GitHubTokenClaim.ReconnectRequired
            pending?.let { if (it + staleAfterMillis > apps.clock) return if ((link.accessExpiresAt ?: Long.MAX_VALUE) > usableUntil) GitHubTokenClaim.Ready(link, tokens.accessToken) else GitHubTokenClaim.Wait }
            if ((link.accessExpiresAt ?: Long.MAX_VALUE) > validUntil) return GitHubTokenClaim.Ready(link, tokens.accessToken)
            pending = apps.clock
            return GitHubTokenClaim.Refresh(link, tokens.accessToken, tokens.refreshToken!!, 1, apps.clock)
        }
        override suspend fun completeRefresh(teamId: String, memberId: String, claim: GitHubTokenClaim.Refresh, tokens: GitHubTokens): Boolean {
            pending = null
            val link = links.getValue(memberId).first
            links[memberId] = link.copy(accessExpiresAt = tokens.accessExpiresAt) to tokens
            return true
        }
        override suspend fun abandonRefresh(teamId: String, memberId: String, claim: GitHubTokenClaim.Refresh, reconnect: Boolean) {
            pending = null
            if (reconnect) links[memberId] = links.getValue(memberId).let { it.first.copy(state = GitHubLinkState.RECONNECT_REQUIRED) to it.second }
        }
    }

    private class FakeGitHub : GitHubPort {
        val calls = mutableListOf<String>()
        var verify: (Long, String, String) -> GitHubAppMetadata = { id, client, _ -> GitHubAppMetadata(id, client, "teamcode-test", "TeamCode test") }
        var poll: GitHubDevicePoll = GitHubDevicePoll.Pending
        var pollFailure: Exception? = null
        var refresh: () -> GitHubRefresh = { GitHubRefresh.Refreshed(GitHubTokens("ghu_refreshed", 9_000_000_000, "ghr_next", null)) }
        var identity = GitHubIdentity(42, "octo")
        val revoked = mutableListOf<String>()
        var exchanged: Triple<String, String, String>? = null
        override suspend fun verifyApp(appId: Long, clientId: String, privateKey: String) = also { calls += "verify:$privateKey" }.verify(appId, clientId, privateKey)
        override suspend fun startDevice(clientId: String) = GitHubDeviceAuthorization("device-code-secret", "ABCD-1234", "https://github.com/login/device", 900, 5).also { calls += "device" }
        override suspend fun pollDevice(clientId: String, deviceCode: String): GitHubDevicePoll { calls += "poll:$deviceCode"; pollFailure?.let { throw it }; return poll }
        override fun authorizationUrl(clientId: String, redirectUri: String, state: String, codeChallenge: String) = "https://github.com/login/oauth/authorize?state=$state&code_challenge=$codeChallenge"
        override suspend fun exchangeCode(credentials: GitHubAppCredentials, code: String, redirectUri: String, codeVerifier: String): GitHubTokens {
            exchanged = Triple(code, redirectUri, codeVerifier)
            return GitHubTokens("ghu_web", 9_000_000_000, "ghr_web", null)
        }
        override suspend fun refresh(credentials: GitHubAppCredentials, refreshToken: String) = also { calls += "refresh:$refreshToken" }.refresh()
        override suspend fun user(accessToken: String) = identity
        override suspend fun revoke(credentials: GitHubAppCredentials, accessToken: String) { revoked += accessToken }
    }

    private fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { runBlocking { block() } }.code)

    @Test fun onlyAdministratorsConfigureAVerifiedGitHubApp() = runBlocking {
        val apps = Apps(); val github = FakeGitHub(); val service = GitHubLinkService(apps, Links(apps), github)
        failure("forbidden") { service.saveApp(member, null, input) }
        failure("credential_scope") { service.saveApp(admin.copy(serviceScopes = emptySet()), null, input) }
        failure("invalid_github_app") { service.saveApp(admin, null, GitHubAppInput(123, "Ov23liOAuthApp", input.clientSecret, key)) }
        failure("invalid_github_app") { service.saveApp(admin, null, GitHubAppInput(123, input.clientId, null, key)) }
        failure("invalid_github_app") { service.saveApp(admin, null, GitHubAppInput(123, input.clientId, input.clientSecret, "not a key")) }
        github.verify = { _, _, _ -> throw TeamFailure("github_app_mismatch", 422, "mismatch") }
        failure("github_app_mismatch") { service.saveApp(admin, null, input) }
        github.verify = { _, _, _ -> throw java.io.IOException("connection reset, body: secret") }
        val outage = assertFailsWith<TeamFailure> { service.saveApp(admin, null, input) }
        assertEquals("github_unreachable", outage.code); assertFalse(outage.message!!.contains("secret"))
        github.verify = { id, client, _ -> GitHubAppMetadata(id, client, "teamcode-test", null) }
        val saved = service.saveApp(admin, null, input)
        assertEquals("teamcode-test", saved.slug)
        // An update without the secrets keeps them and verifies with the stored key.
        github.calls.clear()
        service.saveApp(admin, 1, GitHubAppInput(123, input.clientId, null, null))
        assertEquals(listOf("verify:$key"), github.calls)
        assertEquals(input.clientSecret, apps.credentials!!.clientSecret)
        failure("invalid_github_app") { service.saveApp(admin, 2, GitHubAppInput(456, "Iv23liAnotherApp", null, null)) }
        // Replacing the App removes the links it can no longer refresh and revokes them with the previous App.
        val links = Links(apps)
        val replacing = GitHubLinkService(apps, links, github)
        links.saveLink("team", "member-id", member, GitHubIdentity(42, "octo"), input.clientId, GitHubTokens("ghu_old_app", null, null, null))
        replacing.saveApp(admin, 2, GitHubAppInput(456, "Iv23liAnotherApp", "another-client-secret-0123456789", key))
        assertTrue(links.links.isEmpty()); assertEquals(listOf("ghu_old_app"), github.revoked)
        assertFalse(input.toString().contains("synthetic-client-secret") || apps.credentials.toString().contains("synthetic-client-secret"))
    }

    @Test fun deviceFlowPollsGitHubAtMostOncePerIntervalAndLinksTheMember() = runBlocking {
        val apps = Apps(); val links = Links(apps); val github = FakeGitHub(); val service = GitHubLinkService(apps, links, github)
        failure("github_app_not_configured") { service.startDevice(member) }
        service.saveApp(admin, null, input)
        val device = service.startDevice(member)
        assertEquals("ABCD-1234", device.userCode); assertEquals(5, device.intervalSeconds)
        assertEquals("device-code-secret", links.attempts.getValue(device.id).secret)
        failure("github_link_not_found") { service.pollDevice(other, device.id) }
        assertEquals(GitHubDeviceLinkProgress.Pending(5), service.pollDevice(member, device.id))
        assertFalse(github.calls.any { it.startsWith("poll") }, "No GitHub call before the interval")
        apps.clock += 5_000
        assertEquals(GitHubDeviceLinkProgress.Pending(5), service.pollDevice(member, device.id))
        assertEquals(1, github.calls.count { it == "poll:device-code-secret" })
        assertEquals(GitHubDeviceLinkProgress.Pending(5), service.pollDevice(member, device.id), "A fast client is held back")
        assertEquals(1, github.calls.count { it.startsWith("poll") })
        apps.clock += 5_000; github.pollFailure = java.io.IOException("GitHub unreachable")
        failure("github_unreachable") { service.pollDevice(member, device.id) }
        assertEquals(GitHubDeviceLinkProgress.Pending(5), service.pollDevice(member, device.id))
        github.pollFailure = null
        assertEquals(2, github.calls.count { it.startsWith("poll") }, "A GitHub error still uses up the interval")
        apps.clock += 5_000; github.poll = GitHubDevicePoll.SlowDown(10)
        assertEquals(GitHubDeviceLinkProgress.Pending(10), service.pollDevice(member, device.id))
        apps.clock += 10_000; github.poll = GitHubDevicePoll.Authorized(GitHubTokens("ghu_device", 9_000_000_000, "ghr_device", null))
        val linked = assertIs<GitHubDeviceLinkProgress.Linked>(service.pollDevice(member, device.id))
        assertEquals("octo", linked.status.link!!.identity.login); assertEquals(GitHubLinkState.LINKED, linked.status.state)
        assertTrue(links.attempts.isEmpty(), "The attempt is single-use")
        failure("github_link_not_found") { service.pollDevice(member, device.id) }
        assertEquals(GitHubLinkState.LINKED, service.status(member).state)

        // The same GitHub account cannot be linked to a second member; its fresh token is revoked, nothing else.
        val second = service.startDevice(other)
        apps.clock += 5_000; github.poll = GitHubDevicePoll.Authorized(GitHubTokens("ghu_duplicate", null, null, null))
        failure("github_account_in_use") { service.pollDevice(other, second.id) }
        assertEquals(listOf("ghu_duplicate"), github.revoked)
        assertEquals("ghu_device", links.links.getValue("member-id").second.accessToken)

        val third = service.startDevice(other)
        apps.clock += 15 * 60_000
        assertEquals(GitHubDeviceLinkProgress.Expired, service.pollDevice(other, third.id))
        val fourth = service.startDevice(other)
        apps.clock += 5_000; github.poll = GitHubDevicePoll.Denied
        assertEquals(GitHubDeviceLinkProgress.Denied, service.pollDevice(other, fourth.id))
    }

    @Test fun webCallbackIsSingleUseAndBoundToTheStartingBrowser() = runBlocking {
        val apps = Apps(); val links = Links(apps); val github = FakeGitHub(); val service = GitHubLinkService(apps, links, github)
        service.saveApp(admin, null, input)
        val redirect = "https://teamcode.example/dashboard/github/callback"
        val web = service.startWeb(member, redirect)
        val state = Regex("state=([^&]+)").find(web.authorizationUrl)!!.groupValues[1]
        val challenge = Regex("code_challenge=([^&]+)").find(web.authorizationUrl)!!.groupValues[1]
        assertFalse(web.toString().contains(web.browserNonce))
        failure("github_link_not_found") { service.completeWeb("x".repeat(43), "code", null, web.browserNonce, redirect) }
        failure("github_link_browser_mismatch") { service.completeWeb(state, "code", null, null, redirect) }
        failure("github_link_browser_mismatch") { service.completeWeb(state, "code", null, "y".repeat(43), redirect) }
        val status = service.completeWeb(state, "code-1", null, web.browserNonce, redirect)
        assertEquals("octo", status.link!!.identity.login)
        val (code, uri, verifier) = github.exchanged!!
        assertEquals("code-1", code); assertEquals(redirect, uri)
        assertEquals(challenge, GitHubLinkService.challenge(verifier), "The PKCE verifier matches the challenge sent to GitHub")
        failure("github_link_not_found") { service.completeWeb(state, "code-1", null, web.browserNonce, redirect) }

        val late = service.startWeb(member, redirect)
        val lateState = Regex("state=([^&]+)").find(late.authorizationUrl)!!.groupValues[1]
        apps.clock += GitHubLinkService.WEB_ATTEMPT_MILLIS
        failure("github_link_not_found") { service.completeWeb(lateState, "code-2", null, late.browserNonce, redirect) }
        val cancelled = service.startWeb(member, redirect)
        failure("github_link_denied") { service.completeWeb(Regex("state=([^&]+)").find(cancelled.authorizationUrl)!!.groupValues[1], null, "access_denied", cancelled.browserNonce, redirect) }
    }

    @Test fun membersUnlinkThemselvesAndAdministratorsUnlinkAnyone() = runBlocking {
        val apps = Apps(); val links = Links(apps); val github = FakeGitHub(); val service = GitHubLinkService(apps, links, github)
        service.saveApp(admin, null, input)
        links.saveLink("team", "member-id", member, GitHubIdentity(42, "octo"), input.clientId, GitHubTokens("ghu_a", null, null, null))
        links.saveLink("team", "other-id", other, GitHubIdentity(43, "cat"), input.clientId, GitHubTokens("ghu_b", null, null, null))
        failure("forbidden") { service.status(member, "other-id") }
        failure("forbidden") { service.unlink(member, "other-id") }
        failure("member_not_found") { service.unlink(admin, "missing-id") }
        assertEquals(GitHubLinkState.LINKED, service.status(admin, "other-id").state)
        service.unlink(member); service.unlink(member)
        service.unlink(admin, "other-id")
        assertEquals(listOf("ghu_a", "ghu_b"), github.revoked)
        assertNull(service.status(member).state)
        assertTrue(service.links(admin).isEmpty())
        // A link made with another App can no longer be refreshed: it shows as reconnect_required.
        links.saveLink("team", "member-id", member, GitHubIdentity(42, "octo"), "Iv23liPreviousApp", GitHubTokens("ghu_c", null, null, null))
        assertEquals(GitHubLinkState.RECONNECT_REQUIRED, service.status(member).state)
    }

    @Test fun accessIsRefreshedBeforeExpiryAndRefusedRefreshesRequireANewLink() = runBlocking {
        val apps = Apps(); val links = Links(apps); val github = FakeGitHub()
        val service = GitHubLinkService(apps, links, github)
        val access = ResolveGitHubAccessUseCase(apps, links, github, maxWaitMillis = 300)
        assertEquals(GitHubAccess.NotLinked, access.userAccess("team", "member-id"), "No App configured")
        service.saveApp(admin, null, input)
        assertEquals(GitHubAccess.NotLinked, access.userAccess("team", "member-id"))
        links.saveLink("team", "member-id", member, GitHubIdentity(42, "octo"), input.clientId, GitHubTokens("ghu_valid", apps.clock + 8 * 3_600_000, "ghr_first", null))
        val linked = assertIs<GitHubAccess.Linked>(access.userAccess("team", "member-id"))
        assertEquals("ghu_valid", linked.token.token); assertEquals("octo", linked.token.login); assertEquals(42, linked.token.userId)
        assertFalse(github.calls.any { it.startsWith("refresh") })

        apps.clock += 7 * 3_600_000 + 1
        assertEquals("ghu_refreshed", assertIs<GitHubAccess.Linked>(access.userAccess("team", "member-id")).token.token)
        assertEquals(listOf("refresh:ghr_first"), github.calls.filter { it.startsWith("refresh") })

        // GitHub unreachable: the old token while it lasts, then unavailable; the link and its tokens are kept.
        links.links["member-id"] = links.links.getValue("member-id").let { it.first.copy(accessExpiresAt = apps.clock + 30 * 60_000) to GitHubTokens("ghu_old", apps.clock + 30 * 60_000, "ghr_keep", null) }
        github.refresh = { throw java.io.IOException("unreachable") }
        assertEquals("ghu_old", assertIs<GitHubAccess.Linked>(access.userAccess("team", "member-id")).token.token)
        apps.clock += 29 * 60_000
        assertEquals(GitHubAccess.Unavailable, access.userAccess("team", "member-id"))
        assertEquals(GitHubLinkState.LINKED, links.links.getValue("member-id").first.state)

        // Another replica is refreshing: wait, then report unavailable rather than spend the same refresh token.
        links.pending = apps.clock
        assertEquals(GitHubAccess.Unavailable, access.userAccess("team", "member-id"))
        links.links["member-id"] = links.links.getValue("member-id").let { it.first.copy(accessExpiresAt = apps.clock + 30 * 60_000) to it.second }
        assertEquals("ghu_old", assertIs<GitHubAccess.Linked>(access.userAccess("team", "member-id")).token.token, "A token valid for a while is not waited on")
        links.links["member-id"] = links.links.getValue("member-id").let { it.first.copy(accessExpiresAt = apps.clock + 60_000) to it.second }
        links.pending = null

        github.refresh = { GitHubRefresh.Rejected }
        assertEquals(GitHubAccess.NotLinked, access.userAccess("team", "member-id"))
        assertEquals(GitHubLinkState.RECONNECT_REQUIRED, service.status(member).state)
        val calls = github.calls.size
        assertEquals(GitHubAccess.NotLinked, access.userAccess("team", "member-id"))
        assertEquals(calls, github.calls.size, "No further refresh once GitHub refused it")
    }
}
