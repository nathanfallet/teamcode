package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import me.nathanfallet.teamcode.companion.domain.models.*
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.*
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.*
import me.nathanfallet.teamcode.companion.presentation.screens.*
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.*
import kotlin.test.*

/** The automation screens on the desktop target over a fake repository: list, page, form and test. */
@OptIn(ExperimentalTestApi::class)
class AutomationsScreenTest {
    private val repository = FakeAutomationRepository()
    private val sessions = FakeSessionRepository().also { it.profileList = listOf(Profile("p1", "support", "claude")) }
    private val features = FakeFeatures(setOf("automations.v1", "cloud.sessions.v1"))
    private val secrets = AutomationSecrets()
    private val accounts = InMemoryAccountRepository(listOf(account()))
    private var role: String
        get() = accounts.accounts.value.single().role
        set(value) = accounts.save(account().copy(role = value))
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
    private val access = AutomationAccess(repository, features, accounts, guard)

    // The screens read the clock for their relative times.
    @BeforeTest fun startKoin() { org.koin.core.context.startKoin { modules(org.koin.dsl.module { single<me.nathanfallet.teamcode.companion.domain.services.Clock> { FixedClock(1_000_000L) } }) } }
    @AfterTest fun stopKoin() = org.koin.core.context.stopKoin()

    private fun list() = AutomationsViewModel(AutomationsRoute("a1"), LoadAutomationsUseCase(access), SetAutomationEnabledUseCase(access), DeleteAutomationUseCase(access), accounts)
    private fun page() = AutomationViewModel(AutomationRoute("a1", "au1"), GetAutomationUseCase(access), SetAutomationEnabledUseCase(access), DeleteAutomationUseCase(access),
        RotateAutomationSecretUseCase(access), LoadAutomationRunsUseCase(access), CancelAutomationRunUseCase(access), secrets, LoadProfilesUseCase(sessions, features, guard), accounts)
    private fun form() = AutomationEditViewModel(AutomationEditRoute("a1"), GetAutomationUseCase(access), LoadProfilesUseCase(sessions, features, guard), SaveAutomationUseCase(access), PreviewAutomationUseCase(access), secrets, accounts)
    private fun test() = AutomationTestViewModel(AutomationTestRoute("a1", "au1"), GetAutomationUseCase(access), PreviewAutomationUseCase(access), TestAutomationUseCase(access), SequenceIds(), accounts)

    private fun ComposeUiTest.assertTagged(tag: String, text: String) {
        onNode(hasTestTag(tag) and (hasText(text) or hasAnyDescendant(hasText(text))), useUnmergedTree = true).assertExists()
    }

