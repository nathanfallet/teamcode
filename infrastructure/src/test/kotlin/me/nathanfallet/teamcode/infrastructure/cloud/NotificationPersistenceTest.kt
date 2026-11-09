package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.cloud.CloudCreateOutcome
import me.nathanfallet.teamcode.domain.cloud.CloudSessionDraft
import me.nathanfallet.teamcode.domain.notifications.*
import me.nathanfallet.teamcode.domain.team.TeamEventType
import me.nathanfallet.teamcode.domain.waits.WaitDefinition
import me.nathanfallet.teamcode.domain.waits.WaitService
import me.nathanfallet.teamcode.infrastructure.database.SqlNotificationStore
import me.nathanfallet.teamcode.infrastructure.database.SqlWaitStore
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import kotlin.test.*

/** Notifications on real MySQL, with two pools standing for two replicas running the worker together. */
class NotificationPersistenceTest {
    private class Channel(override val id: String, private val reach: () -> Set<String>) : NotificationChannel {
        val sent = java.util.Collections.synchronizedList(mutableListOf<Notification>())
        var result: ChannelResult = ChannelResult.DELIVERED
        override suspend fun reachable(teamId: String, memberIds: Set<String>) = memberIds intersect reach()
        override suspend fun deliver(notifications: List<Notification>) = notifications.associate { sent += it; it.id to result }
    }

    @Test @Timeout(240)
    fun notificationsHoldAcrossReplicas() = runBlocking {
        AutomationWorld.run(83) { w ->
            w.a.database.query {
                assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(NotificationCursors, NotificationDeliveries, MemberNotificationSettings, withLogs = false),
                    "The reviewed migrations match the Exposed tables")
            }
            val storeA = SqlNotificationStore(w.a.database); val storeB = SqlNotificationStore(w.b.database)
            val waits = WaitService(SqlWaitStore(w.a.database))
            val member = w.member.member.id
            val channel = Channel("push") { setOf(member) }
            val a = DeliverNotificationsUseCase(storeA, listOf(channel)); val b = DeliverNotificationsUseCase(storeB, listOf(channel))

            // What happened before notifications existed stays unsent: the first pass only places the cursor at the head.
            val session = (w.a.sessions.create(w.member, null, CloudSessionDraft("Fix the login", w.profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
            waits.create(w.member, WaitDefinition(session.id, "Before notifications", assigneeId = member), null)
            assertEquals(emptyList(), storeA.behind(100))
            assertEquals(0, a.run() + b.run())
            assertEquals(emptyList(), channel.sent)

            // A wait for the member: read with its title and session name, queued once although both replicas fan out together.
            val wait = waits.create(w.member, WaitDefinition(session.id, "Review the plan", assigneeId = member), null).wait
            val cursor = storeA.behind(100).single()
            val page = storeA.read(cursor, 10)
            val source = page.sources.single { it.event.type == TeamEventType.WAIT_CREATED }
            assertEquals(WaitSummary(wait.id, member, session.id, "human_request", "human", true, "Review the plan"), source.wait)
            assertEquals("Fix the login", source.sessionName)
            assertEquals(cursor.head, page.through)
            listOf(async { a.fanOut() }, async { b.fanOut() }).awaitAll()
            val queued = w.a.database.query { NotificationDeliveries.selectAll().where { NotificationDeliveries.waitId eq wait.id }.toList() }
            assertEquals(1, queued.size, "one delivery per event, member and channel")
            assertEquals("Review the plan", queued.single()[NotificationDeliveries.body])
            assertEquals(emptyList(), storeA.behind(100), "the cursor reached the head")

            // Claimed by one replica only; delivered and recorded.
            listOf(async { a.run() }, async { b.run() }).awaitAll()
            assertEquals(listOf(wait.id), channel.sent.map { it.waitId })
            assertEquals("delivered", w.a.database.query { NotificationDeliveries.select(NotificationDeliveries.state).where { NotificationDeliveries.waitId eq wait.id }.single()[NotificationDeliveries.state] })

            // A switch turned off is honoured at the next fan-out; the other kinds stay on.
            NotificationService(storeA, listOf("push")).set(w.member, "push", "waiting", false)
            assertEquals(setOf(Triple(member, "push", NotificationKind.WAITING)), storeA.disabled(setOf(member, w.admin.member.id)))
            assertEquals(false, storeA.settings(w.teamId, member).single().enabled)
            waits.create(w.member, WaitDefinition(session.id, "Muted", assigneeId = member), null)
            a.run()
            assertEquals(1, channel.sent.size)
            NotificationService(storeA, listOf("push")).set(w.member, "push", "waiting", true)

            // A retryable failure comes back after its backoff, not before; a lease keeps the other replica off it.
            channel.result = ChannelResult.retry("unavailable")
            val retried = waits.create(w.member, WaitDefinition(session.id, "Flaky", assigneeId = member), null).wait
            a.run()
            val row = w.a.database.query { NotificationDeliveries.selectAll().where { NotificationDeliveries.waitId eq retried.id }.single() }
            assertEquals("pending", row[NotificationDeliveries.state]); assertEquals(1, row[NotificationDeliveries.attempts]); assertEquals("unavailable", row[NotificationDeliveries.lastError])
            assertEquals(emptyList(), storeB.claimDue(10, 60_000), "not due before its backoff")
            w.a.database.query { NotificationDeliveries.update({ NotificationDeliveries.id eq row[NotificationDeliveries.id] }) { it[nextAttemptAt] = 0 } }
            assertEquals(1, storeA.claimDue(10, 60_000).size)
            assertEquals(emptyList(), storeB.claimDue(10, 60_000), "leased by the other replica")

            // Old deliveries are given up, then deleted.
            w.a.database.query { NotificationDeliveries.update { it[createdAt] = 0; it[leaseUntil] = 0 } }
            assertTrue(storeA.maintain(100) > 0)
            assertEquals(0L, w.a.database.query { NotificationDeliveries.selectAll().count() })
        }
    }
}
