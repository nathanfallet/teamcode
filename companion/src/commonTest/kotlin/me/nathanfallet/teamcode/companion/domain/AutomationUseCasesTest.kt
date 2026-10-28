package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.*
import me.nathanfallet.teamcode.companion.domain.models.*
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.*
import kotlin.test.*

class AutomationUseCasesTest {
    private val repository = FakeAutomationRepository()
    private val features = FakeFeatures(setOf("automations.v1"))
    private fun access(role: String = "MEMBER"): AutomationAccess {
        val accounts = InMemoryAccountRepository(listOf(account().copy(role = role)))
        return AutomationAccess(repository, features, accounts, AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock())))
    }

    @Test fun availabilityFollowsTheCapabilityAndTheSignIn() = runTest {
        val accounts = InMemoryAccountRepository(listOf(account(), account("a2", signedIn = false)))
        val availability = AutomationsAvailabilityUseCase(accounts, features)
        assertTrue(availability.available("a1"))
        assertFalse(availability.available("a2"))
        assertFalse(availability.available("nobody"))
        features.features = emptySet()
        assertFalse(availability.available("a1"))
    }

    @Test fun aServerWithoutAutomationsIsReportedAsNotOfferingThem() = runTest {
        features.features = emptySet()
        assertFailsWith<CompanionException.Unsupported> { LoadAutomationsUseCase(access())("a1", AutomationScope.MINE) }
        assertTrue(repository.calls.isEmpty())
    }

    @Test fun onlyAnAdministratorReadsTheTeam() = runTest {
        assertFailsWith<CompanionException.Unsupported> { LoadAutomationsUseCase(access("MEMBER"))("a1", AutomationScope.TEAM) }
        assertEquals(2, LoadAutomationsUseCase(access("ADMIN"))("a1", AutomationScope.TEAM).automations.size)
    }

    @Test fun aDefinitionIsCheckedBeforeAnythingIsSent() = runTest {
        val save = SaveAutomationUseCase(access())
        suspend fun problem(definition: AutomationDefinition) = assertFailsWith<InvalidInput> { save.create("a1", definition) }.problem
        assertEquals(InvalidInput.Problem.AUTOMATION_NAME, problem(cronDefinition("  ")))
        assertEquals(InvalidInput.Problem.AUTOMATION_PROMPT, problem(cronDefinition().copy(prompt = " ")))
        assertEquals(InvalidInput.Problem.CHOOSE_PROFILE, problem(cronDefinition().copy(action = AutomationAction(ActionKind.CLOUD))))
        // The remote action is not offered yet.
        assertEquals(InvalidInput.Problem.CHOOSE_PROFILE, problem(cronDefinition().copy(action = AutomationAction(ActionKind.REMOTE, "p1"))))
        assertEquals(InvalidInput.Problem.AUTOMATION_CRON, problem(cronDefinition().copy(trigger = AutomationTrigger(TriggerKind.CRON, "0 21 * *", "UTC"))))
        assertEquals(InvalidInput.Problem.AUTOMATION_TIMEZONE, problem(cronDefinition().copy(trigger = AutomationTrigger(TriggerKind.CRON, "0 21 * * *", " "))))
        assertEquals(InvalidInput.Problem.AUTOMATION_PAYLOAD, problem(webhookDefinition().copy(samplePayload = "[1]")))
        assertEquals(InvalidInput.Problem.AUTOMATION_VARIABLE, problem(webhookDefinition().copy(variables = listOf(AutomationVariable("x", "ticket")))))
        assertEquals(InvalidInput.Problem.AUTOMATION_LIMITS, problem(cronDefinition().copy(maxRunsPerDay = 0)))
        assertTrue(repository.calls.isEmpty())
    }

    @Test fun aValidDefinitionIsCreated() = runTest {
        val created = SaveAutomationUseCase(access()).create("a1", webhookDefinition())
        assertEquals("whsec_new", created.secret)
        assertEquals(listOf("create"), repository.calls)
    }

    @Test fun anUpdateReplacesTheVersionItRead() = runTest {
        SaveAutomationUseCase(access()).update("a1", automation(version = 4), cronDefinition())
        assertEquals(listOf("update:au1:4"), repository.calls)
    }

    @Test fun onlyTheOwnerChangesRotatesOrTests() = runTest {
        val others = automation(ownerId = "m2", definition = webhookDefinition())
        val admin = access("ADMIN")
        assertEquals(InvalidInput.Problem.AUTOMATION_NOT_OWNER, assertFailsWith<InvalidInput> { SaveAutomationUseCase(admin).update("a1", others, cronDefinition()) }.problem)
        assertEquals(InvalidInput.Problem.AUTOMATION_NOT_OWNER, assertFailsWith<InvalidInput> { RotateAutomationSecretUseCase(admin)("a1", others) }.problem)
        assertEquals(InvalidInput.Problem.AUTOMATION_NOT_OWNER, assertFailsWith<InvalidInput> { TestAutomationUseCase(admin)("a1", others, "{}", "k") }.problem)
        assertEquals(InvalidInput.Problem.AUTOMATION_NOT_OWNER, assertFailsWith<InvalidInput> { SetAutomationEnabledUseCase(admin)("a1", AutomationOwned("au1", "m2"), true) }.problem)
        assertTrue(repository.calls.isEmpty())
    }

    @Test fun anAdministratorPausesPreviewsAndDeletesTheTeamsAutomations() = runTest {
        val admin = access("ADMIN")
        SetAutomationEnabledUseCase(admin)("a1", AutomationOwned("au1", "m2"), false)
        PreviewAutomationUseCase(admin)("a1", automation(ownerId = "m2"), "{}")
        DeleteAutomationUseCase(admin)("a1", "au1")
        assertEquals(listOf("disable:au1", "preview:au1", "delete:au1"), repository.calls)
    }

    @Test fun aWebhookTestNeedsAJsonObjectAndACronOneSendsNone() = runTest {
        val test = TestAutomationUseCase(access())
        assertFailsWith<InvalidInput> { test("a1", automation(definition = webhookDefinition()), "not json", "k") }
        assertFailsWith<InvalidInput> { test("a1", automation(definition = webhookDefinition()), "[1]", "k") }
        test("a1", automation(definition = webhookDefinition()), """{"ticket":{"number":735}}""", "k1")
        test("a1", automation(), "ignored", "k2")
        assertEquals(listOf("au1" to "k1", "au1" to "k2"), repository.tested.map { it.first to it.third })
        assertNotNull(repository.tested[0].second)
        assertNull(repository.tested[1].second)
    }

    @Test fun hidingTestsAsksForTheOtherSourcesOnly() = runTest {
        val load = LoadAutomationRunsUseCase(access())
        assertEquals(2, load("a1", "au1", hideTests = false).runs.size)
        assertEquals(listOf("r1"), load("a1", "au1", hideTests = true).runs.map { it.id })
    }

    @Test fun theWebhookUrlFollowsTheChosenProofAndVersion() {
        val hook = AutomationWebhook("/api/v1/automations/au1/trigger", "/api/v1/automations/au1/v/3/trigger", 1)
        assertEquals(WebhookLink("https://t.example/api/v1/automations/au1/trigger", false), webhookLink("https://t.example/", hook, WebhookProof.SIGNATURE, WebhookVersion.LATEST, "whsec_x"))
        assertEquals(WebhookLink("https://t.example/api/v1/automations/au1/v/3/trigger", false), webhookLink("https://t.example", hook, WebhookProof.SIGNATURE, WebhookVersion.PINNED, null))
        assertEquals(WebhookLink("https://t.example/api/v1/automations/au1/trigger/whsec_x", true), webhookLink("https://t.example", hook, WebhookProof.SECRET_URL, WebhookVersion.LATEST, "whsec_x"))
        // The secret is never read again: without it the URL ends with the placeholder.
        assertEquals(WebhookLink("https://t.example/api/v1/automations/au1/v/3/trigger/<secret>", false), webhookLink("https://t.example", hook, WebhookProof.SECRET_URL, WebhookVersion.PINNED, null))
    }

    @Test fun timesAreWrittenInUtc() {
        assertEquals("1970-01-01 00:00 UTC", me.nathanfallet.teamcode.companion.presentation.screens.formatUtc(0))
        assertEquals("2026-10-28 21:00 UTC", me.nathanfallet.teamcode.companion.presentation.screens.formatUtc(1_793_221_200_000L))
        assertEquals("1969-12-31 23:59 UTC", me.nathanfallet.teamcode.companion.presentation.screens.formatUtc(-60_000L))
    }
}
