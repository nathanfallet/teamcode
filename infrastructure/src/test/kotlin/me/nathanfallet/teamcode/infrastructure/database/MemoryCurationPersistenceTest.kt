package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.memory.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** The curation on real MySQL and two replicas: off by default, never mixing scopes, newest wins when safe, a person decides otherwise, restorable. */
class MemoryCurationPersistenceTest {
    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
    private fun note(title: String, content: String = "Details.", scope: MemoryScope = MemoryScope.PERSONAL, tags: List<String> = emptyList()) = NewMemory(scope, title, "About $title", content, tags)

    @Test fun curationSupersedesTheOlderOfTwoEqualEntriesAndQueuesWhatItCannotDecide() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                a.query { assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(Memories, MemoryTags, MemorySettings, MemoryCurationState, MemoryReviews, withLogs = false), "The reviewed migration matches the Exposed tables") }
                val teamA = SqlTeamService(a); val teamB = SqlTeamService(b)
                val initial = teamA.bootstrap()!!
                teamA.changePassword(teamA.login("admin", initial, "fixture").principal, initial, "curation administrator password")
                val admin = teamB.login("admin", "curation administrator password", "fixture").principal
                suspend fun member(name: String): Principal {
                    val created = teamA.createMember(admin, name, Role.MEMBER)
                    teamA.changePassword(teamB.login(name, created.temporaryPassword, "fixture").principal, created.temporaryPassword, "$name curation password")
                    return teamB.login(name, "$name curation password", "fixture").principal
                }
                val vivien = member("vivien"); val chiheb = member("chiheb")
                val keys = SqlServiceKeyService(a)
                val agentKey = teamB.authenticate(keys.create(admin, vivien.member.id, "vivien agent", setOf(ServiceScope.MEMORY), 1).accessToken)!!
                val memoryA = MemoryService(SqlMemoryStore(a)); val memoryB = MemoryService(SqlMemoryStore(b))
                val curationA = MemoryCurationService(SqlMemoryCurationStore(a)); val curationB = MemoryCurationService(SqlMemoryCurationStore(b))

                // Off until an administrator turns it on: a run does nothing, and only an administrator, signed in, changes it.
                val dup1 = memoryA.create(vivien, note("Deploy needs a VPN", "Connect to the VPN first."))
                val dup2 = memoryB.create(vivien, note("deploy  needs a vpn", "connect to the   VPN first."))
                assertEquals(0, curationA.run("run-0").scopes)
                assertFalse(curationA.settings(admin).enabled)
                failure("forbidden") { curationA.setEnabled(vivien, true) }
                failure("forbidden") { curationA.settings(vivien) }
                failure("memory_team_forbidden") { curationA.setEnabled(agentKey, true) }
                assertTrue(curationB.setEnabled(admin, true).enabled)

                // Two equal personal entries: the newest wins, the older is kept as superseded, and only its owner can see that.
                val report = curationA.run("run-1")
                assertEquals(1, report.superseded)
                assertEquals(listOf(dup2.id), memoryB.index(vivien).entries.map { it.id })
                assertEquals(listOf(dup1.id), curationB.superseded(vivien).map { it.id })
                assertEquals(dup2.id, curationB.superseded(vivien).single().supersededById)
                assertTrue(curationB.superseded(admin).isEmpty() && curationB.superseded(chiheb).isEmpty(), "nobody else sees a personal superseded entry")
                failure("not_found") { curationB.restore(admin, dup1.id) }
                failure("not_found") { curationB.restore(chiheb, dup1.id) }
                assertEquals(0, curationB.run("run-2").superseded, "nothing changed since: the scope is not read again")
                a.query { assertTrue(AuditEvents.select(AuditEvents.details).where { (AuditEvents.action eq "memory.superseded") and (AuditEvents.subjectId eq dup1.id) }.single()[AuditEvents.details]!!.contains("\"run\":\"run-1\"")) }

                // The same text in two members' personal memories is never compared: scopes are never mixed.
                memoryA.create(chiheb, note("Deploy needs a VPN", "Connect to the VPN first."))
                memoryA.create(admin, note("Deploy needs a VPN", "Connect to the VPN first."))
                assertEquals(0, curationA.run("run-3").superseded)
                assertEquals(1, memoryA.index(chiheb).entries.size)

                // Restoring: the entry comes back, and the one that replaced it is superseded in turn.
                assertEquals(MemoryStatus.ACTIVE, curationA.restore(vivien, dup1.id).status)
                assertEquals(listOf(dup1.id), memoryB.index(vivien).entries.map { it.id })
                assertEquals(listOf(dup2.id), curationB.superseded(vivien).map { it.id })
                a.query { assertEquals(1L, AuditEvents.selectAll().where { (AuditEvents.action eq "memory.restored") and (AuditEvents.subjectId eq dup1.id) }.count()) }
                failure("not_found") { curationA.restore(vivien, dup1.id) }

                // Same title, different text: the model-free pass cannot tell an update from a contradiction, so a person is asked.
                val old = memoryA.create(vivien, note("Staging URL", "It is staging.example.com."))
                val updated = memoryB.create(vivien, note("Staging URL", "It moved to stg.example.com."))
                assertEquals(1, curationA.run("run-4").queued)
                val review = curationB.reviews(vivien).single()
                assertEquals(ReviewKind.SAME_TITLE, review.kind); assertEquals(old.id, review.olderId); assertEquals(updated.id, review.newerId)
                assertTrue(curationB.reviews(admin).isEmpty() && curationB.reviews(chiheb).isEmpty(), "a personal review is its owner's")
                assertEquals(0, curationA.run("run-5").queued, "a pair is queued once")
                failure("not_found") { curationB.resolve(admin, review.id, true) }
                assertEquals(old.id, curationB.resolve(vivien, review.id, true).olderId)
                assertTrue(memoryA.index(vivien).entries.none { it.id == old.id })
                failure("memory_review_closed") { curationA.resolve(vivien, review.id, false) }
                // Dismissed: both stay and the pair is never queued again.
                val keepA = memoryA.create(vivien, note("Editor", "Vim.")); memoryA.create(vivien, note("Editor", "Emacs."))
                curationA.run("run-6")
                val dismissal = curationA.reviews(vivien).single()
                curationA.resolve(vivien, dismissal.id, false)
                a.query { MemoryCurationState.deleteWhere { MemoryCurationState.teamId eq admin.teamId } }
                assertEquals(0, curationB.run("run-7").queued)
                assertTrue(memoryA.index(vivien).entries.any { it.id == keepA.id })

                // A person's older entry and an agent's newer one, or anything tagged external, is queued, never superseded.
                val human = memoryA.create(vivien, note("Branch naming", "Use feat/ prefixes."))
                val agent = memoryB.create(agentKey, note("Branch naming", "Use feat/ prefixes."))
                assertEquals(MemoryOrigin.AGENT, agent.origin)
                val team = memoryA.create(vivien, note("Release freeze", "No Friday releases.", MemoryScope.TEAM, listOf("external")))
                memoryA.create(vivien, note("Release freeze", "No Friday releases.", MemoryScope.TEAM))
                val run = curationA.run("run-8")
                assertEquals(0, run.superseded); assertEquals(2, run.queued)
                assertEquals(setOf(human.id), curationA.reviews(vivien).filter { it.scope == MemoryScope.PERSONAL && it.olderId == human.id }.map { it.olderId }.toSet())
                val teamReview = curationB.reviews(admin).single { it.scope == MemoryScope.TEAM }
                assertEquals(team.id, teamReview.olderId)
                assertTrue(curationB.reviews(vivien).none { it.scope == MemoryScope.TEAM }, "the team's reviews are the administrators'")

                // Retention: expired entries go after a week, superseded ones after 90 days.
                val day = 86_400_000L; val now = System.currentTimeMillis()
                val soon = memoryA.create(vivien, NewMemory(MemoryScope.PERSONAL, "Short lived", "S", "C", expiresAt = now + 60_000))
                a.query {
                    Memories.update({ Memories.id eq soon.id }) { it[expiresAt] = now - 8 * day }
                    Memories.update({ Memories.id eq dup2.id }) { it[updatedAt] = now - 91 * day }
                    MemoryCurationState.deleteWhere { MemoryCurationState.teamId eq admin.teamId }
                }
                assertEquals(1, curationA.run("run-9").expired)
                a.query {
                    assertEquals(0L, Memories.selectAll().where { Memories.id eq soon.id }.count())
                    assertEquals(0L, Memories.selectAll().where { Memories.id eq dup2.id }.count())
                    assertEquals(setOf("memory.created", "memory.expired"), AuditEvents.select(AuditEvents.action).where { AuditEvents.subjectId eq soon.id }.map { it[AuditEvents.action] }.toSet())
                }
                // Turned off again: nothing more is read.
                curationA.setEnabled(admin, false)
                memoryA.create(vivien, note("Late", "Same.")); memoryA.create(vivien, note("Late", "Same."))
                assertEquals(0, curationB.run("run-11").scopes)
            } }
        }
    }
}