    @Test fun theListShowsStateLastRunNextRunAndFailures() = runComposeUiTest {
        val model = list()
        setContent { TeamCodeTheme { AutomationsScreen(model, onOpen = {}, onCreate = {}) } }
        waitUntil { model.uiState.value.automations != null }
        assertTagged("automation-state-au1", "Active")
        assertTagged("automation-state-au2", "Paused")
        assertTagged("automation-last-au1", "Succeeded")
        assertTagged("automation-trigger-au1", "0 21 * * * · Europe/Paris")
        assertTagged("automation-trigger-au2", "Webhook")
        assertTagged("automation-failures-au2", "3 consecutive failures")
        onNodeWithTag("automation-next-au1", useUnmergedTree = true).assertExists()
        onNodeWithTag("automation-next-au2", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("automations-team").assertDoesNotExist()
    }

    @Test fun theSwitchPausesFromTheList() = runComposeUiTest {
        val model = list()
        repository.current = automation(enabled = false)
        setContent { TeamCodeTheme { AutomationsScreen(model, onOpen = {}, onCreate = {}) } }
        waitUntil { model.uiState.value.automations != null }
        onNodeWithTag("automation-toggle-au1").performClick()
        waitUntil { model.uiState.value.automations!![0].enabled.not() }
        assertTrue("disable:au1" in repository.calls)
    }

    @Test fun anAdministratorHasTheTeamFilterAndCannotArmAMembersAutomation() = runComposeUiTest {
        role = "ADMIN"
        repository.summaries = listOf(summary("x2", ownerId = "m2", enabled = false))
        val model = list()
        setContent { TeamCodeTheme { AutomationsScreen(model, onOpen = {}, onCreate = {}) } }
        waitUntil { model.uiState.value.automations != null }
        onNodeWithTag("automations-team").performClick()
        waitUntil { "list:TEAM:null" in repository.calls && !model.uiState.value.loading }
        onNodeWithTag("automation-toggle-x2").assertIsNotEnabled()
    }

    @Test fun noAutomationShowsTheEmptyStateAndAFailureCanBeRetried() = runComposeUiTest {
        repository.summaries = emptyList()
        val model = list()
        setContent { TeamCodeTheme { AutomationsScreen(model, onOpen = {}, onCreate = {}) } }
        waitUntil { !model.uiState.value.loading }
        onNodeWithTag("automations-empty").assertExists()
        repository.failure = CompanionException.Unreachable()
        model.reload()
        waitUntil { model.uiState.value.error != null }
        onNodeWithTag("automations-error").assertExists()
        repository.failure = null
        onNodeWithTag("automations-retry").performClick()
        waitUntil { model.uiState.value.error == null && !model.uiState.value.loading }
    }

    @Test fun theWebhookPageCopiesTheUrlForTheChosenProofAndVersion() = runComposeUiTest {
        repository.current = automation(version = 3, definition = webhookDefinition())
        secrets.hold("au1", "whsec_abc")
        val model = page()
        setContent { TeamCodeTheme { AutomationScreen(model, onBack = {}, onEdit = {}, onTest = {}, onOpenSession = {}, onDeleted = {}) } }
        waitUntil { model.uiState.value.automation != null && !model.uiState.value.runsLoading }
        onNodeWithTag("automation-secret").assertExists()
        assertTagged("automation-url", "https://team.example/api/v1/automations/au1/trigger")
        onNodeWithTag("automation-proof-secret_url").performScrollTo().performClick()
        onNodeWithTag("automation-version-pinned").performScrollTo().performClick()
        waitForIdle()
        assertTagged("automation-url", "https://team.example/api/v1/automations/au1/v/3/trigger/whsec_abc")
        onNodeWithTag("automation-secret-stored").performScrollTo().performClick()
        waitUntil { model.uiState.value.secret == null }
        assertTagged("automation-url", "https://team.example/api/v1/automations/au1/v/3/trigger/<secret>")
    }

    @Test fun theHistoryBadgesTestsAndLinksToSessions() = runComposeUiTest {
        val model = page()
        setContent { TeamCodeTheme { AutomationScreen(model, onBack = {}, onEdit = {}, onTest = {}, onOpenSession = {}, onDeleted = {}) } }
        waitUntil { model.uiState.value.runs.isNotEmpty() }
        assertTagged("run-source-r1", "Schedule")
        assertTagged("run-source-r2", "Test")
        assertTagged("run-status-r2", "Running")
        onNodeWithTag("run-session-r1").performScrollTo().assertExists()
        onNodeWithTag("automation-history-no-tests").performScrollTo().performClick()
        waitUntil { model.uiState.value.runs.size == 1 }
        onNodeWithTag("run-r2").assertDoesNotExist()
    }

    @Test fun anAdministratorsPageOfAMembersAutomationHasNoEditNorRealTest() = runComposeUiTest {
        role = "ADMIN"; repository.current = automation(ownerId = "m2", enabled = true)
        val model = page()
        setContent { TeamCodeTheme { AutomationScreen(model, onBack = {}, onEdit = {}, onTest = {}, onOpenSession = {}, onDeleted = {}) } }
        waitUntil { model.uiState.value.automation != null }
        onNodeWithTag("automation-read-only").assertExists()
        onNodeWithTag("automation-edit").assertDoesNotExist()
        assertTagged("automation-test", "Preview")
        onNodeWithTag("automation-pause").assertExists()
        onNodeWithTag("automation-delete").performScrollTo().assertExists()
    }

    @Test fun theFormShowsTheRemoteActionGreyedAndRefusesAnEmptyOne() = runComposeUiTest {
        val model = form()
        setContent { TeamCodeTheme { AutomationEditScreen(model, onBack = {}, onSaved = {}) } }
        waitUntil { !model.uiState.value.loading }
        assertTagged("automation-action-remote-soon", "Coming later")
        // Not selectable: tapping it changes nothing.
        onNodeWithTag("automation-action-remote").performScrollTo().performClick()
        assertEquals(ActionKind.CLOUD, definitionKind(model))
        onNodeWithTag("automation-save").performClick()
        waitUntil { model.uiState.value.error != null }
        onNodeWithTag("automation-save-error").assertExists()
        onNodeWithTag("automation-name").performTextInput("Nightly")
        onNodeWithTag("automation-kind-webhook").performClick()
        onNodeWithTag("automation-variable-add").performScrollTo().assertExists()
    }

    private fun definitionKind(model: AutomationEditViewModel) = ActionKind.CLOUD.also { assertNotNull(model.uiState.value.profiles) }

    @Test fun theTestScreenPreviewsThenAsksBeforeARealRun() = runComposeUiTest {
        repository.current = automation(definition = webhookDefinition())
        val model = test()
        setContent { TeamCodeTheme { AutomationTestScreen(model, onBack = {}, onOpenSession = {}) } }
        waitUntil { model.uiState.value.automation != null }
        onNodeWithTag("automation-test-preview").performClick()
        waitUntil { model.uiState.value.preview != null }
        assertTagged("automation-rendered-prompt", "Fix ticket 735.")
        onNodeWithTag("automation-test-run").performScrollTo().performClick()
        onNodeWithTag("automation-test-confirm").assertExists()
        assertTrue(repository.tested.isEmpty())
        onNodeWithTag("automation-test-confirm-yes").performClick()
        waitUntil { model.uiState.value.run != null }
        assertTagged("automation-test-status", "Queued")
        assertEquals(1, repository.tested.size)
    }

    @Test fun anAdministratorOnlyPreviewsAMembersAutomation() = runComposeUiTest {
        role = "ADMIN"; repository.current = automation(ownerId = "m2")
        val model = test()
        setContent { TeamCodeTheme { AutomationTestScreen(model, onBack = {}, onOpenSession = {}) } }
        waitUntil { model.uiState.value.automation != null }
        onNodeWithTag("automation-test-read-only").assertExists()
        onNodeWithTag("automation-test-run").assertDoesNotExist()
    }
}
