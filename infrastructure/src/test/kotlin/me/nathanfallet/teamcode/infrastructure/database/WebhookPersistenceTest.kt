package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.webhooks.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** Webhook subscriptions, fan-out and the delivery queue on real MySQL with two pools standing for two replicas. */
class WebhookPersistenceTest {
    @Test @Timeout(180)
    fun fanOutFiltersByTypeStartsAtTheHeadAndNeverDuplicates() = runBlocking {
        fixture { f ->
            f.a.query {
                assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(WebhookSubscriptions, WebhookDeliveries, withLogs = false), "The reviewed migrations match the Exposed tables")
            }
            val runs = AgentRunServiceImpl(f.a); val storeA = SqlWebhookStore(f.a); val storeB = SqlWebhookStore(f.b)
            val before = id()
            runs.start(f.member.principal, before, "claude", "interactive") // before the subscription: never delivered
            val hook = storeA.create(f.admin.principal, draft(f, setOf(TeamEventType.RUN_ENDED)), "sealed-secret")
            assertEquals(1, hook.version); assertEquals(1, hook.fanoutSeq, "Starts at the journal's head")
            val now = id()
            runs.start(f.member.principal, now, "codex", "background"); runs.end(f.member.principal, now, 3); runs.end(f.member.principal, before, 0)
            assertEquals(2, storeA.fanOut(10, 100)); assertEquals(0, storeB.fanOut(10, 100), "Nothing twice, from any replica")
            val deliveries = storeA.deliveries(f.team, hook.id, 50)
            assertEquals(setOf(now, before), deliveries.map { Json.parseToJsonElement(it.payload).jsonObject["data"]!!.jsonObject["subjectId"]!!.jsonPrimitive.content }.toSet(), "Only run.ended")
            val payload = Json.parseToJsonElement(deliveries.first().payload).jsonObject
            assertEquals("run.ended", payload["type"]!!.jsonPrimitive.content); assertEquals(deliveries.first().id, payload["id"]!!.jsonPrimitive.content)
            assertEquals(f.member.principal.member.id, payload["data"]!!.jsonObject["memberId"]!!.jsonPrimitive.content)
            assertTrue(deliveries.all { it.state == DeliveryState.PENDING && it.attempts == 0 && !it.test })
            assertEquals(4, storeA.find(f.team, hook.id)!!.fanoutSeq)

            // A disabled subscription is skipped; re-enabling restarts at the head without replaying the gap.
            val off = storeA.update(f.admin.principal, hook.id, 1, hook.url, hook.types, "", enabled = false)
            assertEquals(2, off.version); assertFalse(off.enabled)
            val gap = id(); runs.start(f.member.principal, gap, "claude", "interactive"); runs.end(f.member.principal, gap, 0)
            assertEquals(0, storeA.fanOut(10, 100))
            val on = storeA.update(f.admin.principal, hook.id, 2, hook.url, hook.types, "", enabled = true)
            assertEquals(6, on.fanoutSeq); assertEquals(0, storeA.fanOut(10, 100)); assertEquals(2, storeA.deliveries(f.team, hook.id, 50).size)
            assertEquals("stale_version", assertFailsWith<TeamFailure> { storeB.update(f.admin.principal, hook.id, 1, hook.url, hook.types, "", true) }.code)
        }
    }

    @Test @Timeout(180)
    fun claimingIsExclusiveAndOutcomesDriveRetriesFailuresAndDisabling() = runBlocking {
        fixture { f ->
            val runs = AgentRunServiceImpl(f.a); val storeA = SqlWebhookStore(f.a); val storeB = SqlWebhookStore(f.b)
            val hook = storeA.create(f.admin.principal, draft(f, setOf(TeamEventType.RUN_STARTED)), "sealed-secret")
            repeat(3) { runs.start(f.member.principal, id(), "claude", "background") }
            storeA.fanOut(10, 100)
            val claimed = storeA.claimDue(2, 60_000)
            assertEquals(2, claimed.size); assertTrue(claimed.all { it.url == hook.url && it.sealedSecret == "sealed-secret" })
            val other = storeB.claimDue(10, 60_000)
            assertEquals(1, other.size, "The other replica gets only what is not leased")
            assertTrue(claimed.map { it.delivery.id }.intersect(other.map { it.delivery.id }.toSet()).isEmpty())
            assertEquals(emptyList(), storeA.claimDue(10, 60_000))

            val one = claimed[0].delivery
            storeA.record(one.id, DeliveryState.PENDING, 1, Long.MAX_VALUE / 2, SendResult(503, null), 1_000)
            val retried = storeA.deliveries(f.team, hook.id, 10).single { it.id == one.id }
            assertEquals(1, retried.attempts); assertEquals(503, retried.lastStatus); assertEquals(DeliveryState.PENDING, retried.state)
            assertEquals(emptyList(), storeA.claimDue(10, 60_000), "Not due before its backoff")
            storeA.record(claimed[1].delivery.id, DeliveryState.DELIVERED, 1, 0, SendResult(200, null), 2_000)
            assertEquals(2_000L, storeA.deliveries(f.team, hook.id, 10).single { it.id == claimed[1].delivery.id }.deliveredAt)
            assertEquals(0, storeA.find(f.team, hook.id)!!.failures)

            // Twenty final failures in a row disable the subscription; a delivery resets the count.
            repeat(WebhookService.DISABLE_AFTER) { storeA.record(other[0].delivery.id, DeliveryState.FAILED, 8, 0, SendResult(null, "timeout"), 3_000) }
            assertEquals(WebhookService.DISABLE_AFTER, storeA.find(f.team, hook.id)!!.failures)
            assertEquals(1, storeA.disableFailing(10)); assertEquals(0, storeA.disableFailing(10))
            val disabled = storeA.find(f.team, hook.id)!!
            assertFalse(disabled.enabled); assertEquals("too_many_failures", disabled.disabledReason)
            val back = storeA.update(f.admin.principal, hook.id, disabled.version, hook.url, hook.types, "", true)
            assertEquals(0, back.failures); assertTrue(back.enabled); assertNull(back.disabledReason)
        }
    }

    @Test @Timeout(180)
    fun managementIsBoundedAuditedAndTestsDoNotCountAsFailures() = runBlocking {
        fixture { f ->
            val store = SqlWebhookStore(f.a)
            val hooks = (1..WebhookService.MAX_PER_TEAM).map { store.create(f.admin.principal, draft(f, setOf(TeamEventType.CLOUD_TURN)), "sealed") }
            assertEquals("webhook_limit", assertFailsWith<TeamFailure> { store.create(f.admin.principal, draft(f, setOf(TeamEventType.CLOUD_TURN)), "sealed") }.code)
            assertEquals(WebhookService.MAX_PER_TEAM, store.list(f.team).size)
            val hook = hooks.first()
            assertNotNull(store.find(f.team, hook.id)); assertNull(store.find("another-team", hook.id))

            val test = store.enqueueTest(f.admin.principal, hook.id, WebhookService.payload("11111111-1111-4111-8111-111111111111", "webhook.test", 1, "{}"))
            assertTrue(test.test); assertNull(test.eventSeq)
            assertEquals(1, store.claimDue(5, 60_000).count { it.delivery.test })
            store.record(test.id, DeliveryState.FAILED, 1, 0, SendResult(500, null), 1)
            assertEquals(0, store.find(f.team, hook.id)!!.failures, "A failed test says nothing about real traffic")
            repeat(2) { store.enqueueTest(f.admin.principal, hook.id, WebhookService.payload(id(), "webhook.test", 1, "{}")) }
            assertEquals("webhook_test_rate", assertFailsWith<TeamFailure> { store.enqueueTest(f.admin.principal, hook.id, WebhookService.payload(id(), "webhook.test", 1, "{}")) }.code)

            val rotated = store.rotateSecret(f.admin.principal, hook.id, "sealed-2")
            assertEquals(2, rotated.version)
            assertEquals("sealed-2", f.a.query { WebhookSubscriptions.select(WebhookSubscriptions.secret).where { WebhookSubscriptions.id eq hook.id }.single()[WebhookSubscriptions.secret] })

            // Old pending deliveries are given up, old finished ones deleted.
            f.a.query { WebhookDeliveries.update({ WebhookDeliveries.state eq DeliveryState.PENDING.wire }) { it[createdAt] = 1L; it[leaseUntil] = 0L } }
            assertTrue(store.maintain(100) >= 2)
            assertTrue(store.deliveries(f.team, hook.id, 50).all { it.state == DeliveryState.FAILED })
            f.a.query { WebhookDeliveries.update({ WebhookDeliveries.id eq test.id }) { it[createdAt] = 1L } }
            store.maintain(100)
            assertTrue(store.deliveries(f.team, hook.id, 50).none { it.id == test.id })

            store.delete(f.admin.principal, hook.id)
            assertNull(store.find(f.team, hook.id)); assertEquals(0, f.a.query { WebhookDeliveries.selectAll().where { WebhookDeliveries.subscriptionId eq hook.id }.count() }, "The log goes with it")
            assertEquals("not_found", assertFailsWith<TeamFailure> { store.delete(f.admin.principal, hook.id) }.code)
            val actions = f.a.query { AuditEvents.selectAll().where { AuditEvents.action like "webhook.%" }.map { it[AuditEvents.action] }.toSet() }
            assertEquals(setOf("webhook.created", "webhook.tested", "webhook.secret_rotated", "webhook.deleted"), actions)
        }
    }

    private fun id() = UUID.randomUUID().toString()
    private fun draft(f: Fixture, types: Set<TeamEventType>) =
        WebhookSubscription(id(), f.team, "https://hooks.example.test/${id()}", types, "", true, null, f.admin.principal.member.id, null, 0, 0, 1, 0, 0)
    private class Fixture(val a: Database, val b: Database, val admin: LoginSession, val member: LoginSession) { val team get() = admin.principal.teamId }
    private suspend fun fixture(test: suspend (Fixture) -> Unit) {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a -> Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate(); val team = SqlTeamService(a); val initial = assertNotNull(team.bootstrap())
                val pending = team.login("admin", initial, "hook-test"); team.changePassword(pending.principal, initial, "hook owner replacement password")
                val admin = team.login("admin", "hook owner replacement password", "hook-test")
                val created = team.createMember(admin.principal, "developer", Role.MEMBER)
                val memberPending = team.login("developer", created.temporaryPassword, "hook-test")
                team.changePassword(memberPending.principal, created.temporaryPassword, "hook member replacement password")
                test(Fixture(a, b, admin, team.login("developer", "hook member replacement password", "hook-test")))
            } }
        }
    }
}
