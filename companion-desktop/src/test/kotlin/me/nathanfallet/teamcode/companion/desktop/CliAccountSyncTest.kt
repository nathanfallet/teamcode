package me.nathanfallet.teamcode.companion.desktop

import com.russhwolf.settings.MapSettings
import java.io.File
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.client.TeamCodeException
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.LoginSession
import me.nathanfallet.teamcode.companion.domain.models.Member
import me.nathanfallet.teamcode.companion.domain.models.ServerCapabilities
import me.nathanfallet.teamcode.companion.domain.models.ServerInfo
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.infrastructure.credentials.MemoryCredentialStore
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsAccountRepository
import me.nathanfallet.teamcode.config.CliConfig
import me.nathanfallet.teamcode.config.ConfigStore
import me.nathanfallet.teamcode.config.JvmPrivateFiles
import kotlin.test.*

private class RecordingAuth : AuthRepository {
    val calls = mutableListOf<String>()
    override suspend fun discover(origin: String): ServerInfo = error("unused")
    override suspend fun capabilities(origin: String, token: String): ServerCapabilities = error("unused")
    override suspend fun capabilities(accountId: String): ServerCapabilities = error("unused")
    override suspend fun login(origin: String, username: String, password: String, deviceName: String, installationId: String): LoginSession = error("unused")
    override suspend fun changePassword(origin: String, token: String, current: String, replacement: String) = error("unused")
    override suspend fun me(accountId: String): Member = error("unused")
    override suspend fun logout(accountId: String) { calls += "logout $accountId" }
    override suspend fun renameCurrentDevice(accountId: String, name: String): String = error("unused")
    override suspend fun release(accountId: String) { calls += "release $accountId" }
    override fun abandon(origin: String, token: String) { calls += "abandon $origin $token" }
}

private class TestClock(var now: Long = 1_000) : Clock { override fun nowMillis() = now }

/** The CLI's account follows config.json: added, described again, signed out, removed, merged, linked. */
class CliAccountSyncTest {
    private class Fixture(directory: File) {
        val path = File(directory, "teamcode/config.json").path
        val cli = ConfigStore(path, JvmPrivateFiles)
        val accounts = SettingsAccountRepository(MapSettings())
        val file = CliConfigFile(path)
        val credentials = CliCredentialStore(MemoryCredentialStore(), file) { id -> accounts.accounts.value.firstOrNull { it.id == id }?.origin }
        val auth = RecordingAuth()
        val clock = TestClock()
        val described = mutableListOf<String>()
        var identity: (CliConfig) -> CliIdentity = { CliIdentity(if (it.accessToken.startsWith("grace")) "grace" else "ada", if (it.accessToken.startsWith("grace")) "m2" else "m1", "MEMBER", "1.0.0", "Ada's MacBook") }
        val sync = CliAccountSync(file, accounts, credentials, auth, clock, { described += it.accessToken; identity(it) }, "TeamCode macOS · mac", pollMillis = 10, retryMillis = 60_000)
        fun cliAccount() = accounts.accounts.value.firstOrNull { it.usedByCli }
        /** A new version of the file always differs in size here, so its stamp changes within the same millisecond. */
        fun login(origin: String, token: String) = cli.save(CliConfig(origin, token))
    }

    @Test fun aTerminalLoginAddsTheCliAccountAndKeepsTheActiveOne() = withTemporaryDirectory { directory -> runTest {
        val f = Fixture(directory)
        f.accounts.save(Account("a1", "https://other.example", "grace", "m9", "MEMBER", "Desk", 9_000_000, "1.0.0"))
        f.sync.sync()
        assertNull(f.cliAccount())
        f.login("https://team.example", "tc-1")
        f.sync.sync()
        val account = assertNotNull(f.cliAccount())
        assertEquals("ada", account.username); assertEquals("https://team.example", account.origin); assertEquals("Ada's MacBook", account.deviceName)
        assertEquals("a1", f.accounts.activeAccountId.value)
        // Unchanged file: nothing is read again from the server.
        f.sync.sync(); f.sync.sync()
        assertEquals(listOf("tc-1"), f.described)
    } }

    @Test fun theFirstAccountBecomesActive() = withTemporaryDirectory { directory -> runTest {
        val f = Fixture(directory)
        f.login("https://team.example", "tc-1")
        f.sync.sync()
        assertEquals(Account.CLI_ACCOUNT_ID, f.accounts.activeAccountId.value)
    } }

    @Test fun anotherLoginDescribesItAgainAndALogoutRemovesIt() = withTemporaryDirectory { directory -> runTest {
        val f = Fixture(directory)
        f.login("https://team.example", "tc-1")
        f.sync.sync()
        f.login("https://team.example", "grace-token-2")
        f.sync.sync()
        assertEquals("grace", f.cliAccount()?.username)
        File(f.path).delete()
        f.sync.sync()
        assertNull(f.cliAccount())
        assertTrue("release cli" in f.auth.calls)
    } }

    @Test fun aRevokedTokenSignsTheAccountOutAndAnOfflineServerIsAskedAgainLater() = withTemporaryDirectory { directory -> runTest {
        val f = Fixture(directory)
        f.login("https://team.example", "tc-1")
        f.sync.sync()
        f.identity = { throw TeamCodeException(401, "Unauthorized") }
        f.login("https://team.example", "tc-revoked")
        f.sync.sync()
        assertEquals(false, f.cliAccount()?.signedIn)

        f.identity = { throw java.io.IOException("offline") }
        f.login("https://team.example", "tc-offline-x")
        f.sync.sync()
        val tries = f.described.size
        f.sync.sync()
        assertEquals(tries, f.described.size, "not again before the retry delay")
        f.clock.now += 60_000
        f.identity = { CliIdentity("ada", "m1", "MEMBER", "1.0.0", null) }
        f.sync.sync()
        assertEquals(true, f.cliAccount()?.signedIn)
    } }

    @Test fun theSameMemberSignedInInTheAppIsMergedAndItsOwnSessionRevoked() = withTemporaryDirectory { directory -> runTest {
        val f = Fixture(directory)
        f.accounts.save(Account("a1", "https://team.example", "ada", "m1", "MEMBER", "Desk", 9_000_000, "1.0.0"))
        f.credentials.write("a1", "tc-app")
        f.login("https://team.example", "tc-cli")
        f.sync.sync()
        assertEquals(listOf(Account.CLI_ACCOUNT_ID), f.accounts.accounts.value.map { it.id })
        assertEquals(Account.CLI_ACCOUNT_ID, f.accounts.activeAccountId.value)
        assertNull(f.credentials.read("a1"))
        assertTrue("abandon https://team.example tc-app" in f.auth.calls)
    } }

    @Test fun linkingWritesTheAppsSessionForTheCli() = withTemporaryDirectory { directory -> runTest {
        val f = Fixture(directory)
        assertTrue(f.sync.canLink())
        f.accounts.save(Account("a1", "https://team.example", "ada", "m1", "MEMBER", "Desk", 9_000_000, "1.0.0"))
        f.credentials.write("a1", "tc-app")
        f.sync.link("a1")
        assertEquals("tc-app", f.cli.load()?.accessToken)
        assertEquals(listOf(Account.CLI_ACCOUNT_ID), f.accounts.accounts.value.map { it.id })
        assertEquals("tc-app", f.credentials.read(Account.CLI_ACCOUNT_ID))
        assertFalse(f.sync.canLink())
        // Its session is the CLI's now: never revoked, and the file is not described again.
        f.sync.sync()
        assertTrue(f.auth.calls.none { it.startsWith("abandon") })
        assertTrue(f.described.isEmpty())
    } }
}
