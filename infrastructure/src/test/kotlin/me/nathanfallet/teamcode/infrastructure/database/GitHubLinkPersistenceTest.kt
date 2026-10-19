package me.nathanfallet.teamcode.infrastructure.database

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.cloud.GitHubAccess
import me.nathanfallet.teamcode.domain.github.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** Two independent connection pools stand in for two server replicas sharing one MySQL database. */
class GitHubLinkPersistenceTest {
    private val key = "-----BEGIN RSA PRIVATE KEY-----\nsynthetic-private-key\n-----END RSA PRIVATE KEY-----"
    private val secret = "synthetic-client-secret-0123456789"

    private class FakeGitHub : GitHubPort {
        val refreshes = AtomicInteger()
        @Volatile var refreshDelay = 0L
        @Volatile var refresh: (String) -> GitHubRefresh = { GitHubRefresh.Refreshed(GitHubTokens("ghu_refreshed_${refreshes.get()}", System.currentTimeMillis() + 8 * 3_600_000, "ghr_rotated_${refreshes.get()}", null)) }
        @Volatile var identity = GitHubIdentity(42, "octo")
        @Volatile var poll: GitHubDevicePoll = GitHubDevicePoll.Pending
        val revoked = mutableListOf<String>()
        override suspend fun verifyApp(appId: Long, clientId: String, privateKey: String) = GitHubAppMetadata(appId, clientId, "teamcode-test", "TeamCode test")
        override suspend fun startDevice(clientId: String) = GitHubDeviceAuthorization("synthetic-device-code", "ABCD-1234", "https://github.com/login/device", 900, 5)
        override suspend fun pollDevice(clientId: String, deviceCode: String) = poll
        override fun authorizationUrl(clientId: String, redirectUri: String, state: String, codeChallenge: String) = "https://github.com/login/oauth/authorize?state=$state"
        override suspend fun exchangeCode(credentials: GitHubAppCredentials, code: String, redirectUri: String, codeVerifier: String) = GitHubTokens("ghu_web", null, null, null)
        override suspend fun refresh(credentials: GitHubAppCredentials, refreshToken: String): GitHubRefresh {
            refreshes.incrementAndGet(); delay(refreshDelay)
            return refresh(refreshToken)
        }
        override suspend fun user(accessToken: String) = identity
        override suspend fun revoke(credentials: GitHubAppCredentials, accessToken: String) { synchronized(revoked) { revoked += accessToken } }
    }

    private class Replica(val database: Database, cipher: CredentialCipher, github: FakeGitHub) {
        val store = SqlGitHubStore(database, cipher)
        val service = GitHubLinkService(store, store, github)
        val access = ResolveGitHubAccessUseCase(store, store, github)
    }

    @Test fun linksAreEncryptedSerializedAndRevocable() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                coroutineScope { listOf(a, b).map { async(Dispatchers.IO) { it.migrate() } }.awaitAll() }
                a.query {
                    val drift = MigrationUtils.statementsRequiredForDatabaseMigration(GitHubApps, GitHubLinks, GitHubLinkAttempts, withLogs = false)
                    assertEquals(emptyList(), drift, "The reviewed migration matches the Exposed tables")
                }
                val cipher = CredentialCipher(ByteArray(32) { 17 })
                val github = FakeGitHub()
                val ra = Replica(a, cipher, github); val rb = Replica(b, cipher, github)
                val (admin, member, other) = members(a)

                // App configuration: administrators only, optimistic version, secrets encrypted at rest.
                assertEquals("forbidden", assertFailsWith<TeamFailure> { ra.service.saveApp(member, null, GitHubAppInput(123, "Iv23liSynthetic", secret, key)) }.code)
                val app = ra.service.saveApp(admin, null, GitHubAppInput(123, "Iv23liSynthetic", secret, key))
                assertEquals(1, app.version); assertEquals("teamcode-test", app.slug)
                assertEquals("github_app_conflict", assertFailsWith<TeamFailure> { rb.service.saveApp(admin, null, GitHubAppInput(123, "Iv23liSynthetic", secret, key)) }.code)
                assertEquals(2, rb.service.saveApp(admin, 1, GitHubAppInput(123, "Iv23liSynthetic", null, null)).version)
                val stored = a.query { GitHubApps.selectAll().single()[GitHubApps.credential] }
                assertFalse(stored.contains(secret) || stored.contains("synthetic-private-key"), "Client secret and private key are encrypted")
                assertEquals(secret, rb.store.credentials(admin.teamId)!!.clientSecret)
                assertEquals(key, rb.store.credentials(admin.teamId)!!.privateKey, "Omitted secrets are kept on update")

