package me.nathanfallet.teamcode.companion.desktop

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.teamcode.companion.di.appModules
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.services.LocalAvailability
import me.nathanfallet.teamcode.companion.domain.services.LocalSessionRequest
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.StartLocalSessionUseCase
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import org.koin.dsl.koinApplication
import kotlin.test.*

/**
 * The desktop's wiring against a real server and the real native CLI, with a stand-in agent: run by
 * `scripts/smoke-desktop.py`, which starts both and signs the CLI in; skipped otherwise. The CLI's sign-in appears as
 * the CLI account, "On this device" starts a session whose first message is answered, a pause from the app stops its
 * host, and `teamcode logout` in a terminal removes the account. The app starts no host after that: waking a session is
 * its device's daemon's work (`DeviceDaemonTest`, `DeviceDaemonPersistenceTest`).
 */
class DesktopSmokeTest {
    @Test fun theAppDrivesSessionsOnThisDeviceThroughTheCli() = runBlocking {
        val project = System.getenv("TEAMCODE_DESKTOP_SMOKE_PROJECT") ?: return@runBlocking
        val environment = DesktopEnvironment(System.getenv())
        val koin = koinApplication { modules(appModules(desktopModule(environment)) + inMemorySettings) }.koin
        try {
            val accounts = koin.get<AccountRepository>()
            val sync = koin.get<CliAccountSync>()
            sync.sync()
            val account = assertNotNull(accounts.accounts.value.firstOrNull { it.usedByCli }, "the CLI's sign-in is the CLI account")
            assertEquals("admin", account.username)
            val start = koin.get<StartLocalSessionUseCase>()
            assertEquals(LocalAvailability.Ready, start.availability(Account.CLI_ACCOUNT_ID))

            val started = start(Account.CLI_ACCOUNT_ID, LocalSessionRequest(project, "claude", File(project).name, "", "", false), "hello")
            val client = koin.get<TeamCodeClientProvider>().forAccount(Account.CLI_ACCOUNT_ID, account.origin)
            suspend fun texts() = client.cloudEvents(started.sessionId, after = 0, limit = 100).events
                .filter { it.type == "message.agent" }.mapNotNull { it.data["text"]?.jsonPrimitive?.content }
            suspend fun until(what: String, condition: suspend () -> Boolean) {
                repeat(120) { if (condition()) return; delay(500) }
                fail("Timed out waiting for $what")
            }
            until("the first answer") { "echo: hello" in texts() }
            val session = client.cloudSession(started.sessionId)
            assertEquals("remote", session.kind)
            assertEquals(File(project).name, session.host?.directoryName)

            // Paused from the app (through the server): the host checkpoints and exits.
            client.pauseCloudSession(started.sessionId)
            until("the pause") { client.cloudSession(started.sessionId).let { it.state == "paused" && it.host?.online != true } }

            // teamcode logout in a terminal: the app sees the file go and removes the account.
            val cli = assertNotNull(koin.get<CliLocator>().find())
            assertEquals(0, koin.get<CliProcesses>().run(cli.executable, listOf("logout"), 30_000).exitCode)
            sync.sync()
            assertNull(accounts.accounts.value.firstOrNull { it.usedByCli })
            println("Desktop smoke passed: session ${started.sessionId}")
        } finally { koin.close() }
    }
}
