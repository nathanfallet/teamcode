package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.automations.*
import me.nathanfallet.teamcode.domain.cloud.CloudSettings
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.infrastructure.database.SqlServiceKeyService
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import kotlin.test.*

/** Automations and their runs on real MySQL, with two pools standing for two replicas. */
class AutomationPersistenceTest {
    @Test @Timeout(240)
    fun definitionsCredentialsAndRunsHoldAcrossReplicas() = runBlocking {
        AutomationWorld.run(61) { w ->
            w.a.database.query {
                assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(Automations, AutomationRuns, CloudSessions, CloudSettingsTable, withLogs = false),
                    "The reviewed migrations match the Exposed tables")
            }
            val owner = ownerCredentialIsHiddenAndRevokedWithTheOwner(w)
            webhookRunsAreDeduplicatedAndBounded(w, owner)
            aCronSlotIsRecordedOnceByTwoReplicas(w, owner)
            queuedRunsAreClaimedByOneReplica(w)
            historyPagesRetentionAndDeletion(w, owner)
        }
    }

    /** Returns the owner's new login: their password changed. */
    private suspend fun ownerCredentialIsHiddenAndRevokedWithTheOwner(w: AutomationWorld): Principal {
        w.a.admin.saveSettings(w.admin, CloudSettings(maxAutomationsPerMember = 2))
        val created = w.a.automations.create(w.member, w.webhook())
        w.a.automations.create(w.member, w.cron())
        refusedWith("automation_limit") { w.b.automations.create(w.member, w.cron()) }
        val credential = w.a.database.query { Automations.select(Automations.credentialId).where { Automations.id eq created.automation.id }.single()[Automations.credentialId] }
        assertNotNull(credential, "Creating it records the owner credential")
        assertTrue(SqlServiceKeyService(w.a.database).list(w.member, w.member.member.id).none { it.id == credential }, "Never listed among service keys")
        val sealed = w.a.database.query { Automations.select(Automations.secret).where { Automations.id eq created.automation.id }.single()[Automations.secret]!! }
        assertFalse(sealed.contains(created.secret!!), "The secret is sealed at rest")

        val owner = assertNotNull(w.a.store.ownerPrincipal(created.automation.id))
        assertEquals(w.member.member.id, owner.member.id); assertEquals(credential, owner.tokenId)
        // The owner's own password change revokes their tokens, the automation credential included.
        w.team.changePassword(w.member, "automation owner replacement password", "automation owner second password")
        assertNull(w.b.store.ownerPrincipal(created.automation.id))
        val member = w.team.login("automation-owner", "automation owner second password", "test").principal
        val enabled = w.a.automations.enable(member, created.automation.id)
        assertTrue(enabled.enabled)
        assertNotEquals(credential, assertNotNull(w.a.store.ownerPrincipal(created.automation.id)).tokenId, "Enabling again records a new credential")
        w.a.admin.saveSettings(w.admin, CloudSettings())
        return member
    }

    private suspend fun webhookRunsAreDeduplicatedAndBounded(w: AutomationWorld, owner: Principal) {
        val created = w.a.automations.create(owner, w.webhook(maxRunsPerDay = 3))
        val id = created.automation.id; val secret = created.secret!!
        w.a.automations.enable(owner, id)
        // The same delivery key on two replicas at once records one run.
        val both = coroutineScope { listOf(w.a, w.b).map { r -> async(Dispatchers.IO) { r.automations.trigger(w.signed(id, secret, """{"ticket":735}""", "d1")) } }.awaitAll() }
        assertEquals(1, both.count { it is RunOutcome.Created }); assertEquals(1, both.map { it.run.id }.distinct().size)
        val prompt = w.a.database.query { AutomationRuns.select(AutomationRuns.prompt).where { AutomationRuns.id eq both.first().run.id }.single()[AutomationRuns.prompt] }
        assertEquals("Fix ticket 735", prompt, "The rendered prompt waits in the run until its session exists")
        // Invalid payloads are recorded, counted as failures, and inside the budget.
        refusedWith("invalid_payload") { w.b.automations.trigger(w.signed(id, secret, """{"ticket":"x"}""", "d2")) }
        assertEquals(1, w.a.store.find(w.teamId, id)!!.consecutiveFailures)
        w.a.automations.trigger(w.signed(id, secret, """{"ticket":736}""", "d3"))
        refusedWith("automation_budget") { w.b.automations.trigger(w.signed(id, secret, """{"ticket":737}""", "d4")) }
        assertEquals(RunStatus.SKIPPED_BUDGET, w.a.store.runs(w.teamId, id, RunQuery()).runs.first().status)
        assertEquals("budget", w.a.store.wouldSkip(id))
        // A test is outside the budget; its Idempotency-Key replays it, and a different payload under it conflicts.
        val test = w.a.automations.test(owner, id, buildJsonObject { put("ticket", JsonPrimitive(1)) }, false, "t1") as TestOutcome.Run
        assertIs<RunOutcome.Created>(test.outcome); assertEquals(RunStatus.QUEUED, test.outcome.run.status)
        assertIs<RunOutcome.Replayed>((w.b.automations.test(owner, id, buildJsonObject { put("ticket", JsonPrimitive(1)) }, false, "t1") as TestOutcome.Run).outcome)
        refusedWith("idempotency_conflict") { w.b.automations.test(owner, id, buildJsonObject { put("ticket", JsonPrimitive(2)) }, false, "t1") }

        // Overlap `skip` on a fresh automation: a second trigger while one is queued is skipped.
        val single = w.a.automations.create(owner, w.webhook(overlap = OverlapPolicy.SKIP))
        w.a.automations.enable(owner, single.automation.id)
        w.a.automations.trigger(w.signed(single.automation.id, single.secret!!, """{"ticket":1}""", "s1"))
        assertEquals(RunStatus.SKIPPED_OVERLAP, w.b.automations.trigger(w.signed(single.automation.id, single.secret!!, """{"ticket":2}""", "s2")).run.status)
        assertEquals("overlap", w.a.store.wouldSkip(single.automation.id))
        w.a.automations.delete(owner, single.automation.id)
    }

    private suspend fun aCronSlotIsRecordedOnceByTwoReplicas(w: AutomationWorld, owner: Principal) {
        val cron = w.a.automations.create(owner, w.cron("*/5 * * * *"))
        w.a.automations.enable(owner, cron.automation.id)
        // The latest slot fell due less than five minutes ago: both replicas' schedulers run at once.
        val now = w.a.store.now()
        val slot = assertNotNull(CronSchedule.parse("*/5 * * * *", "Europe/Paris", now).latest(now, now - 5 * 60_000L))
        w.a.database.query { Automations.update({ Automations.id eq cron.automation.id }) { it[nextRunAt] = slot } }
        val recorded = coroutineScope { listOf(w.a, w.b).map { r -> async(Dispatchers.IO) { r.scheduler.run() } }.awaitAll() }
        assertEquals(1, recorded.sum())
        val runs = w.a.store.runs(w.teamId, cron.automation.id, RunQuery()).runs
        assertEquals(1, runs.size); assertEquals(RunSource.CRON, runs.single().source); assertEquals(slot, runs.single().scheduledFor)
        val next = assertNotNull(w.a.store.find(w.teamId, cron.automation.id)!!.nextRunAt)
        assertTrue(next > w.a.store.now(), "Moved to the next slot")
        assertFalse(w.b.store.advance(cron.automation.id, runs.single().scheduledFor!!, null, null), "A replica that read the old slot cannot move it")
    }

    private suspend fun queuedRunsAreClaimedByOneReplica(w: AutomationWorld) {
        val claimed = coroutineScope { listOf(w.a, w.b).map { r -> async(Dispatchers.IO) { r.store.claimQueued(50, 60_000) } }.awaitAll() }
        val ids = claimed.flatten().map { it.run.id }
        assertEquals(ids.distinct(), ids, "No run is claimed twice")
        assertTrue(ids.isNotEmpty())
        assertEquals(emptyList(), w.a.store.claimQueued(50, 60_000), "Leased")
        val first = claimed.flatten().first()
        assertNotNull(first.prompt)
        w.a.store.retry(first.run.id, 0)
        assertEquals(listOf(first.run.id), w.b.store.claimQueued(50, 60_000).map { it.run.id }, "A retry makes it due again")
        // Ending a cron or webhook run moves the consecutive failures; a test never does.
        val before = w.a.store.find(w.teamId, first.automation.id)!!.consecutiveFailures
        w.a.store.finish(first.run.id, RunStatus.TIMED_OUT, "max_runtime")
        w.b.store.finish(first.run.id, RunStatus.SUCCEEDED, null)
        assertEquals(RunStatus.TIMED_OUT, w.a.store.run(w.teamId, first.automation.id, first.run.id)!!.status, "An ended run stays ended")
        val expected = if (first.run.source == RunSource.TEST) before else before + 1
        assertEquals(expected, w.a.store.find(w.teamId, first.automation.id)!!.consecutiveFailures)
        assertNull(w.a.database.query { AutomationRuns.select(AutomationRuns.prompt).where { AutomationRuns.id eq first.run.id }.single()[AutomationRuns.prompt] }, "The prompt is cleared at the end")
    }

    private suspend fun historyPagesRetentionAndDeletion(w: AutomationWorld, owner: Principal) {
        val hook = w.a.store.list(w.teamId, owner.member.id, null, 100).automations.first { it.definition.trigger.kind == TriggerKind.WEBHOOK && it.definition.maxRunsPerDay == 3 }
        val all = w.a.store.runs(w.teamId, hook.id, RunQuery(limit = 100)).runs
        val first = w.b.store.runs(w.teamId, hook.id, RunQuery(limit = 2))
        val rest = w.b.store.runs(w.teamId, hook.id, RunQuery(before = first.nextBefore, limit = 100))
        assertEquals(all.map { it.id }, (first.runs + rest.runs).map { it.id }, "Pages follow each other newest first")
        assertEquals(all.filter { it.status == RunStatus.INVALID_PAYLOAD }.map { it.id },
            w.a.store.runs(w.teamId, hook.id, RunQuery(statuses = setOf(RunStatus.INVALID_PAYLOAD))).runs.map { it.id })
        assertNotNull(hook.lastRun, "The list shows the latest run")
        // Runs ended more than 14 days ago are purged, except one whose session is still kept (it is released later, then purged).
        val kept = all.first { it.status.final }.id
        w.a.database.query {
            AutomationRuns.update({ (AutomationRuns.automationId eq hook.id) and AutomationRuns.endedAt.isNotNull() }) { it[endedAt] = 1 }
            AutomationRuns.update({ AutomationRuns.id eq kept }) { it[sessionReleased] = false }
        }
        val ended = all.count { it.status.final }
        assertEquals(ended - 1, w.a.store.purge(500))
        assertNotNull(w.a.store.run(w.teamId, hook.id, kept), "Its session is not released yet")
        // Deleting an automation deletes its runs and its credential.
        val credential = w.a.database.query { Automations.select(Automations.credentialId).where { Automations.id eq hook.id }.single()[Automations.credentialId]!! }
        w.b.automations.delete(w.admin, hook.id)
        w.a.database.query {
            assertTrue(AutomationRuns.select(AutomationRuns.id).where { AutomationRuns.automationId eq hook.id }.empty())
            assertTrue(Tokens.select(Tokens.id).where { Tokens.id eq credential }.empty())
            assertEquals("automation.deleted", AuditEvents.select(AuditEvents.action).where { AuditEvents.subjectId eq hook.id }.orderBy(AuditEvents.id to SortOrder.DESC).first()[AuditEvents.action])
        }
    }
}
