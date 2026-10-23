package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.hooks.*
import me.nathanfallet.teamcode.domain.skills.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** The hook catalog against real MySQL and two independent replicas. */
class HookPersistenceTest {
    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
    private fun files(body: String) = listOf(SkillFile("bin/guard.sh", SkillFileMode.EXECUTABLE, "#!/bin/sh\necho $body\n".encodeToByteArray()))
    private val stop = HookDefinition(setOf(HookEvent.STOP, HookEvent.SUBAGENT_STOP), null, "bin/guard.sh", 30)

    @Test fun onlyAnAdministratorPinsAndOnlyAllowedMembersReceiveTheExactVersion() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                a.query {
                    val drift = MigrationUtils.statementsRequiredForDatabaseMigration(Hooks, HookVersions, HookFiles, HookAssignments, withLogs = false)
                    assertEquals(emptyList(), drift, "The reviewed migration matches the Exposed tables")
                }
                val teamA = SqlTeamService(a); val teamB = SqlTeamService(b)
                val initial = teamA.bootstrap()!!
                teamA.changePassword(teamA.login("admin", initial, "fixture").principal, initial, "hook administrator password")
                val admin = teamB.login("admin", "hook administrator password", "fixture").principal
                val created = teamA.createMember(admin, "hook-member", Role.MEMBER)
                teamA.changePassword(teamB.login("hook-member", created.temporaryPassword, "fixture").principal, created.temporaryPassword, "hook member password")
                val member = teamB.login("hook-member", "hook member password", "fixture").principal
                val other = teamA.createMember(admin, "hook-other", Role.MEMBER).member

                val catalogA = HookCatalog(SqlHookStore(a)); val catalogB = HookCatalog(SqlHookStore(b))
                val keys = SqlServiceKeyService(a)
                failure("invalid_service_key") { keys.create(admin, member.member.id, "member hooks", setOf(ServiceScope.HOOKS_PUBLISH), 1) }
                val publisher = teamB.authenticate(keys.create(admin, admin.member.id, "hooks CI", setOf(ServiceScope.HOOKS_PUBLISH), 1).accessToken)!!
                val skills = teamB.authenticate(keys.create(admin, admin.member.id, "skills CI", setOf(ServiceScope.SKILLS_PUBLISH), 1).accessToken)!!
                val reader = teamB.authenticate(keys.create(admin, member.member.id, "member hooks reader", setOf(ServiceScope.HOOKS_READ), 1).accessToken)!!
                failure("credential_scope") { catalogA.publish(skills, "stop-guard", "1", stop, files("one")) }
                failure("forbidden") { catalogA.publish(member, "stop-guard", "1", stop, files("one")) }
                assertTrue(a.query { Hooks.selectAll().empty() })

                val v1 = catalogA.publish(publisher, "stop-guard", "1", stop, files("one"))
                assertTrue(v1.created); assertNull(v1.hook.pinnedVersion)
                assertEquals(stop, v1.version.definition)
                assertFalse(catalogB.publish(publisher, "stop-guard", "1", stop, files("one")).created, "CI retries are idempotent")
                failure("hook_version_conflict") { catalogB.publish(publisher, "stop-guard", "1", stop.copy(timeoutSeconds = 31), files("one")) }
                val v2 = catalogB.publish(admin, "stop-guard", "2", stop.copy(matcher = "manual|auto", events = setOf(HookEvent.PRE_COMPACT)), files("two"))

                // An administrator reads an unpinned version's code; a publishing key cannot.
                assertEquals("#!/bin/sh\necho two\n", catalogB.manage.read(admin, "stop-guard", "2").second.single().content.decodeToString())
                failure("credential_scope") { catalogB.manage.read(publisher, "stop-guard", "2") }

                // A publishing key never delivers: it cannot pin, and nothing is served before an administrator does.
                failure("credential_scope") { catalogA.store.pin(publisher, "stop-guard", "1") }
                failure("forbidden") { catalogA.store.pin(member, "stop-guard", "1") }
                catalogA.store.assign(admin, "stop-guard", member.member.id, SkillDecision.ALLOW)
                catalogA.store.assign(admin, "stop-guard", other.id, SkillDecision.DENY)
                assertEquals(emptyList(), catalogB.effective(member))
                catalogB.store.pin(admin, "stop-guard", "1")
                assertEquals(listOf("1"), catalogA.effective(reader).map { it.version })
                assertEquals(emptyList(), catalogA.effective(admin, other.id))
                val first = catalogA.download(reader, "stop-guard", "1")
                assertEquals(v1.version.digest, first.hook.digest)
                assertEquals(stop, first.hook.definition)
                assertEquals(SkillFileMode.EXECUTABLE, first.files.single().mode)
                failure("not_found") { catalogA.download(member, "stop-guard", "2") }

                // Update and rollback are pins; unpin and clearing the decision both stop delivery.
                catalogA.store.pin(admin, "stop-guard", "2")
                assertEquals(v2.version.definition, catalogB.effective(member).single().definition)
                catalogB.store.pin(admin, "stop-guard", "1")
                assertEquals(first.entries, catalogA.download(member, "stop-guard", "1").entries)
                catalogA.store.unpin(admin, "stop-guard")
                assertEquals(emptyList(), catalogB.effective(member))
                catalogA.store.pin(admin, "stop-guard", "1")
                catalogB.store.assign(admin, "stop-guard", member.member.id, null)
                assertEquals(emptyList(), catalogA.effective(member))

