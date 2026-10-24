package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.team.*
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** Audit M3: anonymous attempts must not be able to keep members out. */
class LoginThrottlePersistenceTest {
    @Test @Timeout(300)
    fun aFloodOfAnonymousAttemptsDoesNotBlockAMemberOnAKnownDevice() = withTeam { team, admin ->
        val installation = UUID.randomUUID().toString()
        team.login("admin", ADMIN_PASSWORD, "Laptop", installation)
        // More attempts than the former team-wide budget (120 a minute), on usernames that do not exist.
        repeat(130) { runCatching { team.login("nobody-$it", "wrong password", "Attacker") } }
        assertEquals(admin.member.id, team.login("admin", ADMIN_PASSWORD, "Laptop", installation).principal.member.id)
    }

    @Test @Timeout(300)
    fun failuresOnAMembersUsernameDoNotLockThemOutOfAKnownDevice() = withTeam { team, admin ->
        val installation = UUID.randomUUID().toString()
        team.login("admin", ADMIN_PASSWORD, "Laptop", installation)
        repeat(8) { assertEquals("invalid_credentials", assertFailsWith<TeamFailure> { team.login("admin", "wrong password $it", "Attacker") }.code) }
        assertEquals(admin.member.id, team.login("admin", ADMIN_PASSWORD, "Laptop", installation).principal.member.id)
    }

    @Test @Timeout(300)
    fun aLockedUsernameAnswersAfterTheSamePasswordWorkAsAnyOther() = withTeam { team, _ ->
        suspend fun timed(username: String): Long {
            val start = System.nanoTime()
            assertFailsWith<TeamFailure> { team.login(username, "wrong password", "Probe") }
            return (System.nanoTime() - start) / 1_000_000
        }
        val unlocked = (1..3).minOf { timed("unknown-$it") }
        repeat(6) { runCatching { team.login("admin", "wrong password $it", "Attacker") } }
        // The locked branch used to return before the password hash, so its timing told which usernames exist.
        val locked = (1..3).minOf { timed("admin") }
        assertTrue(locked * 2 >= unlocked, "locked $locked ms, unlocked $unlocked ms")
    }

    private fun withTeam(block: suspend (SqlTeamService, Principal) -> Unit) = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { database ->
                database.migrate()
                val team = SqlTeamService(database)
                val initial = assertNotNull(team.bootstrap())
                team.changePassword(team.login("admin", initial, "Setup").principal, initial, ADMIN_PASSWORD)
                block(team, team.login("admin", ADMIN_PASSWORD, "Setup").principal)
            }
        }
    }
    private companion object { const val ADMIN_PASSWORD = "login throttle admin password" }
}
