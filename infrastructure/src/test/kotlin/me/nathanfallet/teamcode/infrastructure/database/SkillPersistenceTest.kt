package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.skills.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** Roadmap acceptance on the server side, against real MySQL and two independent replicas. */
class SkillPersistenceTest {
    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
    private fun files(body: String, extra: ByteArray? = null) = listOfNotNull(
        SkillFile("SKILL.md", SkillFileMode.FILE, "---\nname: review\n---\n$body".encodeToByteArray()),
        SkillFile("scripts/check.sh", SkillFileMode.EXECUTABLE, "#!/bin/sh\necho $body\n".encodeToByteArray()),
        extra?.let { SkillFile("assets/data.bin", SkillFileMode.FILE, it) },
    )

    @Test fun pinnedVersionsAreServedReproduciblyAndOnlyToAllowedMembersAcrossReplicas() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                val teamA = SqlTeamService(a); val teamB = SqlTeamService(b)
                val initial = teamA.bootstrap()!!
                teamA.changePassword(teamA.login("admin", initial, "fixture").principal, initial, "skill administrator password")
                val admin = teamB.login("admin", "skill administrator password", "fixture").principal
                val created = teamA.createMember(admin, "skill-member", Role.MEMBER)
                teamA.changePassword(teamB.login("skill-member", created.temporaryPassword, "fixture").principal, created.temporaryPassword, "skill member password")
                val installation = UUID.randomUUID().toString()
                val member = teamB.login("skill-member", "skill member password", "Member laptop", installation).principal
                val other = teamA.createMember(admin, "skill-other", Role.MEMBER).member

                val catalogA = SkillCatalog(SqlSkillStore(a)); val catalogB = SkillCatalog(SqlSkillStore(b))
                val keys = SqlServiceKeyService(a)
                // Publication permission: a dedicated scope, valid only on administrator-owned keys.
                failure("invalid_service_key") { keys.create(admin, member.member.id, "member publisher", setOf(ServiceScope.SKILLS_PUBLISH), 1) }
                val publisher = teamB.authenticate(keys.create(admin, admin.member.id, "skills CI", setOf(ServiceScope.SKILLS_PUBLISH), 1).accessToken)!!
                val inference = teamB.authenticate(keys.create(admin, admin.member.id, "inference CI", setOf(ServiceScope.INFERENCE), 1).accessToken)!!
                failure("credential_scope") { catalogA.publish(inference, "review", "1.0.0", files("one")) }
                failure("forbidden") { catalogA.publish(member, "review", "1.0.0", files("one")) }
                assertTrue(a.query { Skills.selectAll().empty() })

                // A file above the 64 KiB BLOB limit round-trips intact.
                val large = ByteArray(1_048_576) { (it % 251).toByte() }
                val v1 = catalogA.publish(publisher, "review", "1.0.0", files("one", large))
                assertTrue(v1.created); assertNull(v1.skill.pinnedVersion)
                assertFalse(catalogB.publish(publisher, "review", "1.0.0", files("one", large)).created, "CI retries are idempotent")
                failure("skill_version_conflict") { catalogB.publish(publisher, "review", "1.0.0", files("changed")) }
                val v2 = catalogB.publish(admin, "review", "2.0.0", files("two"))
                assertNotEquals(v1.version.digest, v2.version.digest)

                // Nothing is served before an administrator pins a version and allows the member.
                assertEquals(emptyList(), catalogB.effective(member))
                catalogA.store.pin(admin, "review", "1.0.0")
                assertEquals(emptyList(), catalogB.effective(member))
                failure("forbidden") { catalogA.store.assign(member, "review", member.member.id, SkillDecision.ALLOW) }
                catalogA.store.assign(admin, "review", member.member.id, SkillDecision.ALLOW)
                catalogA.store.assign(admin, "review", other.id, SkillDecision.DENY)
                assertEquals(listOf("1.0.0"), catalogB.effective(member).map { it.version })
                assertEquals(emptyList(), catalogB.effective(admin, other.id))
                val first = catalogB.download(member, "review", "1.0.0")
                assertEquals(v1.version.digest, first.skill.digest)
                assertContentEquals(large, first.files.single { it.path == "assets/data.bin" }.content)
                assertEquals(SkillFileMode.EXECUTABLE, first.files.single { it.path == "scripts/check.sh" }.mode)
                failure("not_found") { catalogB.download(member, "review", "2.0.0") }

