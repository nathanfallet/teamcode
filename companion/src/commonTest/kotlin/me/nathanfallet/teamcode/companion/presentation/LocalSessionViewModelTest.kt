package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeSessionRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.SequenceIds
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.PendingMessage
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.services.LocalAvailability
import me.nathanfallet.teamcode.companion.domain.services.LocalHost
import me.nathanfallet.teamcode.companion.domain.services.LocalHostException
import me.nathanfallet.teamcode.companion.domain.services.LocalSessionRequest
import me.nathanfallet.teamcode.companion.domain.services.LocalResumables
import me.nathanfallet.teamcode.companion.domain.services.LocalResumeBlock
import me.nathanfallet.teamcode.companion.domain.models.ResumableSession
import me.nathanfallet.teamcode.companion.domain.models.ResumableSessions
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SendMessageUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.StartLocalSessionUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.NewSessionRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.LocalSessionViewModel
import me.nathanfallet.teamcode.companion.domain.models.PermissionMode
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsPreferencesRepository
import com.russhwolf.settings.MapSettings
import kotlin.test.*

private class FakeLocalHost(var availability: LocalAvailability = LocalAvailability.Ready) : LocalHost {
    val started = mutableListOf<LocalSessionRequest>()
    var picked: String? = "/Users/ada/git/paywall"
    var failure: Exception? = null
    var installFailure: Exception? = null
    override suspend fun availability(accountId: String) = availability
    override suspend fun installCli(output: (String) -> Unit) {
        output("Downloading TeamCode 1.2.3…")
        installFailure?.let { throw it }
        output("Installed TeamCode 1.2.3.")
        availability = LocalAvailability.Ready
    }
    override suspend fun pickDirectory() = picked
    /** The folders asked for; [listing] answers. */
    val listed = mutableListOf<Pair<String, Long?>>()
    var listing: suspend (String, Long?) -> LocalResumables = { _, _ -> LocalResumables.Page(ResumableSessions(emptyList())) }
    override suspend fun resumable(accountId: String, directory: String, before: Long?): LocalResumables {
        listed += directory to before
        return listing(directory, before)
    }
    override suspend fun start(accountId: String, request: LocalSessionRequest): String {
        failure?.let { throw it }
        started += request
        return "s-${started.size}"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LocalSessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val sessions = FakeSessionRepository()
    private val features = FakeFeatures(FakeFeatures().features + Features.REMOTE_SESSIONS + Features.RESUME_SESSIONS)
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), InMemoryAccountRepository(), credentials(), FixedClock()))
    private fun useCase(host: LocalHost) = StartLocalSessionUseCase(host, features, SendMessageUseCase(sessions, features, guard, SequenceIds()))

    @Test fun theFolderNamesTheSessionAndTheFirstMessageGoesThroughTheServer() = runTest(dispatcher) {
        val host = FakeLocalHost()
        val model = LocalSessionViewModel(NewSessionRoute("cli"), useCase(host), host, SettingsPreferencesRepository(MapSettings()))
        settle()
        assertEquals(LocalAvailability.Ready, model.uiState.value.availability)
        assertFalse(model.uiState.value.canStart)
        model.chooseDirectory(); settle()
        assertEquals("paywall", model.uiState.value.name)
        // A name the member typed is kept when another folder is chosen.
        model.onName("Paywall redesign")
        host.picked = "C:\\Users\\ada\\other"
        model.chooseDirectory(); settle()
        assertEquals("Paywall redesign", model.uiState.value.name)
        assertEquals("C:\\Users\\ada\\other", model.uiState.value.directory)
        model.onAgent("codex"); model.onModel(" gpt-5 "); model.onMessage("Fix the tests"); model.onBypass(true)
        model.create(); settle()
        val request = host.started.single()
        assertEquals(LocalSessionRequest("C:\\Users\\ada\\other", "codex", "Paywall redesign", "gpt-5", "", true), request)
        // The message is not part of what the CLI is given: it is sent to the created session.
        assertTrue(sessions.calls.any { it.startsWith("send s-1") }, sessions.calls.toString())
        assertEquals("s-1", model.uiState.value.started?.sessionId)
        assertEquals(PendingMessage.State.SENT, model.uiState.value.started?.firstMessage?.state)
        assertFalse(model.uiState.value.messageFailed)
    }

    @Test fun thePermissionModeOfTheLastLaunchComesBackOnThisDeviceToo() = runTest(dispatcher) {
        val settings = MapSettings()
        val host = FakeLocalHost()
        val first = LocalSessionViewModel(NewSessionRoute("cli"), useCase(host), host, SettingsPreferencesRepository(settings))
        settle()
        assertFalse(first.uiState.value.bypassPermissions)
        first.chooseDirectory(); first.onBypass(true); settle()
        first.create(); settle()
        assertTrue(host.started.single().bypassPermissions)
        val next = LocalSessionViewModel(NewSessionRoute("cli"), useCase(host), host, SettingsPreferencesRepository(settings))
        assertTrue(next.uiState.value.bypassPermissions)
        assertEquals(PermissionMode.BYPASS, SettingsPreferencesRepository(settings).permissionMode)
    }

    @Test fun aFirstMessageThatFailsIsSaidAndTheSessionStillOpens() = runTest(dispatcher) {
        val host = FakeLocalHost()
        sessions.sendResult = { throw CompanionException.Unreachable() }
        val model = LocalSessionViewModel(NewSessionRoute("cli"), useCase(host), host, SettingsPreferencesRepository(MapSettings()))
        settle()
        model.chooseDirectory(); model.onMessage("Hello"); settle()
        model.create(); settle()
        assertEquals("s-1", model.uiState.value.started?.sessionId)
        assertTrue(model.uiState.value.messageFailed)
    }

    @Test fun whatTheCliNeedsIsShownAndCheckedAgain() = runTest(dispatcher) {
        val host = FakeLocalHost(LocalAvailability.CliMissing("curl -fsSL https://example/install.sh | sh"))
        val model = LocalSessionViewModel(NewSessionRoute("cli"), useCase(host), host, SettingsPreferencesRepository(MapSettings()))
        settle()
        assertIs<LocalAvailability.CliMissing>(model.uiState.value.availability)
        model.chooseDirectory(); settle()
        assertFalse(model.uiState.value.canStart)
        host.availability = LocalAvailability.Ready
        model.check(); settle()
        assertTrue(model.uiState.value.canStart)
    }

    @Test fun aServerWithoutRemoteSessionsIsSaidBeforeTheCliIsLookedFor() = runTest(dispatcher) {
        val host = FakeLocalHost(LocalAvailability.CliMissing("x"))
        val model = LocalSessionViewModel(NewSessionRoute("cli"), StartLocalSessionUseCase(host, FakeFeatures(), SendMessageUseCase(sessions, FakeFeatures(), guard, SequenceIds())), host, SettingsPreferencesRepository(MapSettings()))
        settle()
        assertEquals(LocalAvailability.ServerUnsupported, model.uiState.value.availability)
    }

    @Test fun theClisRefusalIsShownAsIs() = runTest(dispatcher) {
        val host = FakeLocalHost().apply { failure = LocalHostException("Choose a project directory: /Users/ada itself.") }
        val model = LocalSessionViewModel(NewSessionRoute("cli"), useCase(host), host, SettingsPreferencesRepository(MapSettings()))
        settle()
        model.chooseDirectory(); settle()
        model.create(); settle()
        assertEquals("Choose a project directory: /Users/ada itself.", model.uiState.value.error?.text)
        assertNull(model.uiState.value.started)
        assertTrue(model.uiState.value.canStart)
    }

    private val first = ResumableSession("0000000a-aaaa-4bbb-8ccc-00000000000a", "Fix the build", 1_000, 3)
    private val second = ResumableSession("0000000b-aaaa-4bbb-8ccc-00000000000b", "Release notes", 500, 1)

    @Test fun resumeIsNoneByDefaultReadOnFirstOpeningAndSentWhenChosen() = runTest(dispatcher) {
        val host = FakeLocalHost().apply { listing = { _, before -> LocalResumables.Page(if (before == null) ResumableSessions(listOf(first), 1_000) else ResumableSessions(listOf(second))) } }
        val model = LocalSessionViewModel(NewSessionRoute("cli"), useCase(host), host, SettingsPreferencesRepository(MapSettings()))
        settle()
        assertFalse(model.uiState.value.showsResume, "No folder yet")
        model.chooseDirectory(); settle()
        assertTrue(model.uiState.value.showsResume)
        assertNull(model.uiState.value.resumeId)
        assertTrue(host.listed.isEmpty(), "Nothing is read before the picker opens")
        model.onResumeOpen(); settle()
        assertEquals(listOf<Pair<String, Long?>>("/Users/ada/git/paywall" to null), host.listed)
        assertEquals(listOf(first), model.uiState.value.resumable)
        model.onResumeMore(); settle()
        assertEquals(listOf(first, second), model.uiState.value.resumable)
        model.onResume("not-listed")
        assertNull(model.uiState.value.resumeId)
        model.onResume(second.id)
        assertEquals(second.id, model.uiState.value.resumeId)
        assertFalse(model.uiState.value.resumeOpen)
        model.create(); settle()
        assertEquals(second.id, host.started.single().resumeSessionId)
    }

    @Test fun anotherFolderOrCodexClearsTheChoice() = runTest(dispatcher) {
        val host = FakeLocalHost().apply { listing = { _, _ -> LocalResumables.Page(ResumableSessions(listOf(first))) } }
        val model = LocalSessionViewModel(NewSessionRoute("cli"), useCase(host), host, SettingsPreferencesRepository(MapSettings()))
        settle()
        model.chooseDirectory(); settle()
        model.onResumeOpen(); settle()
        model.onResume(first.id)
        host.picked = "/Users/ada/git/web"
        model.chooseDirectory(); settle()
        assertNull(model.uiState.value.resumeId, "A conversation belongs to its folder")
        assertNull(model.uiState.value.resumable, "Read again for the new folder")
        model.onResumeOpen(); settle()
        assertEquals("/Users/ada/git/web", host.listed.last().first)
        model.onResume(first.id)
        model.onAgent("codex")
        assertNull(model.uiState.value.resumeId)
        assertFalse(model.uiState.value.showsResume, "Claude Code only")
        model.create(); settle()
        assertNull(host.started.single().resumeSessionId)
    }

    @Test fun aListStillLoadingForThePreviousFolderIsDropped() = runTest(dispatcher) {
        // The first folder's conversations take 5 s to read; another folder is chosen meanwhile.
        val host = FakeLocalHost().apply { listing = { directory, _ ->
            if (directory.endsWith("paywall")) { kotlinx.coroutines.delay(5_000); LocalResumables.Page(ResumableSessions(listOf(first))) }
            else LocalResumables.Page(ResumableSessions(emptyList()))
        } }
        val model = LocalSessionViewModel(NewSessionRoute("cli"), useCase(host), host, SettingsPreferencesRepository(MapSettings()))
        settle()
        model.chooseDirectory(); settle()
        model.onResumeOpen(); settle()
        assertTrue(model.uiState.value.loadingResumable)
        host.picked = "/Users/ada/git/web"
        model.chooseDirectory(); settle()
        repeat(6) { settle() }
        assertNull(model.uiState.value.resumable)
        assertFalse(model.uiState.value.loadingResumable)
    }

    @Test fun whyThisDeviceCannotResumeIsSaidAndCheckedAgain() = runTest(dispatcher) {
        val host = FakeLocalHost().apply { listing = { _, _ -> LocalResumables.Unavailable(LocalResumeBlock.DAEMON_OFFLINE) } }
        val model = LocalSessionViewModel(NewSessionRoute("cli"), useCase(host), host, SettingsPreferencesRepository(MapSettings()))
        settle()
        model.chooseDirectory(); settle()
        model.onResumeOpen(); settle()
        assertEquals(LocalResumeBlock.DAEMON_OFFLINE, model.uiState.value.resumeBlock)
        assertFalse(model.uiState.value.resumeOpen, "Nothing to choose from")
        assertTrue(model.uiState.value.canStart, "A new conversation still starts")
        host.listing = { _, _ -> LocalResumables.Page(ResumableSessions(listOf(first))) }
        model.onResumeRetry(); settle()
        assertNull(model.uiState.value.resumeBlock)
        assertEquals(listOf(first), model.uiState.value.resumable)

        // A failure of the CLI is said in the picker, with Retry.
        host.listing = { _, _ -> throw LocalHostException("The TeamCode CLI did not answer in time.") }
        model.onResumeRetry(); settle()
        assertEquals("The TeamCode CLI did not answer in time.", model.uiState.value.resumableError?.text)
    }

    @Test fun aServerWithoutResumeIsSaidWithoutAskingTheCli() = runTest(dispatcher) {
        val host = FakeLocalHost()
        val plain = FakeFeatures(FakeFeatures().features + Features.REMOTE_SESSIONS)
        val model = LocalSessionViewModel(NewSessionRoute("cli"), StartLocalSessionUseCase(host, plain, SendMessageUseCase(sessions, plain, guard, SequenceIds())), host,
            SettingsPreferencesRepository(MapSettings()))
        settle()
        model.chooseDirectory(); settle()
        model.onResumeOpen(); settle()
        assertEquals(LocalResumeBlock.SERVER, model.uiState.value.resumeBlock)
        assertTrue(host.listed.isEmpty())
    }

    @Test fun theDefaultNameIsTheFoldersOwnName() {
        assertEquals("paywall", StartLocalSessionUseCase.defaultName("/Users/ada/git/paywall/"))
        assertEquals("other", StartLocalSessionUseCase.defaultName("C:\\Users\\ada\\other"))
    }

    @Test fun installingTheCliShowsItsOutputThenChecksAgain() = runTest(dispatcher) {
        val host = FakeLocalHost(LocalAvailability.CliMissing("curl … | sh", canInstall = true))
        val model = LocalSessionViewModel(NewSessionRoute("cli"), useCase(host), host, SettingsPreferencesRepository(MapSettings()))
        settle()
        assertIs<LocalAvailability.CliMissing>(model.uiState.value.availability)
        model.installCli(); settle()
        assertEquals(listOf("Downloading TeamCode 1.2.3…", "Installed TeamCode 1.2.3."), model.uiState.value.installOutput)
        assertFalse(model.uiState.value.installing)
        assertEquals(LocalAvailability.Ready, model.uiState.value.availability)

        val failing = FakeLocalHost(LocalAvailability.CliMissing("curl … | sh", canInstall = true)).apply {
            installFailure = LocalHostException("The downloaded install script is not the one this app was built with. Nothing was run.")
        }
        val refused = LocalSessionViewModel(NewSessionRoute("cli"), useCase(failing), failing, SettingsPreferencesRepository(MapSettings()))
        settle()
        refused.installCli(); settle()
        assertEquals("The downloaded install script is not the one this app was built with. Nothing was run.", refused.uiState.value.error?.text)
        assertIs<LocalAvailability.CliMissing>(refused.uiState.value.availability)
        assertFalse(refused.uiState.value.installing)
    }

    @Test fun aFolderGivenByTeamcodeUiIsChosenAlready() = runTest(dispatcher) {
        val host = FakeLocalHost()
        val model = LocalSessionViewModel(NewSessionRoute("cli", "/Users/ada/git/paywall"), useCase(host), host, SettingsPreferencesRepository(MapSettings()))
        settle()
        assertEquals("/Users/ada/git/paywall", model.uiState.value.directory)
        assertEquals("paywall", model.uiState.value.name)
        assertTrue(model.uiState.value.canStart)
    }
}

/** Runs what is due within a second of virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }
