package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class PoolStandingPersistenceTest {
    @Test fun unusablePoolsExplainTheirAccountsAndOnlyWaitingOnesAreRetryable() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { database ->
                database.migrate()
                val team = SqlTeamService(database)
                val password = team.bootstrap()!!
                val initial = team.login("admin", password, "Pool standing fixture")
                team.changePassword(initial.principal, password, "Pool standing fixture replacement")
                val actor = team.login("admin", "Pool standing fixture replacement", "Pool standing fixture").principal
                val store = SqlAccountStore(database, CredentialCipher(ByteArray(32) { 31 }))
                suspend fun enroll(name: String) = (store.upsert(AccountMutationActor.System, VerifiedProviderAccount(AccountIdentity("default", "anthropic", name, "org"), name),
                    ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, name) as EnrollmentResult.Created).account
                val admission = SqlAdmissionStore(database)

                val empty = assertFailsWith<TeamFailure> { admission.reserve(actor, 1, "claude-test") }
                assertEquals(503 to false, empty.status to empty.retryable)
                assertTrue("No Claude account is connected" in empty.message!!, empty.message)

                val pending = (1..6).map { enroll("pending-$it") }
                val handover = assertFailsWith<TeamFailure> { admission.reserve(actor, 1, "claude-test") }
                assertEquals("pool_unavailable", handover.code)
                assertEquals(503, handover.status)
                assertFalse(handover.retryable)
                assertTrue("6 accounts are waiting for a handover" in handover.message!!, handover.message)

                // A codex request only counts codex accounts.
                val codex = assertFailsWith<TeamFailure> { admission.reserve(actor, 1, "gpt-test", "codex") }
                assertTrue("No Codex account is connected" in codex.message!!, codex.message)

                val ready = store.handover(AccountMutationActor.System, "default", pending.first().id, accessOnly = true)
                val now = database.query { nowMillis() }
                admission.coolDown(ready.id, now + 40_000)
                val cooling = assertFailsWith<TeamFailure> { admission.reserve(actor, 1, "claude-test") }
                assertEquals(429, cooling.status)
                assertTrue(cooling.retryable)
                assertTrue(cooling.retryAfterSeconds!! in 1..40, cooling.retryAfterSeconds.toString())
                assertTrue("5 accounts are waiting for a handover" in cooling.message!! && "1 account is paused after a provider rate limit" in cooling.message!!, cooling.message)

                // An account the provider already refused for this request counts as waiting too.
                val second = store.handover(AccountMutationActor.System, "default", pending[1].id, accessOnly = true)
                val refused = assertFailsWith<TeamFailure> { admission.reserve(actor, 1, "claude-test", excludedAccountIds = setOf(second.id)) }
                assertEquals(429, refused.status)
                assertTrue("2 accounts are paused after a provider rate limit" in refused.message!!, refused.message)
                val admitted = admission.reserve(actor, 1, "claude-test")
                assertEquals(second.id, admitted.account.id)
                admission.settle(admitted, 0)
            }
        }
    }
}