                // Update then rollback: each pin serves the same immutable bytes as before.
                catalogB.store.pin(admin, "review", "2.0.0")
                assertEquals(v2.version.digest, catalogA.download(member, "review", "2.0.0").skill.digest)
                failure("not_found") { catalogA.download(member, "review", "1.0.0") }
                catalogA.store.pin(admin, "review", "1.0.0")
                val rolledBack = catalogB.download(member, "review", "1.0.0")
                assertEquals(first.entries, rolledBack.entries)
                assertEquals(first.skill, rolledBack.skill)

                // Installation status is per device and separate from access.
                failure("credential_scope") { catalogA.report(member.copy(serviceScopes = setOf(ServiceScope.SKILLS_READ)), emptyList()) }
                failure("device_required") { catalogA.report(admin, listOf(SkillInstallationReport("review", "1.0.0", v1.version.digest, SkillInstallationStatus.INSTALLED))) }
                failure("not_found") { catalogA.report(member, listOf(SkillInstallationReport("unknown", null, null, SkillInstallationStatus.CONFLICT))) }
                failure("invalid_skill_installation") { catalogA.report(member, listOf(SkillInstallationReport("review", "9.9.9", null, SkillInstallationStatus.INSTALLED))) }
                failure("invalid_skill_installation") { catalogA.report(member, listOf(SkillInstallationReport("review", "1.0.0", v2.version.digest, SkillInstallationStatus.INSTALLED))) }
                failure("invalid_skill_installation") { catalogA.report(member, listOf(SkillInstallationReport("review", null, v1.version.digest, SkillInstallationStatus.INSTALLED))) }
                catalogA.report(member, listOf(SkillInstallationReport("review", "1.0.0", v1.version.digest, SkillInstallationStatus.INSTALLED)))
                catalogB.report(member, listOf(SkillInstallationReport("review", "1.0.0", v1.version.digest, SkillInstallationStatus.CONFLICT)))
                val status = catalogA.store.installations(admin, member.member.id).single()
                assertEquals("Member laptop", status.deviceName); assertEquals(SkillInstallationStatus.CONFLICT, status.status)
                failure("forbidden") { catalogA.store.installations(member, admin.member.id) }

                // Removing the assignment stops delivery while the installation report remains history.
                catalogB.store.assign(admin, "review", member.member.id, null)
                assertEquals(emptyList(), catalogA.effective(member))
                failure("not_found") { catalogA.download(member, "review", "1.0.0") }
                assertEquals(1, catalogA.store.installations(member, member.member.id).size)
                catalogB.store.assign(admin, "review", member.member.id, SkillDecision.ALLOW)

                // Version retirement, unpinning and catalog removal.
                failure("skill_version_pinned") { catalogA.store.retireVersion(admin, "review", "1.0.0") }
                assertTrue(catalogA.store.retireVersion(admin, "review", "2.0.0").retired)
                failure("skill_version_retired") { catalogB.store.pin(admin, "review", "2.0.0") }
                failure("skill_version_retired") { catalogA.publish(publisher, "review", "2.0.0", files("two")) }
                catalogB.store.unpin(admin, "review")
                assertEquals(emptyList(), catalogA.effective(member))
                catalogB.store.pin(admin, "review", "1.0.0")
                catalogA.store.retire(admin, "review")
                assertEquals(emptyList(), catalogB.effective(member))
                failure("skill_retired") { catalogB.publish(publisher, "review", "3.0.0", files("three")) }
                failure("skill_retired") { catalogB.store.pin(admin, "review", "1.0.0") }
                catalogB.store.restore(admin, "review")
                assertNull(catalogA.store.versions(admin, "review").first.pinnedVersion)
                catalogA.store.pin(admin, "review", "1.0.0")
                assertEquals(listOf("1.0.0"), catalogB.effective(member).map { it.version })