                // Device flow started on one replica, completed on the other.
                val device = ra.service.startDevice(member)
                val attemptSecret = a.query { GitHubLinkAttempts.selectAll().single()[GitHubLinkAttempts.secret] }
                assertFalse(attemptSecret.contains("synthetic-device-code"), "The device code is encrypted")
                a.query { GitHubLinkAttempts.update({ GitHubLinkAttempts.id eq device.id }) { it[nextPollAt] = 0 } }
                github.poll = GitHubDevicePoll.Authorized(GitHubTokens("ghu_device_token", System.currentTimeMillis() + 8 * 3_600_000, "ghr_device_token", System.currentTimeMillis() + 180L * 86_400_000))
                val linked = assertIs<GitHubDeviceLinkProgress.Linked>(rb.service.pollDevice(member, device.id))
                assertEquals("octo", linked.status.link!!.identity.login)
                assertTrue(a.query { GitHubLinkAttempts.selectAll().empty() }, "The attempt is consumed")
                val tokenRow = a.query { GitHubLinks.selectAll().single()[GitHubLinks.credential] }
                assertFalse(tokenRow.contains("ghu_device_token") || tokenRow.contains("ghr_device_token"), "User tokens are encrypted")

                // One GitHub account per team.
                val web = rb.service.startWeb(other, "https://teamcode.test/dashboard/github/callback")
                val state = Regex("state=([^&]+)").find(web.authorizationUrl)!!.groupValues[1]
                assertEquals("github_account_in_use", assertFailsWith<TeamFailure> {
                    ra.service.completeWeb(state, "code", null, web.browserNonce, "https://teamcode.test/dashboard/github/callback")
                }.code)
                assertEquals(listOf("ghu_web"), github.revoked, "The unstored token is revoked")
                assertNull(ra.store.link(other.teamId, other.member.id))

                // Only the member's own access: another member is not linked.
                assertEquals("ghu_device_token", assertIs<GitHubAccess.Linked>(rb.access.userAccess(member.teamId, member.member.id)).token.token)
                assertEquals(GitHubAccess.NotLinked, ra.access.userAccess(other.teamId, other.member.id))
                assertEquals(0, github.refreshes.get())

                // Near expiry, two replicas resolving at once spend the rotating refresh token exactly once.
                a.query { GitHubLinks.update({ GitHubLinks.memberId eq member.member.id }) { it[accessExpiresAt] = System.currentTimeMillis() + 2 * 60_000 } }
                github.refreshDelay = 600
                val results = coroutineScope { listOf(ra, rb, ra, rb).map { r -> async(Dispatchers.IO) { r.access.userAccess(member.teamId, member.member.id) } }.awaitAll() }
                assertEquals(1, github.refreshes.get(), "One refresh across replicas")
                assertEquals(setOf("ghu_refreshed_1"), results.map { assertIs<GitHubAccess.Linked>(it).token.token }.toSet())
                assertEquals(2, a.query { GitHubLinks.selectAll().single()[GitHubLinks.tokenVersion] })
                github.refreshDelay = 0
                // While a refresh is pending, a token still valid for a while is handed out instead of waited on.
                a.query { GitHubLinks.update({ GitHubLinks.memberId eq member.member.id }) {
                    it[accessExpiresAt] = System.currentTimeMillis() + 30 * 60_000; it[refreshPending] = true; it[refreshStartedAt] = System.currentTimeMillis()
                } }
                assertEquals("ghu_refreshed_1", assertIs<GitHubAccess.Linked>(ra.access.userAccess(member.teamId, member.member.id)).token.token)
                assertEquals(1, github.refreshes.get())

