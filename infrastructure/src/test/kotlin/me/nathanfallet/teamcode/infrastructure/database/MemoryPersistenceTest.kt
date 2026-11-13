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

/** The memory against real MySQL and two replicas: who reads what, pagination, search, rights, expiry and the audit trail. */
class MemoryPersistenceTest {
    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
    private fun entry(title: String, scope: MemoryScope = MemoryScope.PERSONAL, tags: List<String> = emptyList(), content: String = "Details of $title.") =
        NewMemory(scope, title, "About $title", content, tags)

    @Test fun teamEntriesAreSharedPersonalEntriesAreTheirOwnersAndNobodyElsesNotEvenAdministrators() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                a.query { assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(Memories, MemoryTags, withLogs = false), "The reviewed migration matches the Exposed tables") }
                val teamA = SqlTeamService(a); val teamB = SqlTeamService(b)
                val initial = teamA.bootstrap()!!
                teamA.changePassword(teamA.login("admin", initial, "fixture").principal, initial, "memory administrator password")
                val admin = teamB.login("admin", "memory administrator password", "fixture").principal
                suspend fun member(name: String): Principal {
                    val created = teamA.createMember(admin, name, Role.MEMBER)
                    teamA.changePassword(teamB.login(name, created.temporaryPassword, "fixture").principal, created.temporaryPassword, "$name memory password")
                    return teamB.login(name, "$name memory password", "fixture").principal
                }
                val vivien = member("vivien"); val chiheb = member("chiheb")
                val keys = SqlServiceKeyService(a)
                val agent = teamB.authenticate(keys.create(admin, vivien.member.id, "vivien agent", setOf(ServiceScope.MEMORY), 1).accessToken)!!
                val blind = teamB.authenticate(keys.create(admin, vivien.member.id, "no memory", setOf(ServiceScope.INFERENCE), 1).accessToken)!!
                val onA = MemoryService(SqlMemoryStore(a)); val onB = MemoryService(SqlMemoryStore(b))

                // A member writes a team entry; the other member and an administrator read it, on either replica.
                val convention = onA.create(vivien, entry("Exposed only", MemoryScope.TEAM, listOf("database", "kotlin")))
                assertEquals(MemoryOrigin.HUMAN, convention.origin); assertNull(convention.ownerId); assertEquals(listOf("database", "kotlin"), convention.tags)
                assertEquals("Details of Exposed only.", onB.read(chiheb, convention.id).content)
                assertEquals(2, onB.read(admin, convention.id).readCount)

                // A personal entry is its owner's alone: not another member, not an administrator, not by id, index or search.
                val secret = onA.create(vivien, entry("Vivien prefers French", tags = listOf("preferences")))
                assertEquals(vivien.member.id, secret.ownerId)
                for (other in listOf(chiheb, admin)) {
                    failure("not_found") { onB.read(other, secret.id) }
                    failure("not_found") { onB.update(other, secret.id, MemoryUpdate(1, "x", "y", "z")) }
                    failure("not_found") { onB.delete(other, secret.id) }
                    assertTrue(onB.index(other).entries.none { it.id == secret.id })
                    assertTrue(onB.search(other, "french").isEmpty())
                }
                assertEquals(setOf(convention.id, secret.id), onB.index(vivien).entries.map { it.id }.toSet())
                assertEquals(listOf(secret.id), onA.index(vivien, scope = MemoryScope.PERSONAL).entries.map { it.id })
                assertEquals(listOf(convention.id), onA.index(vivien, scope = MemoryScope.TEAM).entries.map { it.id })

                // An agent (a key with memory) acts for its owner, writes personal entries, and is refused the team's.
                val note = onA.create(agent, entry("Deploy needs a VPN"))
                assertEquals(MemoryOrigin.AGENT, note.origin); assertEquals(vivien.member.id, note.ownerId)
                failure("memory_team_forbidden") { onA.create(agent, entry("Agent rule", MemoryScope.TEAM)) }
                failure("memory_team_forbidden") { onA.update(agent, convention.id, MemoryUpdate(1, "t", "s", "c")) }
                failure("memory_team_forbidden") { onA.delete(agent, convention.id) }
                failure("credential_scope") { onA.index(blind) }
                assertTrue(onB.index(chiheb).entries.none { it.id == note.id })

                // Index: newest first, pages by keyset cursor, tags filter, no content.
                repeat(7) { onA.create(vivien, entry("Page entry $it", tags = listOf("paging"))) }
                val first = onA.index(vivien, limit = 4)
                assertEquals(4, first.entries.size); assertNotNull(first.next)
                val second = onB.index(vivien, after = first.next, limit = 4)
                val ids = first.entries.map { it.id } + second.entries.map { it.id }
                assertEquals(ids.size, ids.toSet().size, "no entry on two pages")
                assertEquals(8, ids.size)
                assertEquals(7, onA.index(vivien, tags = listOf("paging")).entries.size)
                assertEquals(emptyList(), onA.index(vivien, tags = listOf("nothing")).entries)

                // Search: every word, in title, summary or tag; never in the content.
                assertEquals(listOf(note.id), onA.search(vivien, "vpn deploy").map { it.id })
                assertEquals(setOf(convention.id), onA.search(chiheb, "exposed").map { it.id }.toSet())
                assertEquals(listOf(convention.id), onA.search(chiheb, "kotlin").map { it.id })
                assertTrue(onA.search(vivien, "Details").isEmpty(), "content is not searched")
                assertTrue(onA.search(vivien, "100%_match").isEmpty())

                // Reading counts; an update needs the version read; a stale one conflicts.
                val read = onA.read(vivien, note.id)
                assertEquals(1, read.readCount); assertEquals(2, onB.read(vivien, note.id).readCount)
                val updated = onA.update(vivien, note.id, MemoryUpdate(1, "Deploy needs a VPN", "Updated", "Use the office VPN.", listOf("infra")))
                assertEquals(2, updated.version); assertEquals(listOf("infra"), updated.tags)
                failure("memory_conflict") { onB.update(vivien, note.id, MemoryUpdate(1, "x", "y", "z")) }
                assertEquals(listOf(note.id), onA.index(vivien, tags = listOf("infra")).entries.map { it.id })

                // The author of a team entry, and an administrator, change or forget it; another member does not.
                failure("memory_team_forbidden") { onA.update(chiheb, convention.id, MemoryUpdate(1, "t", "s", "c")) }
                failure("memory_team_forbidden") { onA.delete(chiheb, convention.id) }
                onA.update(admin, convention.id, MemoryUpdate(1, "Exposed only", "Corrected", "No raw SQL, ever.", listOf("database")))
                onB.delete(vivien, convention.id)
                failure("not_found") { onA.read(chiheb, convention.id) }

                // Expired entries are not listed, searched or read.
                val brief = onA.create(vivien, entry("Brief fact").copy(expiresAt = System.currentTimeMillis() + 1_500))
                assertTrue(onA.index(vivien).entries.any { it.id == brief.id })
                Thread.sleep(2_000)
                assertTrue(onA.index(vivien).entries.none { it.id == brief.id })
                failure("not_found") { onA.read(vivien, brief.id) }

                // Secrets are refused before anything is stored; the audit trail holds ids, never titles or content.
                failure("memory_secret") { onA.create(vivien, entry("Credentials", content = "password = correct-horse-battery")) }
                a.query {
                    val events = AuditEvents.selectAll().where { AuditEvents.action like "memory.%" }.toList()
                    assertTrue(events.any { it[AuditEvents.action] == "memory.created" } && events.any { it[AuditEvents.action] == "memory.forgotten" })
                    assertTrue(events.none { e -> listOfNotNull(e[AuditEvents.details], e[AuditEvents.subjectId]).any { it.contains("French") || it.contains("Exposed") } })
                }
            } }
        }
    }
}
