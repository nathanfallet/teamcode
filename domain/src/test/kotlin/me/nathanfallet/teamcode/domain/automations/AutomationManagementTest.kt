package me.nathanfallet.teamcode.domain.automations

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.automations.Fixtures.admin
import me.nathanfallet.teamcode.domain.automations.Fixtures.ada
import me.nathanfallet.teamcode.domain.automations.Fixtures.bob
import me.nathanfallet.teamcode.domain.automations.Fixtures.principal
import me.nathanfallet.teamcode.domain.cloud.CloudSettings
import me.nathanfallet.teamcode.domain.team.ServiceScope
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class AutomationManagementTest {
    @Test fun anAutomationIsCreatedDisabledAndAWebhookOneGetsItsSecretOnce() = runBlocking {
        val world = Fixtures.World()
        val cron = world.service.create(principal(ada), Fixtures.cron)
        assertFalse(cron.automation.enabled); assertNull(cron.secret); assertNull(cron.automation.nextRunAt)
        val hook = world.service.create(principal(ada), Fixtures.webhook)
        assertTrue(hook.secret!!.startsWith("whsec_"))
        assertEquals("sealed:automation:${hook.automation.id}:${hook.secret}", world.store.secrets.getValue(hook.automation.id).current)
        assertFalse(hook.toString().contains(hook.secret!!))
    }

    @Test fun creatingAndArmingStayInteractive() = runBlocking {
        val world = Fixtures.World()
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { world.service.create(principal(ada, setOf(ServiceScope.CLOUD_WRITE)), Fixtures.cron) }.code)
        val id = world.service.create(principal(ada), Fixtures.cron).automation.id
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { world.service.enable(principal(ada, setOf(ServiceScope.ADMIN_CLOUD)), id) }.code)
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { world.service.rotateSecret(principal(ada, setOf(ServiceScope.CLOUD_WRITE)), id) }.code)
        assertEquals(id, world.service.get(principal(ada, setOf(ServiceScope.CLOUD_READ)), id).id, "a cloud:read key reads it")
    }

    @Test fun enablingComputesTheNextSlot() = runBlocking {
        val world = Fixtures.World()
        val id = world.service.create(principal(ada), Fixtures.cron).automation.id
        val enabled = world.service.enable(principal(ada), id)
        assertTrue(enabled.enabled)
        assertEquals(CronSchedule.parse("0 21 * * *", "Europe/Paris").next(world.store.clock), enabled.nextRunAt)
        assertNull(world.service.disable(principal(ada), id).nextRunAt)
    }

    @Test fun anAdministratorReadsPausesAndDeletesButNeverArmsOrEdits() = runBlocking {
        val world = Fixtures.World()
        val id = world.service.create(principal(ada), Fixtures.cron).automation.id
        assertEquals("not_found", assertFailsWith<TeamFailure> { world.service.get(principal(bob), id) }.code, "another member does not see it")
        assertEquals("forbidden", assertFailsWith<TeamFailure> { world.service.list(principal(bob), "ada", null, 50) }.code)
        assertEquals(1, world.service.list(principal(admin), AutomationService.ALL, null, 50).automations.size)
        assertEquals("automation_owner_only", assertFailsWith<TeamFailure> { world.service.enable(principal(admin), id) }.code)
        assertEquals("automation_owner_only", assertFailsWith<TeamFailure> { world.service.update(principal(admin), id, 1, Fixtures.cron.copy(name = "Changed")) }.code)
        assertEquals("admin", world.service.disable(principal(admin), id).disabledBy)
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { world.service.disable(principal(admin, setOf(ServiceScope.TEAM_READ)), id) }.code, "team:read never writes")
        world.service.delete(principal(admin), id)
        assertTrue(world.store.automations.isEmpty())
    }

    @Test fun aRemoteActionIsRefusedAsNotAvailableYet() = runBlocking {
        val world = Fixtures.World()
        val remote = Fixtures.webhook.copy(action = AutomationAction(ActionKind.REMOTE, null))
        assertEquals("not_available_yet", assertFailsWith<TeamFailure> { world.service.create(principal(ada), remote) }.code)
    }

    @Test fun theProfileMustBeAssignedToTheOwner() = runBlocking {
        val world = Fixtures.World()
        assertEquals("profile_unassigned", assertFailsWith<TeamFailure> { world.service.create(principal(bob), Fixtures.cron) }.code)
        assertEquals("profile_not_found", assertFailsWith<TeamFailure> { world.service.create(principal(ada), Fixtures.cron.copy(action = AutomationAction(ActionKind.CLOUD, "p9"))) }.code)
        world.service.create(principal(admin), Fixtures.cron)
        Unit
    }

    @Test fun updatesAreVersionedAndKeepTheTriggerKind() = runBlocking {
        val world = Fixtures.World()
        val id = world.service.create(principal(ada), Fixtures.cron).automation.id
        assertEquals(2, world.service.update(principal(ada), id, 1, Fixtures.cron.copy(name = "Renamed")).version)
        assertEquals("stale_version", assertFailsWith<TeamFailure> { world.service.update(principal(ada), id, 1, Fixtures.cron) }.code)
        assertEquals("invalid_automation", assertFailsWith<TeamFailure> { world.service.update(principal(ada), id, 2, Fixtures.webhook) }.code)
        assertEquals(2, world.service.enable(principal(ada), id).version, "arming does not change the version")
    }

    @Test fun theTeamCapsTheAutomationsOfAMember() = runBlocking {
        val world = Fixtures.World()
        world.directory.settings = CloudSettings(maxAutomationsPerMember = 1)
        world.service.create(principal(ada), Fixtures.cron)
        assertEquals("automation_limit", assertFailsWith<TeamFailure> { world.service.create(principal(ada), Fixtures.cron) }.code)
    }

    @Test fun aRotationKeepsTheFormerSecretForTenMinutes() = runBlocking {
        val world = Fixtures.World()
        val created = world.service.create(principal(ada), Fixtures.webhook)
        val rotated = world.service.rotateSecret(principal(ada), created.automation.id)
        assertNotNull(rotated.secret)
        assertEquals(world.store.clock + AutomationRules.PREVIOUS_SECRET_GRACE_MILLIS, rotated.automation.previousSecretValidUntil)
        assertEquals("not_webhook", assertFailsWith<TeamFailure> { world.service.rotateSecret(principal(ada), world.service.create(principal(ada), Fixtures.cron).automation.id) }.code)
    }

    @Test fun invalidDefinitionsAreRefusedAtSave() = runBlocking {
        val world = Fixtures.World()
        for (definition in listOf(Fixtures.cron.copy(name = ""), Fixtures.cron.copy(prompt = "Hi {{ticket.number}}"), Fixtures.cron.copy(maxRunsPerDay = 0),
            Fixtures.cron.copy(action = AutomationAction(ActionKind.CLOUD, "p1", maxRuntimeMinutes = 0)), Fixtures.cron.copy(trigger = AutomationTrigger(TriggerKind.CRON, "* * * * *", "UTC")),
            Fixtures.cron.copy(trigger = AutomationTrigger(TriggerKind.CRON, "0 21 * * *", null)), Fixtures.webhook.copy(trigger = AutomationTrigger(TriggerKind.WEBHOOK, "0 21 * * *")),
            Fixtures.webhook.copy(samplePayload = kotlinx.serialization.json.buildJsonObject { put("ticket", kotlinx.serialization.json.buildJsonObject { put("number", kotlinx.serialization.json.JsonPrimitive("abc")) }) })))
            assertEquals("invalid_automation", assertFailsWith<TeamFailure>(definition.toString()) { world.service.create(principal(ada), definition) }.code)
    }
}