                // CI pins what it publishes, in the publication transaction and with the same key; a retry changes nothing.
                val auto = catalogA.publish(publisher, "review", "5.0.0", files("five"), pin = true)
                assertTrue(auto.created); assertEquals("5.0.0", auto.skill.pinnedVersion); assertTrue(auto.version.id.isNotEmpty())
                assertEquals(listOf("5.0.0"), catalogB.effective(member).map { it.version })
                val retried = catalogB.publish(publisher, "review", "5.0.0", files("five"), pin = true)
                assertFalse(retried.created); assertEquals("5.0.0", retried.skill.pinnedVersion)
                failure("skill_version_conflict") { catalogB.publish(publisher, "review", "5.0.0", files("changed"), pin = true) }
                failure("skill_version_retired") { catalogA.publish(publisher, "review", "2.0.0", files("two"), pin = true) }
                failure("skill_version_retired") { catalogA.store.pin(publisher, "review", "2.0.0") }
                assertEquals("5.0.0", catalogA.store.versions(admin, "review").first.pinnedVersion)
                // Republishing an existing version with pin, or the pin route, is a rollback the key may make.
                assertEquals("1.0.0", catalogB.publish(publisher, "review", "1.0.0", files("one", large), pin = true).skill.pinnedVersion)
                assertEquals("5.0.0", catalogA.store.pin(publisher, "review", "5.0.0").pinnedVersion)
                assertEquals("5.0.0", catalogB.store.pin(publisher, "review", "5.0.0").pinnedVersion)
                // Each actual change is one audit event naming the key; interactive pins leave the credential empty.
                val pins = a.query {
                    AuditEvents.select(AuditEvents.subjectId, AuditEvents.credentialId).where { AuditEvents.action eq "skill.pinned" }
                        .orderBy(AuditEvents.id to SortOrder.ASC).map { it[AuditEvents.credentialId] to it[AuditEvents.subjectId] }
                }
                assertEquals(listOf(publisher.tokenId to auto.version.id, publisher.tokenId to v1.version.id, publisher.tokenId to auto.version.id),
                    pins.filter { it.first == publisher.tokenId })
                assertTrue(pins.filter { it.first != publisher.tokenId }.all { it.first == null })
                assertTrue(pins.any { it.first == null })

                // A publishing key cannot otherwise administer the catalog, and its revocation is shared by every replica.
                failure("forbidden") { catalogB.store.catalog(member) }
                failure("credential_scope") { catalogB.store.catalog(publisher) }
                failure("credential_scope") { catalogB.store.unpin(publisher, "review") }
                failure("credential_scope") { catalogB.store.retire(publisher, "review") }
                failure("forbidden") { catalogB.store.pin(member, "review", "1.0.0") }
                failure("credential_scope") { catalogB.store.pin(inference, "review", "1.0.0") }
                keys.revoke(admin, publisher.tokenId)
                failure("authentication_required") { catalogB.publish(publisher, "review", "4.0.0", files("four")) }

