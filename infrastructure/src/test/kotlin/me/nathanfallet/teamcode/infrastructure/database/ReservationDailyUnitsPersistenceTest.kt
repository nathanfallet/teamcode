package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** The daily totals admission reads equal the sums over reservations: after the backfill and after every write. */
class ReservationDailyUnitsPersistenceTest {
    @Test @Timeout(180)
    fun backfillAndEveryChargeKeepTheDailyTotalsEqualToTheReservations() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            // Upgrade path: reservations written before the totals existed, including lent and grouped ones.
            Flyway.configure().dataSource(container.jdbcUrl, container.username, container.password)
                .locations("classpath:db/migration").target("20261025130042").load().migrate()
            Database(container.jdbcUrl, container.username, container.password).use { db ->
                val actor = seedLegacyAdministrator(db, "daily totals replacement password").principal
                val member = actor.member.id
                val accounts = SqlAccountStore(db, CredentialCipher(ByteArray(32) { 73 }))
                val account = (accounts.upsert(AccountMutationActor.System, VerifiedProviderAccount(AccountIdentity("default", "anthropic", "synthetic-daily-account", "synthetic-org"), "Fixture"),
                    ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "daily-fixture") as EnrollmentResult.Created).account.id
                accounts.handover(AccountMutationActor.System, "default", account, accessOnly = true)
                val group = UUID.randomUUID().toString()
                val today = db.query { nowMillis() } / DAY
                db.query {
                    TeamGroups.insert { it[id] = group; it[teamId] = "default"; it[name] = "Legacy group"; it[dailyUnits] = 100_000 }
                    // Home pool, a pool lent to by "reserve", a legacy row without a source pool, and an older day.
                    legacy(member, account, today, 30, "", "", group)
                    legacy(member, account, today, 20, "home", "reserve", null)
                    legacy(member, account, today, 7, "home", null, null)
                    legacy(member, account, today - 1, 11, "", "", group)
                }
                db.migrate()
                db.query {
                    assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(Reservations, ReservationDailyUnits, withLogs = false),
                        "The reviewed migration matches the Exposed tables")
                }
                assertTotalsMatch(db)
                assertEquals(57, db.query { readDailyUnits("default", today, DailyScope.MEMBER, listOf(member)) }[member])
                assertEquals(mapOf("" to 30L, "home" to 27L, "reserve" to 20L), db.query { poolSpends("default", setOf("", "home", "reserve"), today) })

                val admission = SqlAdmissionStore(db)
                GroupServiceImpl(db).assign(actor, group, member, GroupRole.MEMBER)
                val settled = admission.reserve(actor, AdmissionInput(100, "claude-test", "anthropic"))
                assertTotalsMatch(db)
                admission.settle(settled, 40, true)
                assertTotalsMatch(db)
                admission.settle(settled, 90, true) // A late duplicate settlement changes nothing.
                assertTotalsMatch(db)
                val uncertain = admission.reserve(actor, AdmissionInput(50, "claude-test", "anthropic"))
                admission.settle(uncertain, null)
                assertTotalsMatch(db)
                UsageReconciliationServiceImpl(db).reconcile(actor, uncertain.id, UsageReconciliationChange(50, 5, UsageEvidence.PROVIDER_RECORD,
                    "Synthetic provider record", "Verified terminal usage", null))
                assertTotalsMatch(db)
                assertEquals(57 + 40 + 5, admission.capacity(actor).chargedAndReservedUnits)
                assertEquals(30 + 40 + 5, GroupServiceImpl(db).get(actor, group).summary.usage.chargedAndReservedUnits)
            }
        }
    }

    private fun legacy(member: String, account: String, day: Long, units: Long, pool: String, source: String?, group: String?) {
        val id = UUID.randomUUID().toString()
        Reservations.insert {
            it[Reservations.id] = id; it[teamId] = "default"; it[memberId] = member; it[accountId] = account; it[dayNumber] = day
            it[Reservations.units] = units; it[state] = "SETTLED"; it[expiresAt] = day * DAY; it[createdAt] = day * DAY; it[poolId] = pool; it[sourcePoolId] = source
        }
        if (group != null) ReservationGroups.insert { it[reservationId] = id; it[groupId] = group; it[dayNumber] = day; it[expiresAt] = day * DAY }
    }

    /** Recomputes every total from the reservations, the way admission used to, and compares it with the stored ones. */
    private suspend fun assertTotalsMatch(db: Database) = db.query {
        val units = Reservations.units.sum()
        val expected = mutableMapOf<List<Any>, Long>()
        fun add(day: Long, scope: String, id: String, value: Long?) { expected.merge(listOf(day, scope, id), value ?: 0, Long::plus) }
        Reservations.select(Reservations.dayNumber, Reservations.memberId, units).groupBy(Reservations.dayNumber, Reservations.memberId)
            .forEach { add(it[Reservations.dayNumber], DailyScope.MEMBER, it[Reservations.memberId], it[units]) }
        Reservations.select(Reservations.dayNumber, Reservations.poolId, units).groupBy(Reservations.dayNumber, Reservations.poolId)
            .forEach { add(it[Reservations.dayNumber], DailyScope.POOL, it[Reservations.poolId], it[units]) }
        Reservations.select(Reservations.dayNumber, Reservations.sourcePoolId, units).where { Reservations.sourcePoolId neq Reservations.poolId }
            .groupBy(Reservations.dayNumber, Reservations.sourcePoolId).forEach { add(it[Reservations.dayNumber], DailyScope.LENT, it[Reservations.sourcePoolId]!!, it[units]) }
        (ReservationGroups innerJoin Reservations).select(ReservationGroups.dayNumber, ReservationGroups.groupId, units).groupBy(ReservationGroups.dayNumber, ReservationGroups.groupId)
            .forEach { add(it[ReservationGroups.dayNumber], DailyScope.GROUP, it[ReservationGroups.groupId], it[units]) }
        val t = ReservationDailyUnits
        val stored = t.selectAll().associate { listOf<Any>(it[t.dayNumber], it[t.scope], it[t.scopeId]) to it[t.units] }
        assertEquals(expected.filterValues { it != 0L }, stored.filterValues { it != 0L })
    }

    companion object { private const val DAY = 86_400_000L }
}
