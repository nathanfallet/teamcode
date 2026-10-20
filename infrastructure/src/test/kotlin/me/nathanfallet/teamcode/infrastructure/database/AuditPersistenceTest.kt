package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class AuditPersistenceTest {
    @Test fun keysetJournalIsBoundedTeamScopedAndRevocationAwareAcrossReplicas() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                val team = SqlTeamService(a)
                val initial = team.bootstrap()!!
                val pending = team.login("admin", initial, "Audit fixture")
                team.changePassword(pending.principal, initial, "audit reader password")
                val login = team.login("admin", "audit reader password", "Audit fixture")
                val actor = login.principal
                a.query {
                    repeat(123) { audit("default", actor.member.id, "fixture.recorded", UUID.randomUUID().toString()) }
                    repeat(7) { audit("foreign-team", "foreign-actor", "private.foreign", "foreign-subject") }
                }
                val service = SqlAuditService(b)
                val first = service.list(actor)
                assertEquals(AuditService.PAGE_SIZE, first.events.size)
                assertNotNull(first.nextBeforeId)
                val latest = first.events.first().id
                a.query { audit("default", actor.member.id, "fixture.appended", UUID.randomUUID().toString()) }
                val all = first.events.toMutableList()
                var before = first.nextBeforeId
                while (before != null) {
                    val page = service.list(actor, before)
                    assertTrue(page.events.all { it.id < before })
                    assertTrue(page.events.size <= AuditService.PAGE_SIZE)
                    all.addAll(page.events); before = page.nextBeforeId
                }
                assertEquals(123, all.count { it.action == "fixture.recorded" })
                assertEquals(all.size, all.map { it.id }.toSet().size)
                assertTrue(all.zipWithNext().all { (newer, older) -> newer.id > older.id })
                assertFalse(all.any { it.action.startsWith("private") || it.action == "fixture.appended" })
                assertTrue(service.list(actor).events.first().id > latest)
                assertTrue(service.list(actor, 1).events.isEmpty()); assertNull(service.list(actor, 1).nextBeforeId)
                suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
                failure("invalid_cursor") { service.list(actor, 0) }
                failure("invalid_cursor") { service.list(actor, -1) }
                failure("forbidden") { service.list(actor.copy(member = actor.member.copy(role = Role.MEMBER))) }
                failure("credential_scope") { service.list(actor.copy(serviceScopes = setOf(ServiceScope.USAGE_READ))) }
                failure("authentication_required") { service.list(actor.copy(teamId = "foreign-team")) }
                val key = SqlServiceKeyService(a).create(actor, actor.member.id, "Audit denied", setOf(ServiceScope.USAGE_READ), 1)
                val credential = team.authenticate(key.accessToken)!!
                failure("credential_scope") { service.list(credential) }
                // Stripping the scopes in memory does not turn a persisted service key into an interactive session.
                failure("authentication_required") { service.list(credential.copy(serviceScopes = null)) }
                a.query { Members.update({ Members.id eq actor.member.id }) { it[role] = "MEMBER"; it[isOwner] = false } }
                failure("authentication_required") { service.list(actor) }
                a.query { Members.update({ Members.id eq actor.member.id }) { it[role] = "ADMIN"; it[isOwner] = true } }
                team.logout(actor)
                failure("authentication_required") { service.list(actor) }
                assertNull(team.authenticate(login.token))
                val again = team.login("admin", "audit reader password", "New reader").principal
                assertEquals("session.login", service.list(again).events.first().action)
                a.query { Tokens.update({ Tokens.id eq again.tokenId }) { it[expiresAt] = 0 } }
                failure("authentication_required") { service.list(again) }
                b.migrate()
            } }
        }
    }
}
