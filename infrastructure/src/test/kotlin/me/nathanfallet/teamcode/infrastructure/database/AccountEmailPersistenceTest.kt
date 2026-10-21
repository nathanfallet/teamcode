package me.nathanfallet.teamcode.infrastructure.database

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.infrastructure.providers.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class AccountEmailPersistenceTest {
    @Test fun anExistingAccountLearnsItsEmailWhenItsTokenIsRefreshedAndKeepsItAfterwards() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { database ->
                database.migrate()
                SqlTeamService(database).bootstrap()
                val cipher = CredentialCipher(ByteArray(32) { 43 })
                val store = SqlAccountStore(database, cipher)
                val identity = AccountIdentity("default", "anthropic", "claude-account", "claude-org")
                val expired = ProviderCredentials("expired-access", "refresh", 1)
                val account = (store.upsert(AccountMutationActor.System, VerifiedProviderAccount(identity, "Claude"), expired, null, "email-operation-0001", EnrollmentOrigin.SIGN_IN) as EnrollmentResult.Created).account
                assertNull(account.email, "Enrolled before emails were recorded")

                var refreshes = 0
                val http = HttpClient(MockEngine {
                    refreshes++
                    respond("""{"access_token":"renewed-access","refresh_token":"refresh","expires_in":3600,"account":{"uuid":"claude-account","email_address":"ada@example.com"},"organization":{"uuid":"claude-org"}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }) { install(HttpTimeout) }
                http.use {
                    val scope = CoroutineScope(SupervisorJob())
                    val backfill = AccountEmailBackfill(listOf(ClaudeTransport(it)), emptyMap(), store, scope)
                    val source = SqlCredentialSource(database, cipher) { a, t -> backfill.renew(a, t) }
                    assertEquals("renewed-access", source.fresh(account).accessToken)
                    scope.coroutineContext[Job]!!.children.toList().joinAll()
                    assertEquals("ada@example.com", store.find(identity)!!.email)
                    assertEquals(2, store.find(identity)!!.credentialVersion, "Only the refresh bumped the credentials")

                    assertFalse(store.recordEmail(identity, account.id, "other@example.com"), "A known address is never replaced")
                    assertEquals("ada@example.com", store.find(identity)!!.email)
                    assertFalse(store.recordEmail(identity.copy(organizationId = "other-org"), account.id, "third@example.com"))
                }
                assertEquals(1, refreshes)
            }
        }
    }
}