                // The publishing key is otherwise powerless and its revocation is shared.
                failure("credential_scope") { catalogB.store.catalog(publisher) }
                failure("credential_scope") { catalogB.store.assign(publisher, "stop-guard", member.member.id, SkillDecision.ALLOW) }
                keys.revoke(admin, publisher.tokenId)
                failure("authentication_required") { catalogB.publish(publisher, "stop-guard", "3", stop, files("three")) }

                // Audit holds identifiers only; the key's publication names it; pins are interactive.
                val events = a.query {
                    AuditEvents.select(AuditEvents.action, AuditEvents.subjectId, AuditEvents.credentialId).where { AuditEvents.action like "hook.%" }
                        .map { Triple(it[AuditEvents.action], it[AuditEvents.subjectId], it[AuditEvents.credentialId]) }
                }
                assertTrue(events.map { it.first }.containsAll(listOf("hook.created", "hook.version.published", "hook.pinned", "hook.unpinned",
                    "hook.access.allowed", "hook.access.denied", "hook.access.cleared")))
                assertEquals(2, events.count { it.first == "hook.version.published" }, "Idempotent and refused publications append nothing")
                assertEquals(publisher.tokenId, events.single { it.first == "hook.version.published" && it.second == v1.version.id }.third)
                assertTrue(events.filter { it.first == "hook.pinned" }.all { it.third == null })
                assertTrue(events.all { it.second.length == 36 })
            } }
        }
    }

    @Test fun aHookForAllMembersRunsForLaterMembersOnlyAfterAnAdministratorPinsIt() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                val teamA = SqlTeamService(a); val teamB = SqlTeamService(b)
                val initial = teamA.bootstrap()!!
                teamA.changePassword(teamA.login("admin", initial, "fixture").principal, initial, "hook administrator password")
                val admin = teamB.login("admin", "hook administrator password", "fixture").principal
                val early = teamA.createMember(admin, "early-member", Role.MEMBER).member
                val publisher = teamB.authenticate(SqlServiceKeyService(a).create(admin, admin.member.id, "hooks CI", setOf(ServiceScope.HOOKS_PUBLISH), 1).accessToken)!!
                val catalogA = HookCatalog(SqlHookStore(a)); val catalogB = HookCatalog(SqlHookStore(b))

                // A key may propose the audience of a hook nobody approved yet: nothing runs until an administrator pins.
                assertTrue(catalogA.publish(publisher, "stop-guard", "1", stop, files("one"), allMembers = true).hook.allMembers)
                assertEquals(emptyList(), catalogB.effective(admin, early.id))
                catalogB.store.pin(admin, "stop-guard", "1")
                assertEquals(listOf("stop-guard"), catalogA.effective(admin, early.id).map { it.name })
                val late = teamB.createMember(admin, "late-member", Role.MEMBER).member
                assertEquals(listOf("stop-guard"), catalogA.effective(admin, late.id).map { it.name }, "Members who join later receive it")
                catalogA.store.assign(admin, "stop-guard", late.id, SkillDecision.DENY)
                assertEquals(emptyList(), catalogB.effective(admin, late.id))

                // Once pinned, only an administrator widens it: the key's publication is refused whole, nothing stored.
                catalogB.manage.setAudience(admin, "stop-guard", false)
                assertEquals(emptyList(), catalogA.effective(admin, early.id))
                failure("hook_audience_requires_administrator") { catalogA.publish(publisher, "stop-guard", "2", stop, files("two"), allMembers = true) }
                failure("hook_audience_requires_administrator") { catalogB.publish(publisher, "stop-guard", "1", stop, files("one"), allMembers = true) }
                assertEquals(listOf("1"), catalogA.store.versions(admin, "stop-guard").second.map { it.version })
                assertTrue(catalogA.publish(publisher, "stop-guard", "2", stop, files("two")).created, "Without the flag the key still publishes")
                assertFalse(catalogB.store.versions(admin, "stop-guard").first.allMembers)
                assertTrue(catalogB.publish(admin, "stop-guard", "3", stop, files("three"), allMembers = true).hook.allMembers, "An administrator may")
                assertFalse(catalogA.publish(publisher, "stop-guard", "3", stop, files("three"), allMembers = true).created, "Already all members: a retry passes")
                failure("credential_scope") { catalogA.manage.setAudience(publisher, "stop-guard", false) }

                val events = a.query {
                    AuditEvents.select(AuditEvents.action, AuditEvents.credentialId)
                        .where { AuditEvents.action inList listOf("hook.audience.all_members", "hook.audience.assigned_only") }
                        .orderBy(AuditEvents.id to SortOrder.ASC).map { it[AuditEvents.action] to it[AuditEvents.credentialId] }
                }
                assertEquals(listOf("hook.audience.all_members" to publisher.tokenId, "hook.audience.assigned_only" to null, "hook.audience.all_members" to null), events)
            } }
        }
    }
}