                // An owner that died mid-refresh is taken over after a minute, never before.
                a.query { GitHubLinks.update({ GitHubLinks.memberId eq member.member.id }) {
                    it[accessExpiresAt] = System.currentTimeMillis() - 1; it[refreshPending] = true; it[refreshStartedAt] = System.currentTimeMillis() - 5_000
                } }
                assertEquals(GitHubAccess.Unavailable, ResolveGitHubAccessUseCase(ra.store, ra.store, github, maxWaitMillis = 300).userAccess(member.teamId, member.member.id))
                assertEquals(1, github.refreshes.get())
                a.query { GitHubLinks.update({ GitHubLinks.memberId eq member.member.id }) { it[refreshStartedAt] = System.currentTimeMillis() - 120_000 } }
                assertEquals("ghu_refreshed_2", assertIs<GitHubAccess.Linked>(rb.access.userAccess(member.teamId, member.member.id)).token.token)

                // A refresh owner that was overtaken by a new link cannot overwrite it.
                a.query { GitHubLinks.update({ GitHubLinks.memberId eq member.member.id }) { it[accessExpiresAt] = System.currentTimeMillis() - 1 } }
                val claim = assertIs<GitHubTokenClaim.Refresh>(ra.store.claim(member.teamId, member.member.id, "Iv23liSynthetic", System.currentTimeMillis(), System.currentTimeMillis(), 60_000))
                ra.store.saveLink(member.teamId, member.member.id, member, GitHubIdentity(42, "octo"), "Iv23liSynthetic", GitHubTokens("ghu_relinked", null, null, null))
                assertFalse(rb.store.completeRefresh(member.teamId, member.member.id, claim, GitHubTokens("ghu_stale", null, "ghr_stale", null)))
                assertEquals("ghu_relinked", assertIs<GitHubAccess.Linked>(ra.access.userAccess(member.teamId, member.member.id)).token.token)

                // GitHub refusing the refresh token ends the link until the member links again.
                ra.store.saveLink(member.teamId, member.member.id, member, GitHubIdentity(42, "octo"), "Iv23liSynthetic", GitHubTokens("ghu_expired", System.currentTimeMillis() - 1, "ghr_revoked", null))
                github.refresh = { GitHubRefresh.Rejected }
                assertEquals(GitHubAccess.NotLinked, rb.access.userAccess(member.teamId, member.member.id))
                assertEquals(GitHubLinkState.RECONNECT_REQUIRED, ra.service.status(member).state)
                val refreshes = github.refreshes.get()
                assertEquals(GitHubAccess.NotLinked, ra.access.userAccess(member.teamId, member.member.id))
                assertEquals(refreshes, github.refreshes.get())

                // Unlink: the row and pending attempts are gone, the token is revoked, the journal records each step.
                ra.service.startDevice(member)
                rb.service.unlink(admin, member.member.id)
                assertNull(ra.store.link(member.teamId, member.member.id))
                assertTrue(a.query { GitHubLinkAttempts.selectAll().empty() })
                assertTrue("ghu_expired" in github.revoked)
                val actions = a.query { AuditEvents.select(AuditEvents.action).where { AuditEvents.action like "github.%" }.map { it[AuditEvents.action] } }
                assertEquals(listOf("github.app.saved", "github.app.saved", "github.link.created", "github.link.created", "github.link.created", "github.link.removed"), actions)
            } }
        }
    }

    private suspend fun members(a: Database): Triple<Principal, Principal, Principal> {
        val team = SqlTeamService(a)
        val password = team.bootstrap()!!
        team.changePassword(team.login("admin", password, "test").principal, password, "github admin replacement password")
        val admin = team.login("admin", "github admin replacement password", "test").principal
        fun member(name: String): suspend () -> Principal = {
            val created = team.createMember(admin, name, Role.MEMBER)
            team.changePassword(team.login(name, created.temporaryPassword, "test").principal, created.temporaryPassword, "$name replacement password")
            team.login(name, "$name replacement password", "test").principal
        }
        return Triple(admin, member("github-member")(), member("github-other")())
    }
}
