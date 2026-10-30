package me.nathanfallet.teamcode.companion.demo

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakePlatform
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.di.appModules
import me.nathanfallet.teamcode.companion.domain.models.ArtifactScope
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.NewSession
import me.nathanfallet.teamcode.companion.domain.models.PendingMessage
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionFilter
import me.nathanfallet.teamcode.companion.domain.models.WaitSource
import me.nathanfallet.teamcode.companion.domain.models.WaitStatus
import me.nathanfallet.teamcode.companion.domain.models.SessionScope
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.GitHubLinkRepository
import me.nathanfallet.teamcode.companion.domain.repositories.McpServerRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SubscriptionAccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TransferRepository
import me.nathanfallet.teamcode.companion.domain.models.TransferDirection
import me.nathanfallet.teamcode.companion.domain.repositories.DeviceRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import me.nathanfallet.teamcode.companion.domain.services.SecureRandom
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.GetArtifactUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.LoadArtifactsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RenameDeviceUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.SignOutUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.CreateSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadSessionsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SendMessageUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.LoadTranscriptUseCase
import me.nathanfallet.teamcode.companion.infrastructure.credentials.MemoryCredentialStore
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.*

/** The built-in demo on the app's real modules: what it shows and does, and that it never reaches a network or the secure store. */
class DemoModeTest {
    /** Every HTTP client the app would create, and every request it would send: the demo must make neither. */
    private class CountingEngine : HttpClientEngineFactory<MockEngineConfig> {
        var clients = 0
        val requests = mutableListOf<String>()
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine {
            clients++
            return MockEngine(MockEngineConfig().apply { addHandler { request -> requests += request.url.toString(); error("No network in the demo") } })
        }
    }

    /** The platform's secure store, recording each call. */
    private class RecordingStore : CredentialStore {
        val calls = mutableListOf<String>()
        private val memory = MemoryCredentialStore()
        override val persistent = true
        override suspend fun read(accountId: String) = memory.read(accountId).also { calls += "read $accountId" }
        override suspend fun write(accountId: String, token: String) { calls += "write $accountId"; memory.write(accountId, token) }
        override suspend fun delete(accountId: String) { calls += "delete $accountId"; memory.delete(accountId) }
    }

    private val engine = CountingEngine()
    private val store = RecordingStore()
    private val settings = MapSettings()
    private val app = koinApplication {
        modules(appModules(module {
            single<Settings> { settings }
            single<CredentialStore> { store }
            single<HttpClientEngineFactory<*>> { engine }
            single<PlatformInfo> { FakePlatform }
            single<SecureRandom> { SecureRandom { ByteArray(it) } }
        }))
    }
    private val koin: Koin = app.koin

    // The modules are shared values: closing drops the singletons they hold, so the next test starts afresh.
    @AfterTest fun tearDown() = app.close()

    private val accounts get() = koin.get<AccountRepository>()
    private fun startDemo() = koin.get<StartDemoUseCase>()()

