package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** The feed's journal on real MySQL with two pools standing for two replicas. */
class TeamEventPersistenceTest {
    @Test @Timeout(120)
    fun runEventsAreOrderedIsolatedResumableAndStaleOncePerSilence() = runBlocking {
        fixture { f ->
            f.a.query {
                assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(TeamEvents, TeamEventCursors, AgentRuns, withLogs = false), "The reviewed migrations match the Exposed tables")
            }
            val runsA = AgentRunServiceImpl(f.a); val runsB = AgentRunServiceImpl(f.b)
            val store = SqlTeamEventStore(f.b)
            val team = f.member.principal.teamId
            assertEquals(0, store.head(team)); assertNull(store.oldest(team))

            val own = id(); val admins = id()
            runsA.start(f.member.principal, own, "claude", "interactive")
            runsB.start(f.admin.principal, admins, "codex", "background")
            runsB.start(f.member.principal, own, "claude", "interactive") // idempotent: no second event
            runsA.end(f.member.principal, own, 0)
            runsA.end(f.member.principal, own, 0) // idempotent too
            assertEquals(3, store.head(team)); assertEquals(1, store.oldest(team))

            val memberView = TeamEventAudience(f.member.principal.member.id, sessions = true, runs = true, teamSessions = false, teamRuns = false)
            val adminView = memberView.copy(memberId = f.admin.principal.member.id, teamSessions = true, teamRuns = true)
            val mine = store.read(team, memberView, 0, null, 100)
            assertEquals(listOf(TeamEventType.RUN_STARTED, TeamEventType.RUN_ENDED), mine.events.map { it.type }, "A member sees only their own runs")
            assertEquals(listOf(1L, 3L), mine.events.map { it.seq }); assertEquals(3, mine.through, "The cursor moves over events the member cannot see")
            assertEquals(own, mine.events.first().subjectId); assertEquals(0, Json.parseToJsonElement(mine.events.last().data).jsonObject["exitCode"]!!.jsonPrimitive.int)
            assertEquals(listOf(1L, 2L, 3L), store.read(team, adminView, 0, null, 100).events.map { it.seq }, "The team-wide audience sees everyone's")
            assertEquals(listOf(2L), store.read(team, adminView, 1, null, 100).events.map { it.seq }.take(1), "Resuming after an id skips what was seen")
            assertEquals(emptyList(), store.read(team, memberView.copy(runs = false), 0, null, 100).events, "Without the runs right, no run event")
            assertEquals(listOf(TeamEventType.RUN_ENDED), store.read(team, memberView, 0, setOf(TeamEventType.RUN_ENDED), 100).events.map { it.type })
            val paged = store.read(team, adminView, 0, null, 2)
            assertTrue(paged.more); assertEquals(2, paged.through)

            // A silent run is reported once; a heartbeat and a new silence report it again.
            val silent = id()
            runsA.start(f.member.principal, silent, "claude", "interactive")
            val sweep = SqlRunStaleSweep(f.a)
            assertEquals(0, sweep.sweep(), "A fresh run is not stale")
            // `notifiedAgo`: when the previous silence was reported, as if before the heartbeat that ended it.
            suspend fun age(ms: Long, notifiedAgo: Long? = null) = f.a.query {
                val now = nowMillis()
                AgentRuns.update({ AgentRuns.id eq silent }) { it[heartbeatAt] = now - ms; if (notifiedAgo != null) it[staleNotifiedAt] = now - notifiedAgo }
            }
            age(AgentRunService.HEARTBEAT_TTL_MILLIS + 5_000)
            assertEquals(1, sweep.sweep()); assertEquals(0, sweep.sweep(), "Once per silence")
            runsA.heartbeat(f.member.principal, silent)
            assertEquals(0, sweep.sweep())
            age(AgentRunService.HEARTBEAT_TTL_MILLIS + 5_000, notifiedAgo = AgentRunService.HEARTBEAT_TTL_MILLIS + 10_000)
            assertEquals(1, sweep.sweep(), "A new silence after a heartbeat is reported again")
            age(2 * 24 * 3_600_000L)
            f.a.query { AgentRuns.update({ AgentRuns.id eq silent }) { it[staleNotifiedAt] = null } }
            assertEquals(0, sweep.sweep(), "Only the last day is scanned")
            runsA.end(f.member.principal, silent, null)
            assertEquals(listOf(TeamEventType.RUN_STARTED, TeamEventType.RUN_STALE, TeamEventType.RUN_STALE, TeamEventType.RUN_ENDED),
                store.read(team, memberView, 3, null, 100).events.map { it.type })

            // Retention drops old events, and the oldest retained sequence says where the history now starts.
            f.a.query { TeamEvents.update({ (TeamEvents.teamId eq team) and (TeamEvents.seq lessEq 2L) }) { it[at] = 1L } }
            assertEquals(2, store.purge(100)); assertEquals(3, store.oldest(team))
        }
    }

    @Test @Timeout(120)
    fun concurrentPublishersOnTwoReplicasGetDenseSequences() = runBlocking {
        fixture { f ->
            val runs = listOf(AgentRunServiceImpl(f.a), AgentRunServiceImpl(f.b))
            val team = f.member.principal.teamId
            val ids = List(24) { id() }
            withContext(Dispatchers.IO) { ids.mapIndexed { index, run -> async { runs[index % 2].start(f.member.principal, run, "claude", "background") } }.awaitAll() }
            val view = TeamEventAudience(f.member.principal.member.id, sessions = true, runs = true, teamSessions = false, teamRuns = false)
            val events = SqlTeamEventStore(f.a).read(team, view, 0, null, 100).events
            assertEquals((1L..24L).toList(), events.map { it.seq }, "No gap and no duplicate under concurrent writers")
            assertEquals(ids.toSet(), events.map { it.subjectId }.toSet())
        }
    }

    private fun id() = UUID.randomUUID().toString()
    private class Fixture(val a: Database, val b: Database, val admin: LoginSession, val member: LoginSession)
    private suspend fun fixture(test: suspend (Fixture) -> Unit) {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a -> Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate(); val team = SqlTeamService(a); val initial = assertNotNull(team.bootstrap())
                val pending = team.login("admin", initial, "feed-test"); team.changePassword(pending.principal, initial, "feed owner replacement password")
                val admin = team.login("admin", "feed owner replacement password", "feed-test")
                val created = team.createMember(admin.principal, "developer", Role.MEMBER)
                val memberPending = team.login("developer", created.temporaryPassword, "feed-test")
                team.changePassword(memberPending.principal, created.temporaryPassword, "feed member replacement password")
                test(Fixture(a, b, admin, team.login("developer", "feed member replacement password", "feed-test")))
            } }
        }
    }
}
