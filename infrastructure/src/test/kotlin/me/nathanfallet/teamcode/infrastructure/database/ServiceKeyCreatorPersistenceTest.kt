package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.team.*
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** Audit M4: keys an administrator creates for other members end with that administrator's rights. */
class ServiceKeyCreatorPersistenceTest {
    @Test @Timeout(300)
    fun anAdministratorCannotCreateAKeyForAnOwner() = withTeam { t ->
        failure("owner_required") { t.keys.create(t.admin, t.owner.member.id, "Owner CI", setOf(ServiceScope.INFERENCE), 30) }
        // The owner still can, for any member.
        t.keys.create(t.owner, t.admin.member.id, "Operator CI", setOf(ServiceScope.INFERENCE), 30)
    }

    @Test @Timeout(300)
    fun administratorScopesAreOnlyGivenByTheKeysOwnMember() = withTeam { t ->
        for (scope in listOf(ServiceScope.ADMIN_CLOUD, ServiceScope.TEAM_READ, ServiceScope.SKILLS_PUBLISH, ServiceScope.SKILLS_ASSIGN, ServiceScope.HOOKS_PUBLISH)) {
            failure("invalid_service_key") { t.keys.create(t.owner, t.admin.member.id, "Delegated", setOf(scope), 30) }
            failure("invalid_service_key") { t.keys.create(t.admin, t.second.member.id, "Delegated", setOf(scope), 30) }
        }
        // An administrator still creates one for themselves.
        t.keys.create(t.admin, t.admin.member.id, "Own monitor", setOf(ServiceScope.TEAM_READ), 30)
    }

    @Test @Timeout(300)
    fun keysCreatedForOthersAreRevokedWhenTheirCreatorIsDisabled() = withTeam { t ->
        val forMember = t.keys.create(t.admin, t.member.member.id, "Support app", setOf(ServiceScope.INFERENCE), null)
        val ownByMember = t.keys.create(t.member, t.member.member.id, "Member CI", setOf(ServiceScope.INFERENCE), 30)
        val byOwner = t.keys.create(t.owner, t.member.member.id, "Owner made", setOf(ServiceScope.INFERENCE), 30)
        assertNotNull(t.team.authenticate(forMember.accessToken))
        t.team.updateMember(t.owner, t.admin.member.id, Role.ADMIN, true)
        assertNull(t.team.authenticate(forMember.accessToken))
        assertNotNull(t.team.authenticate(ownByMember.accessToken), "The member's own key is not the creator's")
        assertNotNull(t.team.authenticate(byOwner.accessToken), "Another administrator's key is not affected")
    }

    @Test @Timeout(300)
    fun keysCreatedForOthersAreRevokedWhenTheirCreatorIsDemoted() = withTeam { t ->
        val forMember = t.keys.create(t.admin, t.member.member.id, "Support app", setOf(ServiceScope.INFERENCE), 30)
        val forAdmin = t.keys.create(t.admin, t.second.member.id, "Colleague CI", setOf(ServiceScope.INFERENCE), 30)
        t.team.updateMember(t.owner, t.admin.member.id, Role.MEMBER, false)
        assertNull(t.team.authenticate(forMember.accessToken))
        assertNull(t.team.authenticate(forAdmin.accessToken))
        assertTrue(t.keys.list(t.owner, t.member.member.id).none { it.id == forMember.metadata.id })
    }

    private class Team(val team: SqlTeamService, val keys: SqlServiceKeyService, val owner: Principal, val admin: Principal, val second: Principal, val member: Principal)
    private fun withTeam(block: suspend (Team) -> Unit) = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { database ->
                database.migrate()
                val team = SqlTeamService(database)
                val initial = assertNotNull(team.bootstrap())
                team.changePassword(team.login("admin", initial, "Setup").principal, initial, "creator test owner password")
                val owner = team.login("admin", "creator test owner password", "Setup").principal
                suspend fun ready(username: String, role: Role): Principal {
                    val created = team.createMember(owner, username, role)
                    team.changePassword(team.login(username, created.temporaryPassword, "Setup").principal, created.temporaryPassword, "creator test replacement password")
                    return team.login(username, "creator test replacement password", "Setup").principal
                }
                block(Team(team, SqlServiceKeyService(database), owner, ready("operator", Role.ADMIN), ready("second-operator", Role.ADMIN), ready("colleague", Role.MEMBER)))
            }
        }
    }
    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
}