    @Test fun theDemoNeverReachesANetworkNorTheSecureStore() = runTest {
        val account = startDemo()
        assertEquals(DemoContent.ACCOUNT_ID, accounts.activeAccountId.value)
        // Everything a member does in the demo, through the app's own use cases.
        assertEquals(account, koin.get<RefreshAccountUseCase>()(account.id))
        val sessions = koin.get<LoadSessionsUseCase>()(account.id, SessionScope.MINE, SessionFilter.OPEN).sessions
        assertEquals(9, sessions.size)
        assertEquals("Fix the flaky login test", sessions.first().name)
        assertTrue(koin.get<LoadTranscriptUseCase>()(account.id, "s0").events.isNotEmpty())
        val send = koin.get<SendMessageUseCase>()
        assertEquals(PendingMessage.State.SENT, send(account.id, "s1", send.draft("Hello")).state)
        assertEquals(2, koin.get<LoadProfilesUseCase>()(account.id).profiles.size)
        koin.get<CreateSessionUseCase>()(account.id, NewSession("Try TeamCode", "demo-claude", null, null, "Hi"), "key")
        val control = koin.get<ControlSessionUseCase>()
        assertEquals(SessionState.PAUSED, control.pause(account.id, "s2").state)
        assertEquals(SessionState.READY, control.resume(account.id, "s2").state)
        control.end(account.id, "s4")
        val artifacts = koin.get<LoadArtifactsUseCase>()(account.id, ArtifactScope.MINE).artifacts
        assertEquals(8, artifacts.size)
        assertEquals("Login flakiness analysis", koin.get<GetArtifactUseCase>()(account.id, artifacts[1].id).artifact.title)
        koin.get<RenameDeviceUseCase>()(account.id, "Ada's iPad")
        assertEquals("Ada's iPad", accounts.accounts.value.single().deviceName)
        assertTrue(koin.get<GitHubLinkRepository>().offered(account.id).not())
        assertTrue(koin.get<McpServerRepository>().servers(account.id).isEmpty())
        assertTrue(koin.get<TransferRepository>().transfers(account.id, TransferDirection.INCOMING, null).transfers.isEmpty())
        assertTrue(koin.get<DeviceRepository>().onlineDevices(account.id).isEmpty())
        koin.get<SubscriptionAccountRepository>().access(account.id)
        assertEquals(DemoContent.features, koin.get<FeaturesRepository>().features(account.id, refresh = true))
        assertEquals(SignOutUseCase.Result.REVOKED, koin.get<SignOutUseCase>()(account.id))

        assertEquals(0, engine.clients, engine.requests.toString())
        assertEquals(emptyList(), store.calls)
        // Nothing of the demo was written to the device's preferences either.
        assertFalse(settings.getStringOrNull(me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsAccountRepository.ACCOUNTS_KEY).orEmpty().contains(DemoContent.ORIGIN))
    }

    @Test fun aMessageAppearsInTheConversationWithTheScriptedReply() = runTest {
        val account = startDemo()
        val live = koin.get<TranscriptRepository>().follow(account.id, "s1", after = 3)
        val send = koin.get<SendMessageUseCase>()
        val sent = send(account.id, "s1", send.draft("Can you add a dark mode?"))

        val events = koin.get<LoadTranscriptUseCase>()(account.id, "s1").events
        val message = events.filterIsInstance<TranscriptEvent.UserMessage>().last()
        assertEquals("Can you add a dark mode?", message.text)
        // The message the screen shows as pending is the one the transcript recorded: it leaves the pending list.
        assertEquals(sent.inputId, message.inputId)
        assertEquals(DemoContent.SCRIPTED_REPLY, (events[events.indexOf(message) + 1] as TranscriptEvent.AgentMessage).text)
        // The same events arrive live, in order, on a screen that follows the session.
        val followed = live.filterIsInstance<LiveUpdate.Item<TranscriptEvent>>().map { it.value }.take(3).toList()
        assertEquals(listOf(TranscriptEvent.UserMessage::class, TranscriptEvent.AgentMessage::class, TranscriptEvent.Turn::class), followed.map { it::class })
        assertTrue(DemoContent.SCRIPTED_REPLY.contains("no AI agent is connected"))
        assertTrue(DemoContent.SCRIPTED_REPLY.contains(DemoContent.REPOSITORY_URL))
    }

    @Test fun aNewSessionAppearsWithTheScriptedReply() = runTest {
        val account = startDemo()
        val feed = koin.get<SessionRepository>().sessionChanges(account.id)
        val created = koin.get<CreateSessionUseCase>()(account.id, NewSession("Add a dark mode", "demo-codex", null, null, null), "key")

        assertEquals("Add a dark mode", created.name)
        assertEquals("codex", created.agentKind)
        assertEquals(SessionState.READY, created.state)
        assertEquals(created.id, koin.get<LoadSessionsUseCase>()(account.id, SessionScope.MINE, SessionFilter.OPEN).sessions.first().id)
        // Without a first message, the session opens on the reply alone.
        val events = koin.get<LoadTranscriptUseCase>()(account.id, created.id).events
        assertEquals(DemoContent.SCRIPTED_REPLY, events.filterIsInstance<TranscriptEvent.AgentMessage>().single().text)
        assertTrue(events.none { it is TranscriptEvent.UserMessage })
        // The session list hears about it.
        assertIs<LiveUpdate.Opened>(feed.first())
    }

