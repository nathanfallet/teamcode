package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.skills.*
import me.nathanfallet.teamcode.domain.team.*
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** A member's own skills against real MySQL: owner-only visibility and delivery, one namespace per team, promotion. */
class PersonalSkillPersistenceTest {
    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
    private fun files(body: String) = listOf(
        SkillFile("SKILL.md", SkillFileMode.FILE, "---\nname: mine\n---\n$body".encodeToByteArray()),
        SkillFile("scripts/run.sh", SkillFileMode.EXECUTABLE, "#!/bin/sh\necho $body\n".encodeToByteArray()),
    )

    @Test fun aPersonalSkillReachesItsOwnerOnlyAndNamesStayUniqueInTheTeam() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { db ->
                db.migrate()
                val team = SqlTeamService(db)
                val initial = team.bootstrap()!!
                team.changePassword(team.login("admin", initial, "fixture").principal, initial, "personal skill admin password")
                val admin = team.login("admin", "personal skill admin password", "fixture").principal
                suspend fun member(name: String): Principal {
                    val created = team.createMember(admin, name, Role.MEMBER)
                    team.changePassword(team.login(name, created.temporaryPassword, "fixture").principal, created.temporaryPassword, "$name personal password")
                    return team.login(name, "$name personal password", "fixture").principal
                }
                val vivien = member("vivien"); val chiheb = member("chiheb")
                val catalog = SkillCatalog(SqlSkillStore(db))

                // The owner publishes, pins and receives it; nobody else does, not even with allMembers.
                val first = catalog.personal.publish(vivien, "my-review", "1.0.0", files("one"), pin = true)
                assertTrue(first.created); assertEquals(vivien.member.id, first.skill.ownerId); assertEquals("1.0.0", first.skill.pinnedVersion)
                assertEquals(listOf("my-review"), catalog.effective(vivien).map { it.name })
                assertEquals(emptyList(), catalog.effective(chiheb))
                assertEquals(emptyList(), catalog.effective(admin, chiheb.member.id))
                assertEquals(vivien.member.id, catalog.personal.mine(vivien).single().ownerId)
                assertEquals(emptyList(), catalog.personal.mine(chiheb))
                assertEquals("1.0.0", catalog.download(vivien, "my-review", "1.0.0").skill.version)
                failure("not_found") { catalog.download(chiheb, "my-review", "1.0.0") }
                assertFalse(catalog.personal.publish(vivien, "my-review", "1.0.0", files("one")).created, "a retry is idempotent")

                // One namespace per team: nobody takes a name used by the team or by another member.
                failure("name_taken") { catalog.personal.publish(chiheb, "my-review", "1.0.0", files("other")) }
                catalog.publish(admin, "team-review", "1.0.0", files("team"))
                failure("name_taken") { catalog.personal.publish(vivien, "team-review", "1.0.0", files("mine")) }
                failure("name_taken") { catalog.publish(admin, "my-review", "9.0.0", files("team")) }

                // The owner is the only one who manages it; the catalog's own operations refuse it.
                failure("not_found") { catalog.personal.pin(chiheb, "my-review", "1.0.0") }
                failure("not_found") { catalog.personal.versions(chiheb, "my-review") }
                failure("skill_personal") { catalog.manage.pin(admin, "my-review", "1.0.0") }
                failure("skill_personal") { catalog.manage.assign(admin, "my-review", chiheb.member.id, SkillDecision.ALLOW) }
                failure("skill_personal") { catalog.manage.setAudience(admin, "my-review", true) }
                failure("not_found") { catalog.personal.pin(vivien, "team-review", "1.0.0") }
                assertEquals(vivien.member.id, catalog.manage.catalog(admin).single { it.name == "my-review" }.ownerId, "administrators see it as metadata")
                failure("forbidden") { catalog.manage.promote(vivien, "my-review") }

                // Retire stops delivery; restore does not pin again.
                catalog.personal.retire(vivien, "my-review")
                assertEquals(emptyList(), catalog.effective(vivien))
                catalog.personal.restore(vivien, "my-review")
                assertEquals(emptyList(), catalog.effective(vivien))
                catalog.personal.pin(vivien, "my-review", "1.0.0")
                assertEquals(listOf("my-review"), catalog.effective(vivien).map { it.name })

                // The limit counts a member's own skills only.
                for (index in 2..SkillStore.PERSONAL_LIMIT.toInt()) catalog.personal.publish(chiheb, "c-$index", "1.0.0", files("c$index"))
                catalog.personal.publish(chiheb, "c-1", "1.0.0", files("c1"))
                failure("skill_limit") { catalog.personal.publish(chiheb, "c-extra", "1.0.0", files("extra")) }
                catalog.personal.publish(vivien, "v-extra", "1.0.0", files("extra"))

                // Promotion after review: a team skill, nothing delivered until the administrator pins it.
                val promoted = catalog.manage.promote(admin, "my-review")
                assertNull(promoted.ownerId); assertNull(promoted.pinnedVersion)
                assertEquals(emptyList(), catalog.effective(vivien).filter { it.name == "my-review" })
                assertEquals(emptyList(), catalog.personal.mine(vivien).filter { it.name == "my-review" })
                failure("skill_not_personal") { catalog.manage.promote(admin, "my-review") }
                catalog.manage.pin(admin, "my-review", "1.0.0")
                catalog.manage.assign(admin, "my-review", chiheb.member.id, SkillDecision.ALLOW)
                assertEquals(listOf("my-review"), catalog.effective(chiheb).map { it.name })
            }
        }
    }
}