                // Audit holds identifiers only, never content or digests.
                val actions = a.query { AuditEvents.select(AuditEvents.action, AuditEvents.subjectId).where { AuditEvents.action like "skill.%" }.map { it[AuditEvents.action] to it[AuditEvents.subjectId] } }
                assertTrue(actions.map { it.first }.containsAll(listOf("skill.created", "skill.version.published", "skill.pinned", "skill.access.allowed",
                    "skill.access.denied", "skill.access.cleared", "skill.version.retired", "skill.unpinned", "skill.retired", "skill.restored")))
                assertEquals(3, actions.count { it.first == "skill.version.published" }, "Idempotent and refused publications append nothing")
                assertTrue(actions.all { (_, subject) -> subject.length == 36 })
                // Access events name the assignment, which resolves to both the skill and the member, even once cleared.
                val accessSubjects = actions.filter { it.first.startsWith("skill.access.") }.map { it.second }.toSet()
                val resolved = a.query { SkillAssignments.select(SkillAssignments.id, SkillAssignments.skillId, SkillAssignments.memberId).where { SkillAssignments.id inList accessSubjects }.map { it[SkillAssignments.skillId] to it[SkillAssignments.memberId] }.toSet() }
                assertEquals(setOf(v1.skill.id to member.member.id, v1.skill.id to other.id), resolved)
            } }
        }
    }

    @Test fun concurrentFirstPublicationsOfTheSameVersionConvergeOnOneRecord() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                val team = SqlTeamService(a)
                val initial = team.bootstrap()!!
                team.changePassword(team.login("admin", initial, "fixture").principal, initial, "skill administrator password")
                val admin = team.login("admin", "skill administrator password", "fixture").principal
                val results = listOf(SkillCatalog(SqlSkillStore(a)), SkillCatalog(SqlSkillStore(b))).map { catalog ->
                    async(Dispatchers.IO) { catalog.publish(admin, "race", "1", files("same")) }
                }.awaitAll()
                assertEquals(1, results.count { it.created })
                assertEquals(1, results.map { it.version.id }.toSet().size)
                assertEquals(1L, a.query { SkillVersions.selectAll().count() })
                // Version labels are case-sensitive, matching the domain pattern.
                val catalog = SkillCatalog(SqlSkillStore(a))
                assertTrue(catalog.publish(admin, "race", "v2", files("lower")).created)
                assertTrue(catalog.publish(admin, "race", "V2", files("upper")).created)
                catalog.store.pin(admin, "race", "V2")
                assertEquals("V2", catalog.store.versions(admin, "race").first.pinnedVersion)
            } }
        }
    }

    @Test fun aPinningPublicationAndAVersionRetirementNeverLeaveARetiredVersionPinned() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                val team = SqlTeamService(a)
                val initial = team.bootstrap()!!
                team.changePassword(team.login("admin", initial, "fixture").principal, initial, "skill administrator password")
                val admin = team.login("admin", "skill administrator password", "fixture").principal
                val publisher = team.authenticate(SqlServiceKeyService(a).create(admin, admin.member.id, "skills CI", setOf(ServiceScope.SKILLS_PUBLISH), 1).accessToken)!!
                val ci = SkillCatalog(SqlSkillStore(a)); val dashboard = SkillCatalog(SqlSkillStore(b))
                repeat(20) { round ->
                    val name = "race-$round"
                    ci.publish(publisher, name, "1", files("one"), pin = true)
                    ci.publish(publisher, name, "2", files("two"))
                    // CI re-pins 2 by retrying its publication while an administrator retires 2: one of them wins, never both.
                    listOf(
                        async(Dispatchers.IO) { runCatching { ci.publish(publisher, name, "2", files("two"), pin = true) } },
                        async(Dispatchers.IO) { runCatching { dashboard.store.retireVersion(admin, name, "2") } },
                    ).awaitAll()
                    val (skill, versions) = dashboard.store.versions(admin, name)
                    val pinned = versions.single { it.version == skill.pinnedVersion }
                    assertFalse(pinned.retired, "round $round: pinned ${skill.pinnedVersion} is retired")
                }
            } }
        }
    }

    @Test fun aFailedFirstPublicationLeavesNoEmptyCatalogEntry() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
                a.migrate()
                val team = SqlTeamService(a)
                val initial = team.bootstrap()!!
                team.changePassword(team.login("admin", initial, "fixture").principal, initial, "skill administrator password")
                val admin = team.login("admin", "skill administrator password", "fixture").principal
                val publisher = team.authenticate(SqlServiceKeyService(a).create(admin, admin.member.id, "skills CI", setOf(ServiceScope.SKILLS_PUBLISH), 1).accessToken)!!
                val store = SqlSkillStore(a)
                // A path above the column bound passes a hand-built artifact and fails in the content transaction.
                val tooLong = "x".repeat(300)
                val broken = SkillArtifact("0".repeat(64), listOf(SkillFile(tooLong, SkillFileMode.FILE, byteArrayOf(1))), listOf(SkillFileEntry(tooLong, SkillFileMode.FILE, 1, "0".repeat(64))))
                assertFails { store.publish(publisher, "orphan", "1.0.0", broken) }
                assertEquals(0L, a.query { Skills.selectAll().count() }, "A failed first publication must not leave an empty skill behind")
                assertEquals(0L, a.query { SkillVersions.selectAll().count() })
                // An existing skill is never removed by a failed later publication.
                assertTrue(SkillCatalog(store).publish(publisher, "kept", "1.0.0", files("one")).created)
                assertFails { store.publish(publisher, "kept", "2.0.0", broken) }
                assertEquals(1L, a.query { Skills.selectAll().count() })
            }
        }
    }
}