    @Test fun theDemoHasWaitsToAnswerAndTheFeedAnnouncesAnAnswer() = runTest {
        val account = startDemo()
        val waits = koin.get<me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository>()
        val open = waits.list(account.id, WaitStatus.OPEN).waits
        // A session's own request comes first, like the server's first page; the others are answered in place.
        assertEquals(listOf("w2", "w1", "w3", "w4"), open.map { it.id })
        assertEquals(WaitSource.SESSION, open.first().source)
        assertEquals(listOf("w5"), waits.list(account.id, WaitStatus.RESOLVED).waits.map { it.id })
        assertTrue(waits.list(account.id, WaitStatus.CANCELLED).waits.single().cancelled)
        val feed = koin.get<SessionRepository>().sessionChanges(account.id)
        // Listening first: the feed has no replay.
        val announced = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { feed.filterIsInstance<LiveUpdate.Item<SessionChange>>().first().value }
        kotlinx.coroutines.yield()
        val answered = waits.answer(account.id, "w1", "approve", emptyMap())
        assertEquals(WaitStatus.RESOLVED, answered.status)
        assertEquals(3, waits.list(account.id, WaitStatus.OPEN).waits.size)
        assertEquals(SessionChange.WAIT_RESOLVED, announced.await().type)
        // A session's request is answered in the session, a closed wait does not change, an unknown action is refused.
        assertEquals(409, assertFailsWith<me.nathanfallet.teamcode.companion.domain.models.CompanionException.Server> { waits.answer(account.id, "w2", "allow", emptyMap()) }.statusCode)
        assertEquals(409, assertFailsWith<me.nathanfallet.teamcode.companion.domain.models.CompanionException.Server> { waits.answer(account.id, "w1", "approve", emptyMap()) }.statusCode)
        assertEquals(400, assertFailsWith<me.nathanfallet.teamcode.companion.domain.models.CompanionException.Server> { waits.answer(account.id, "w3", "nope", emptyMap()) }.statusCode)
        assertEquals(0, engine.clients)
    }

    @Test fun signingOutEndsTheDemoAndDropsEverythingItHeld() = runTest {
        val account = startDemo()
        val send = koin.get<SendMessageUseCase>()
        send(account.id, "s1", send.draft("Remember this"))
        koin.get<SignOutUseCase>()(account.id)

        assertTrue(accounts.accounts.value.isEmpty())
        assertNull(accounts.activeAccountId.value)
        assertFalse(koin.get<DemoMode>().active)
        // An ended demo answers nothing, from memory or elsewhere.
        assertFailsWith<me.nathanfallet.teamcode.companion.domain.models.CompanionException.NotFound> { koin.get<SessionRepository>().session(account.id, "s1") }
        // A new demo starts from scratch.
        startDemo()
        val events = koin.get<LoadTranscriptUseCase>()(account.id, "s1").events
        assertTrue(events.filterIsInstance<TranscriptEvent.UserMessage>().none { it.text == "Remember this" })
        assertEquals(0, engine.clients)
    }

    @Test fun theDemoIsOfferedOnlyOnADeviceWithoutAccounts() = runTest {
        val start = koin.get<StartDemoUseCase>()
        assertTrue(start.offered)
        start()
        assertFalse(start.offered)
        koin.get<SignOutUseCase>()(DemoContent.ACCOUNT_ID)
        assertTrue(start.offered)
        accounts.save(account("real"))
        assertFalse(start.offered)
        assertFailsWith<IllegalStateException> { (accounts as DemoAccountRepository).startDemo("Bench") }
    }

    @Test fun aRealAccountEndsTheDemo() {
        startDemo()
        // A sign-in (or the desktop's CLI account) saves a real account: the demo leaves, and only the real account stays.
        accounts.save(account("real"))
        assertEquals(listOf("real"), accounts.accounts.value.map { it.id })
        assertEquals("real", accounts.activeAccountId.value)
        assertFalse(koin.get<DemoMode>().active)
    }

    @Test fun theDemoAccountIsNeverStoredWithTheRealOnes() {
        startDemo()
        // The device's own list (its preferences) never holds it: quitting the app ends the demo.
        val stored = koin.get<me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsAccountRepository>()
        assertTrue(stored.accounts.value.isEmpty())
        assertTrue(DemoContent.account("Bench").isDemo)
        assertFalse(account("a1").isDemo)
    }
}
