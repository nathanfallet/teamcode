package me.nathanfallet.teamcode.companion.desktop

import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.InputReceipt
import me.nathanfallet.teamcode.companion.domain.models.NewSession
import me.nathanfallet.teamcode.companion.domain.models.PermissionAnswer
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionKind
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionScope
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.companion.infrastructure.credentials.MemoryCredentialStore
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsAccountRepository
import kotlin.test.*

private class NoSessions : SessionRepository {
    override suspend fun sessions(accountId: String, scope: SessionScope, group: SessionGroup, before: String?, kinds: Set<SessionKind>): SessionPage = error("unused")
    override suspend fun session(accountId: String, sessionId: String): CloudSession = error("unused")
    override suspend fun profiles(accountId: String): List<Profile> = error("unused")
    override suspend fun create(accountId: String, session: NewSession, idempotencyKey: String): CloudSession = error("unused")
    override suspend fun createOnDevice(accountId: String, session: me.nathanfallet.teamcode.companion.domain.models.NewDeviceSession, idempotencyKey: String): CloudSession = error("unused")
    override suspend fun send(accountId: String, sessionId: String, text: String, clientMessageId: String, fileIds: List<String>): InputReceipt = error("unused")
    override suspend fun answer(accountId: String, sessionId: String, answer: PermissionAnswer, idempotencyKey: String): InputReceipt = error("unused")
    override suspend fun interrupt(accountId: String, sessionId: String, idempotencyKey: String): InputReceipt = error("unused")
    override suspend fun pause(accountId: String, sessionId: String): CloudSession = error("unused")
    override suspend fun resume(accountId: String, sessionId: String): CloudSession = error("unused")
    override fun sessionChanges(accountId: String, kinds: Set<SessionKind>) = emptyFlow<me.nathanfallet.teamcode.companion.domain.models.LiveUpdate<me.nathanfallet.teamcode.companion.domain.models.SessionChange>>()
}

private object Platform : PlatformInfo {
    override val platform = "macOS"
    override val deviceModel = "mac"
    override val appVersion = "1.0.0"
}

/** Which of this device's sessions get a host, when, and how often. */
class RemoteHostSupervisorTest {
    private class Fixture(directory: File, val listed: () -> String = { "" }) {
        var now = 1_000_000L
        val records = File(directory, "remote").apply { mkdirs() }
        val accounts = SettingsAccountRepository(MapSettings()).apply {
            save(Account(Account.CLI_ACCOUNT_ID, "https://team.example", "ada", "m1", "MEMBER", "Mac", Long.MAX_VALUE, "1.0.0"))
        }
        val credentials = MemoryCredentialStore()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/devices") -> """[{"id":"d1","memberId":"m1","name":"Mac","createdAt":1,"lastLoginAt":1,"activeSessions":1,"current":true}]"""
                path == "/api/v1/cloud/sessions" -> """{"sessions":[${listed()}]}"""
                else -> error("unexpected $path")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val clients = TeamCodeClientProvider(MockEngineFactory(engine), credentials, Platform)
        val bin = File(directory, "bin").apply { mkdirs() }
        val environment = DesktopEnvironment(mapOf("HOME" to File(directory, "home").path, "PATH" to bin.path), os = DesktopEnvironment.Os.current())
        val processes = CliProcesses(environment)
        val features = object : FeaturesRepository { override suspend fun features(accountId: String, refresh: Boolean) = setOf("cloud.sessions.remote.v1") }
        val supervisor = RemoteHostSupervisor(accounts, clients, features, NoSessions(), CliLocator(environment, processes), processes, Clock { now }, "1.0.0", records.path,
            graceMillis = 30_000, maxStarts = 3, windowMillis = 600_000)
        fun record(id: String) = File(records, "$id.json").writeText("{}")
        fun resumes() = File(bin, "calls").takeIf { it.exists() }?.readLines().orEmpty().filter { it.startsWith("remote resume") }
    }

    @Test fun onlyThisDevicesRecordedSessionsWantedRunningWithoutAHost() = withTemporaryDirectory { directory ->
        val f = Fixture(directory)
        listOf("woken", "running-dead", "online", "elsewhere", "paused", "unrecorded", "ended").forEach { if (it != "unrecorded") f.record(it) }
        val listed = listOf(
            sessionView("woken", "paused", "running", "d1", online = false),
            sessionView("running-dead", "active", "running", "d1", online = false),
            sessionView("online", "active", "running", "d1", online = true),
            sessionView("elsewhere", "paused", "running", "d2", online = false),
            sessionView("paused", "paused", "paused", "d1", online = false),
            sessionView("unrecorded", "paused", "running", "d1", online = false),
            sessionView("ended", "terminated", "running", "d1", online = false),
        )
        val recorded = { id: String -> File(f.records, "$id.json").exists() }
        // A woken session at once; a running one whose host stopped only after the grace period.
        assertEquals(listOf("woken"), f.supervisor.hostsToStart(listed, "d1", recorded, f.now).map { it.id })
        f.supervisor.started("woken", 1)
        f.now += 30_000
        assertEquals(listOf("running-dead"), f.supervisor.hostsToStart(listed, "d1", recorded, f.now).map { it.id })
        // Its host took the session (a newer epoch) and stopped again: woken again, it is started at once.
        val again = listed.map { if (it.id == "woken") it.copy(currentEpoch = 2) else it }
        assertTrue("woken" in f.supervisor.hostsToStart(again, "d1", recorded, f.now).map { it.id })
    }

    @Test fun aHostThatKeepsStoppingIsLeftAloneAndReportedOnce() = withTemporaryDirectory { directory ->
        val f = Fixture(directory)
        f.record("s1")
        val listed = listOf(sessionView("s1", "paused", "running", "d1", online = false))
        val recorded = { _: String -> true }
        repeat(3) {
            assertEquals(1, f.supervisor.hostsToStart(listed, "d1", recorded, f.now).size)
            f.supervisor.started("s1", 1)
            f.now += 120_000
        }
        assertTrue(f.supervisor.hostsToStart(listed, "d1", recorded, f.now).isEmpty())
        assertTrue(f.supervisor.hostsToStart(listed, "d1", recorded, f.now).isEmpty())
        // Ten minutes after the first start, it may be started again.
        f.now += 240_000
        assertEquals(1, f.supervisor.hostsToStart(listed, "d1", recorded, f.now).size)
    }

    @Test fun aPassResumesTheWokenSessionThroughTheCli() = withTemporaryDirectory { directory -> runTest {
        if (!posix) return@runTest
        val f = Fixture(directory) { sessionView("s1", "paused", "running", "d1", online = false).let { me.nathanfallet.teamcode.api.Serialization.json.encodeToString(it) } }
        fakeCli(f.bin, remote = "exit 0")
        f.record("s1")
        f.credentials.write(Account.CLI_ACCOUNT_ID, "tc-cli")
        f.supervisor.reconcile()
        // The arguments only: the runner's own XDG_CONFIG_HOME, if any, is inherited by the fake CLI.
        assertEquals(listOf("remote resume s1 --detach"), f.resumes().map { it.substringBefore(" |") })
        // Right after, the host is registering: not started twice.
        f.supervisor.reconcile()
        assertEquals(1, f.resumes().size)
    } }
}

/** A factory around one mock engine, as the provider creates its own HTTP clients. */
private class MockEngineFactory(private val engine: MockEngine) : io.ktor.client.engine.HttpClientEngineFactory<io.ktor.client.engine.mock.MockEngineConfig> {
    override fun create(block: io.ktor.client.engine.mock.MockEngineConfig.() -> Unit) = engine
}
