package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class GroupPersistenceTest {
    @Test @Timeout(120)
    fun scopedManagementAndOriginalGroupChargesSurviveMembershipChanges() = runBlocking {
        fixture { f ->
            val admin = f.admin; val dev = f.dev; val support = f.support
            val groupsA = GroupServiceImpl(f.a); val groupsB = GroupServiceImpl(f.b)
            val a = groupsA.create(admin, GroupPolicy("Developers", 1000, 1)).summary.group.id
            val b = groupsB.create(admin, GroupPolicy("Project", 200, 2)).summary.group.id
            val c = groupsB.create(admin, GroupPolicy("New project", 120, 1)).summary.group.id
            groupsA.assign(admin, a, dev.member.id, GroupRole.MANAGER)
            groupsB.assign(admin, a, support.member.id, GroupRole.MEMBER)
            groupsB.assign(admin, b, dev.member.id, GroupRole.MEMBER)
            assertEquals(setOf(a, b), groupsB.list(dev).map { it.group.id }.toSet())
            assertEquals(2, groupsB.get(dev, a).members.size)
            assertEquals(listOf(support.member.id), groupsB.get(support, a).members.map { it.memberId })
            refusal("not_found") { groupsB.get(support, b) }
            refusal("not_found") { groupsB.pause(dev, c, support.member.id, true) }
            refusal("forbidden") { groupsB.update(dev, a, GroupPolicy("Escalated", 999999)) }
            refusal("forbidden") { groupsB.assign(dev, a, support.member.id, GroupRole.MANAGER) }
            refusal("forbidden") { groupsB.remove(dev, a, support.member.id) }
            refusal("forbidden") { groupsB.pause(support, a, dev.member.id, true) }
            val key = SqlServiceKeyService(f.a).create(admin, admin.member.id, "Group denial", ServiceScope.entries.toSet(), 1)
            val keyActor = assertNotNull(SqlTeamService(f.b).authenticate(key.accessToken))
            refusal("credential_scope") { groupsB.list(keyActor) }
            refusal("credential_scope") { groupsB.pause(keyActor, a, support.member.id, true) }

            val first = f.admissionA.reserve(dev, 150, "claude-test")
            refusal("group_concurrency") { f.admissionB.reserve(support, 1, "claude-test") }
            f.admissionB.settle(first, 80, true)
            groupsB.remove(admin, a, dev.member.id)
            groupsB.assign(admin, c, dev.member.id, GroupRole.MEMBER)
            assertEquals(80, groupsB.get(admin, a).summary.usage.chargedAndReservedUnits)
            assertEquals(0, groupsA.get(admin, c).summary.usage.chargedAndReservedUnits)
            assertEquals(80, f.admissionB.capacity(dev).chargedAndReservedUnits)
            refusal("group_allowance") { f.admissionB.reserve(dev, 121, "claude-test") }
            val second = f.admissionB.reserve(dev, 100, "claude-test")
            assertEquals(180, groupsA.get(admin, b).summary.usage.chargedAndReservedUnits)
            f.admissionA.settle(second, 0)
            assertEquals(80, groupsB.get(admin, b).summary.usage.chargedAndReservedUnits)
            val uncertain = f.admissionA.reserve(support, 100, "claude-test")
            f.admissionB.settle(uncertain, null)
            assertEquals(GroupUsage(180, 0), groupsB.get(admin, a).summary.usage)
            groupsA.assign(admin, a, dev.member.id, GroupRole.MANAGER)
            groupsB.update(admin, a, GroupPolicy("Developers", 180, 1))
            refusal("group_allowance") { f.admissionB.reserve(dev, 1, "claude-test") }
            groupsA.update(admin, a, GroupPolicy("Developers", 180, 1, archived = true))
            refusal("not_found") { groupsB.get(dev, a) }
            refusal("group_archived") { groupsB.assign(admin, a, support.member.id, GroupRole.MEMBER) }
            assertEquals(setOf(b, c), f.admissionB.capacity(dev).groups.map { it.group.id }.toSet())
            val third = f.admissionB.reserve(dev, 1, "claude-test"); f.admissionB.settle(third, 0)
            groupsB.update(admin, a, GroupPolicy("Developers", 180, 1))
            refusal("group_allowance") { f.admissionA.reserve(dev, 1, "claude-test") }
            assertEquals(180, groupsA.get(admin, a).summary.usage.chargedAndReservedUnits)
            groupsA.assign(admin, a, dev.member.id, GroupRole.MEMBER)
            refusal("forbidden") { groupsB.pause(dev, a, support.member.id, true) }
            val actions = SqlAuditService(f.b).list(admin).events
            assertTrue(actions.any { it.action == "group.member_removed" && it.subjectId == "$a/${dev.member.id}" })
            SqlTeamService(f.a).logout(admin)
            refusal("authentication_required") { groupsB.update(admin, a, GroupPolicy("Revoked")) }
        }
    }

    @Test @Timeout(120)
    fun simultaneousReplicasAndPausedQueuedWorkCannotExceedGroupLimits() = runBlocking {
        fixture { f ->
            val groupsA = GroupServiceImpl(f.a); val groupsB = GroupServiceImpl(f.b)
            val id = groupsA.create(f.admin, GroupPolicy("Shared", 1000, 1)).summary.group.id
            groupsA.assign(f.admin, id, f.dev.member.id, GroupRole.MANAGER)
            groupsA.assign(f.admin, id, f.support.member.id, GroupRole.MEMBER)
            val observation = SqlPacingSettingsStore(f.a)
            observation.save(f.admin, PacingSettings(true))
            suspend fun race(): List<Pair<Reservation?, String?>> = coroutineScope {
                val start = CompletableDeferred<Unit>()
                val jobs = listOf(f.dev to f.admissionA, f.support to f.admissionB).map { (actor, admission) -> async {
                    start.await()
                    try { admission.reserve(actor, 80, "claude-test") to null } catch (error: TeamFailure) { null to error.code }
                } }
                start.complete(Unit); jobs.awaitAll()
            }
            val first = race()
            assertEquals(1, first.count { it.first != null }); assertEquals(listOf("group_concurrency"), first.mapNotNull { it.second })
            assertEquals(GroupUsage(80, 1), groupsB.get(f.admin, id).summary.usage)
            f.admissionA.settle(first.mapNotNull { it.first }.single(), 0)
            val active = f.admissionA.reserve(f.dev, 20, "claude-test")
            coroutineScope {
                val waiting = async {
                    try { AwaitAdmissionUseCase(f.admissionB).reserve(f.support, AdmissionInput(10, "claude-test", "anthropic")); "admitted" }
                    catch (error: TeamFailure) { error.code }
                }
                withTimeout(5000) {
                    while (f.b.query { AdmissionWaiters.selectAll().where { AdmissionWaiters.memberId eq f.support.member.id }.empty() }) delay(25)
                }
                groupsA.pause(f.dev, id, f.support.member.id, true)
                assertEquals("group_paused", withTimeout(5000) { waiting.await() })
            }
            assertEquals(GroupUsage(20, 1), groupsB.get(f.admin, id).summary.usage, "Pausing never cancels or refunds active work")
            assertEquals(0, f.admissionB.capacity(f.support).queuedRequests)
            SqlDirectiveService(f.b).update()
            assertEquals(Pace.PAUSE, SqlDirectiveService(f.a).preview(f.support)!!.advice.pace)
            assertNull(SqlDirectiveService(f.b).current(f.support), "Observation suppresses advice but not group limits")
            groupsB.pause(f.dev, id, f.support.member.id, false)
            // Expiring concurrency must retain the conservative unknown-usage charge.
            f.a.query { Reservations.update({ Reservations.id eq active.id }) { it[expiresAt] = nowMillis() - 1 } }
            assertEquals(GroupUsage(20, 0), groupsB.get(f.admin, id).summary.usage)
            groupsB.update(f.admin, id, GroupPolicy("Shared", 100, 0))
            f.admissionB.settle(active, 20)
            val second = race()
            assertEquals(1, second.count { it.first != null }); assertEquals(listOf("group_allowance"), second.mapNotNull { it.second })
            assertEquals(100, groupsA.get(f.admin, id).summary.usage.chargedAndReservedUnits)
            f.admissionA.settle(second.mapNotNull { it.first }.single(), 60)
            assertEquals(80, groupsB.get(f.admin, id).summary.usage.chargedAndReservedUnits)
            assertTrue(f.admissionB.capacity(f.dev).dailyAllowanceUnits > 100, "Group cap never replaces member weights")
        }
    }

    private suspend fun refusal(code: String, block: suspend () -> Any?) { assertEquals(code, assertFailsWith<TeamFailure> { block() }.code) }
    private class Fixture(val a: Database, val b: Database, val admin: Principal, val dev: Principal, val support: Principal) {
        val admissionA = SqlAdmissionStore(a); val admissionB = SqlAdmissionStore(b)
    }
    private suspend fun fixture(test: suspend (Fixture) -> Unit) {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
                Database(container.jdbcUrl, container.username, container.password).use { b ->
                    a.migrate(); b.migrate()
                    val team = SqlTeamService(a)
                    val initial = assertNotNull(team.bootstrap())
                    val pending = team.login("admin", initial, "groups-test")
                    team.changePassword(pending.principal, initial, "group admin replacement password")
                    val admin = team.login("admin", "group admin replacement password", "groups-test").principal
                    suspend fun member(name: String): Principal {
                        val created = team.createMember(admin, name, Role.MEMBER)
                        val pendingMember = team.login(name, created.temporaryPassword, "groups-test")
                        team.changePassword(pendingMember.principal, created.temporaryPassword, "group member replacement password")
                        return team.login(name, "group member replacement password", "groups-test").principal
                    }
                    val dev = member("developer"); val support = member("support")
                    val store = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 29 }))
                    val enrolled = store.upsert(AccountMutationActor.System,
                        VerifiedProviderAccount(AccountIdentity("default", "anthropic", "synthetic-group-account", "synthetic-org"), "Fixture"),
                        ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "group-fixture") as EnrollmentResult.Created
                    store.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
                    test(Fixture(a, b, admin, dev, support))
                }
            }
        }
    }
}
