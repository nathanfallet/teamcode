package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.Database
import me.nathanfallet.teamcode.infrastructure.database.SqlCloudRuntimeStore
import me.nathanfallet.teamcode.infrastructure.database.SqlTeamService
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** The stored cloud runtime on real MySQL with two replicas: seeded once from the environment, then the database decides. */
class CloudRuntimePersistenceTest {
    @Test fun theDatabaseIsTheSourceOfTruthAfterTheFirstSeed(): Unit = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                coroutineScope { listOf(a, b).map { async(Dispatchers.IO) { it.migrate() } }.awaitAll() }
                a.query {
                    val drift = MigrationUtils.statementsRequiredForDatabaseMigration(CloudRuntimeTable, withLogs = false)
                    assertEquals(emptyList(), drift, "The reviewed migration matches the Exposed table")
                }
                val origin = "https://teamcode.example.test"
                val ra = CloudRuntimeService(SqlCloudRuntimeStore(a), origin)
                val rb = CloudRuntimeService(SqlCloudRuntimeStore(b), origin)

                // Nothing stored and no former variable: disabled.
                assertEquals(CloudRuntime(), ra.seed(null))
                assertNull(a.query { CloudRuntimeTable.selectAll().singleOrNull() }, "No variable writes nothing")

                // Both replicas start with the former variables: one seed is stored, once.
                val fromEnvironment = CloudRuntime(enabled = true, runnerImage = "registry.example.test/runner@sha256:abc", workspaceAccessMode = "ReadWriteOnce")
                val seeded = coroutineScope { listOf(ra, rb).map { async(Dispatchers.IO) { it.seed(fromEnvironment) } }.awaitAll() }
                assertEquals(seeded[0], seeded[1])
                assertTrue(seeded[0].enabled && seeded[0].updatedAt != null)
                assertEquals(1, a.query { AuditEvents.selectAll().where { AuditEvents.action eq "cloud.runtime.seeded" }.count() })

                // The administrator turns cloud sessions off; a later restart with the variable still set changes nothing.
                val admin = admin(a)
                ra.save(admin, ra.get(admin).copy(enabled = false, serverUrl = "https://teamcode.internal"))
                assertFalse(ra.enabled)
                assertTrue(rb.enabled, "The other replica keeps its cached value until it rereads")
                assertFalse(rb.refresh().enabled, "and sees the change on its next refresh, without a restart")
                assertFalse(rb.seed(fromEnvironment).enabled, "A former variable never overrides the stored runtime")
                assertEquals("https://teamcode.internal", rb.serverUrl())
                assertEquals("cloud.runtime.updated", a.query { AuditEvents.selectAll().where { AuditEvents.action eq "cloud.runtime.updated" }.single()[AuditEvents.action] })

                // An invalid seed is stored disabled so the server still starts.
                a.query { CloudRuntimeTable.deleteAll() }
                val warnings = mutableListOf<String>()
                assertFalse(ra.seed(fromEnvironment.copy(serverUrl = "http://teamcode.internal"), warnings::add).enabled)
                assertEquals(1, warnings.size)

                assertFailsWith<TeamFailure> { ra.get(member(a, admin)) }
            } }
        }
    }

    private suspend fun admin(a: Database): Principal {
        val team = SqlTeamService(a)
        val password = team.bootstrap()!!
        team.changePassword(team.login("admin", password, "test").principal, password, "runtime admin replacement password")
        return team.login("admin", "runtime admin replacement password", "test").principal
    }

    private suspend fun member(a: Database, admin: Principal): Principal {
        val team = SqlTeamService(a)
        val created = team.createMember(admin, "runtime-member", Role.MEMBER)
        team.changePassword(team.login("runtime-member", created.temporaryPassword, "test").principal, created.temporaryPassword, "runtime member replacement password")
        return team.login("runtime-member", "runtime member replacement password", "test").principal
    }
}
