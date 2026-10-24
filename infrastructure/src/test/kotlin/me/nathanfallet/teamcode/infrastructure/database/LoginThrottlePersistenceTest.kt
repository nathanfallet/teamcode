package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.TrustedBrowsers
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
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

    @Test @Timeout(300)
    fun eachAddressHasItsOwnBudget() = withDatabase { team, admin, database ->
        val attacker = LoginOrigin("198.51.100.66")
        // The budget counts per calendar minute of the database clock, and 31 password hashes take seconds on CI: a run
        // that crosses a minute starts a fresh window and is not limited (this test was flaky). Such a run is repeated
        // once; the second one starts early in the new window, which already holds the first run's later attempts.
        suspend fun window() = database.query { nowMillis() / 60_000 }
        var limited: String? = null
        for (round in 1..2) {
            val start = window()
            repeat(SqlLoginThrottle.SOURCE_ATTEMPTS) { runCatching { team.login("nobody-$it", "wrong password", "Attacker", null, null, null, attacker) } }
            val code = assertFailsWith<TeamFailure> { team.login("nobody", "wrong password", "Attacker", null, null, null, attacker) }.code
            if (window() == start) { limited = code; break }
        }
        assertEquals("login_rate_limit", limited)
        // A new device elsewhere is not a trusted device, and still signs in.
        val elsewhere = team.login("admin", ADMIN_PASSWORD, "New laptop", null, null, null, LoginOrigin("203.0.113.10"))
        assertEquals(admin.member.id, elsewhere.principal.member.id)
        assertNull(elsewhere.trustedBrowser, "Only a browser login gets a trusted-browser cookie")
    }

    @Test @Timeout(300)
    fun theUsernameBackoffDoublesAndAPasswordResetClearsIt() = withDatabase { team, admin, database ->
        val created = team.createMember(admin, "colleague", Role.MEMBER)
        val key = SqlLoginThrottle.accountKey(created.member.id, "colleague")
        suspend fun lockedFor() = database.query { val row = LoginThrottle.selectAll().where { LoginThrottle.key eq key }.single(); row[LoginThrottle.lockedUntil] - nowMillis() }
        repeat(SqlLoginThrottle.FREE_FAILURES) { failure("invalid_credentials") { team.login("colleague", "wrong password $it", "Attacker") } }
        assertTrue(lockedFor() in 50_000..60_000, "One minute at the fifth failure")
        failure("invalid_credentials") { team.login("colleague", created.temporaryPassword, "Colleague") }
        // A locked attempt does not count; the next failure after the lock doubles it.
        database.query { LoginThrottle.update({ LoginThrottle.key eq key }) { it[lockedUntil] = 0 } }
        failure("invalid_credentials") { team.login("colleague", "wrong password again", "Attacker") }
        assertTrue(lockedFor() in 110_000..120_000, "Two minutes at the sixth failure")
        assertEquals(15 * 60_000L, SqlLoginThrottle.lockMillis(30))
        val reset = team.resetPassword(admin, created.member.id)
        assertTrue(team.login("colleague", reset.temporaryPassword, "Colleague").principal.member.passwordChangeRequired)
        // Unknown usernames back off exactly like existing ones, so the lock does not tell which exist.
        repeat(SqlLoginThrottle.FREE_FAILURES) { failure("invalid_credentials") { team.login("ghost", "wrong password $it", "Attacker") } }
        assertTrue(database.query { LoginThrottle.selectAll().where { LoginThrottle.key eq SqlLoginThrottle.accountKey(null, "ghost") }.single()[LoginThrottle.lockedUntil] > nowMillis() })
    }

    @Test @Timeout(300)
    fun spellingsTheCollationMatchesShareTheMembersCounter() = withTeam { team, _ ->
        // The username column folds case and accents: each spelling reaches the same member, so it must not get fresh tries.
        for (spelling in listOf("ádmin", "àdmin", "âdmin", "ädmin", "ADMÍN")) failure("invalid_credentials") { team.login(spelling, "wrong password", "Attacker") }
        failure("invalid_credentials") { team.login("admin", ADMIN_PASSWORD, "Attacker") }
    }

    @Test fun ipv6ClientsAreKeyedByTheirSlash64() {
        assertEquals(SqlLoginThrottle.sourceKey("2001:db8:1:2:0:0:0:1"), SqlLoginThrottle.sourceKey("2001:db8:1:2:ffff:0:0:9"))
        assertNotEquals(SqlLoginThrottle.sourceKey("2001:db8:1:2:0:0:0:1"), SqlLoginThrottle.sourceKey("2001:db8:1:3:0:0:0:1"))
        assertNotEquals(SqlLoginThrottle.sourceKey("203.0.113.1"), SqlLoginThrottle.sourceKey("203.0.113.2"))
    }

    @Test @Timeout(300)
    fun aTrustedBrowserKeepsItsOwnBackoff() = withTeam { team, admin ->
        val first = team.login("admin", ADMIN_PASSWORD, "Web dashboard", null, null, null, LoginOrigin("203.0.113.1", ""))
        val cookie = assertNotNull(first.trustedBrowser)
        repeat(8) { failure("invalid_credentials") { team.login("admin", "wrong password $it", "Web dashboard", null, null, null, LoginOrigin("198.51.100.66", "")) } }
        failure("invalid_credentials") { team.login("admin", ADMIN_PASSWORD, "Web dashboard", null, null, null, LoginOrigin("203.0.113.1", "")) }
        val again = team.login("admin", ADMIN_PASSWORD, "Web dashboard", null, null, null, LoginOrigin("203.0.113.1", cookie))
        assertEquals(admin.member.id, again.principal.member.id)
        assertNotNull(again.trustedBrowser, "The cookie is renewed")
        // A cookie that is not ours, or another member's, is no trusted browser.
        for (forged in listOf("v1.${admin.member.id}.1.forged", cookie.replace(admin.member.id, "someone-else"), TrustedBrowsers(ByteArray(32) { 9 }).mint(admin.member.id, System.currentTimeMillis())))
            failure("invalid_credentials") { team.login("admin", ADMIN_PASSWORD, "Web dashboard", null, null, null, LoginOrigin("203.0.113.1", forged)) }
        // Failures from the trusted browser lock that browser only.
        repeat(SqlLoginThrottle.FREE_FAILURES) { failure("invalid_credentials") { team.login("admin", "wrong password $it", "Web dashboard", null, null, null, LoginOrigin("203.0.113.1", cookie)) } }
        failure("invalid_credentials") { team.login("admin", ADMIN_PASSWORD, "Web dashboard", null, null, null, LoginOrigin("203.0.113.1", cookie)) }
        val installation = UUID.randomUUID().toString()
        failure("invalid_credentials") { team.login("admin", ADMIN_PASSWORD, "Laptop", installation) }
    }

    @Test @Timeout(300)
    fun oldRowsArePurged() = withDatabase { team, _, database ->
        repeat(2) { runCatching { team.login("purge-$it", "wrong password", "Attacker") } }
        database.query { LoginThrottle.update({ LoginThrottle.key eq SqlLoginThrottle.accountKey(null, "purge-0") }) { it[updatedAt] = nowMillis() - SqlLoginThrottle.RETENTION_MILLIS - 1 } }
        assertEquals(1, team.purgeLoginThrottle())
        assertEquals(listOf(false, true), listOf("purge-0", "purge-1").map { name -> database.query { !LoginThrottle.selectAll().where { LoginThrottle.key eq SqlLoginThrottle.accountKey(null, name) }.empty() } })
    }

    @Test fun trustedBrowserCookiesAreBoundToTheMemberTheKeyAndTheirAge() {
        val browsers = TrustedBrowsers(ByteArray(32) { 1 })
        val now = 1_800_000_000_000L
        val token = browsers.mint("member-1", now)
        assertTrue(browsers.verify(token, "member-1", now + 1))
        assertFalse(browsers.verify(token, "member-2", now))
        assertFalse(TrustedBrowsers(ByteArray(32) { 2 }).verify(token, "member-1", now))
        assertFalse(browsers.verify(token, "member-1", now + TrustedBrowsers.MAX_AGE_MILLIS + 1))
        assertFalse(browsers.verify(token.dropLast(1) + if (token.last() == 'A') "B" else "A", "member-1", now))
        assertFalse(browsers.verify(token.replace(".$now.", ".${now + 1}."), "member-1", now))
        assertFalse(browsers.verify("", "member-1", now))
    }

    private fun withTeam(block: suspend (SqlTeamService, Principal) -> Unit) = withDatabase { team, admin, _ -> block(team, admin) }
    private fun withDatabase(block: suspend (SqlTeamService, Principal, Database) -> Unit) = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { database ->
                database.migrate()
                database.query { assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(LoginThrottle, Tokens, withLogs = false), "The reviewed migrations match the Exposed tables") }
                val team = SqlTeamService(database, browsers = TrustedBrowsers(ByteArray(32) { 7 }))
                val initial = assertNotNull(team.bootstrap())
                team.changePassword(team.login("admin", initial, "Setup").principal, initial, ADMIN_PASSWORD)
                block(team, team.login("admin", ADMIN_PASSWORD, "Setup").principal, database)
            }
        }
    }
    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
    private companion object { const val ADMIN_PASSWORD = "login throttle admin password" }
}
