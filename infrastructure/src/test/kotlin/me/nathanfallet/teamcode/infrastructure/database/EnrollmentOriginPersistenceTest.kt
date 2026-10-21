package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class EnrollmentOriginPersistenceTest {
    @Test fun aFreshSignInServesAtOnceWhileAnImportWaitsForItsHandover() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { database ->
                database.migrate()
                val team = SqlTeamService(database)
                val password = team.bootstrap()!!
                val initial = team.login("admin", password, "Enrollment origin fixture")
                team.changePassword(initial.principal, password, "Enrollment origin fixture replacement")
                val actor = team.login("admin", "Enrollment origin fixture replacement", "Enrollment origin fixture").principal
                val store = SqlAccountStore(database, CredentialCipher(ByteArray(32) { 41 }))
                val admission = SqlAdmissionStore(database)
                val credentials = ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE)
                fun verified(name: String, email: String? = null) = VerifiedProviderAccount(AccountIdentity("default", "anthropic", name, "org"), name, email)
                suspend fun reserve() = admission.reserve(actor, 1, "claude-test")

                val imported = (store.upsert(AccountMutationActor.System, verified("imported"), credentials, null, "import-operation-0001") as EnrollmentResult.Created).account
                assertEquals(AccountState.PENDING_HANDOVER, imported.state)
                assertEquals("pool_unavailable", assertFailsWith<TeamFailure> { reserve() }.code, "An imported account serves nothing before its handover")

                val signedIn = (store.upsert(AccountMutationActor.System, verified("signed-in", "ada@example.com"), credentials, null, "sign-in-operation-01", EnrollmentOrigin.SIGN_IN) as EnrollmentResult.Created).account
                assertEquals(AccountState.READY, signedIn.state)
                assertEquals("handover_not_pending", assertFailsWith<TeamFailure> { store.handover(AccountMutationActor.System, "default", signedIn.id, requirePending = true) }.code,
                    "The dashboard button never changes an account that is not pending")
                assertEquals("ada@example.com", store.find(signedIn.identity)!!.email)
                val reservation = reserve()
                assertEquals(signedIn.id, reservation.account.id, "A fresh sign-in is usable by the pool immediately")
                admission.settle(reservation, 0)

                // Importing again keeps the pending state; signing in again through TeamCode takes the account over.
                val reimported = (store.upsert(AccountMutationActor.System, verified("imported"), credentials, 1, "import-operation-0002") as EnrollmentResult.Updated).account
                assertEquals(AccountState.PENDING_HANDOVER, reimported.state)
                val reconnected = (store.upsert(AccountMutationActor.System, verified("imported", "grace@example.com"), credentials, 2, "sign-in-operation-02", EnrollmentOrigin.SIGN_IN) as EnrollmentResult.Updated).account
                assertEquals(AccountState.READY, reconnected.state)
                assertEquals(AccountState.READY, store.find(imported.identity)!!.state)
                assertEquals("grace@example.com", store.find(imported.identity)!!.email)
                // A later import without an email keeps the one already known.
                store.upsert(AccountMutationActor.System, verified("imported"), credentials, 3, "import-operation-0003")
                assertEquals("grace@example.com", store.find(imported.identity)!!.email)
            }
        }
    }
}
