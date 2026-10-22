package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class QueueTurnWithoutLimitPersistenceTest {
    @Test fun withoutAConcurrencyLimitEveryWaiterIsAdmittedAtOnceAndWithOneTheTurnOrderStays() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { db ->
                db.migrate()
                val team = SqlTeamService(db)
                val password = team.bootstrap()!!
                val initial = team.login("admin", password, "queue test")
                team.changePassword(initial.principal, password, "queue administrator replacement")
                val admin = team.login("admin", "queue administrator replacement", "queue test").principal
                val created = team.createMember(admin, "colleague", Role.MEMBER)
                val login = team.login("colleague", created.temporaryPassword, "queue test")
                team.changePassword(login.principal, created.temporaryPassword, "queue colleague replacement")
                val colleague = team.login("colleague", "queue colleague replacement", "queue test").principal
                val accounts = SqlAccountStore(db, CredentialCipher(ByteArray(32) { 37 }))
                val enrolled = accounts.upsert(AccountMutationActor.System, VerifiedProviderAccount(AccountIdentity("default", "anthropic", "queue-test", "org"), "Queue account"), ProviderCredentials("synthetic", "synthetic-refresh", Long.MAX_VALUE), null, "queue-enrollment") as EnrollmentResult.Created
                accounts.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
                val store = SqlAdmissionStore(db)
                val input = AdmissionInput(100, "claude-test", "anthropic")

                // No member has a concurrency limit: a free account takes every ready waiter in the same round.
                val together = listOf(admin, colleague, admin, colleague).map { it to store.enqueue(it, input) }
                val admitted = together.map { (who, ticket) -> store.reserveQueued(who, ticket, input) }
                assertEquals(4, admitted.size)
                admitted.forEach { store.settle(it, 0) }

                // One limited member among the waiters brings the turns back: only the first in line is admitted.
                store.configure(admin, MemberPolicy(admin.member.id, maxConcurrent = 4))
                val first = store.enqueue(admin, input)
                val second = store.enqueue(colleague, input)
                assertEquals("queue_turn", assertFailsWith<TeamFailure> { store.reserveQueued(colleague, second, input) }.code)
                store.settle(store.reserveQueued(admin, first, input), 0)
                store.settle(store.reserveQueued(colleague, second, input), 0)
            }
        }
    }
}
