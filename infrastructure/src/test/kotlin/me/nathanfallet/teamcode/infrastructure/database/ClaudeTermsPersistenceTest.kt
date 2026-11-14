package me.nathanfallet.teamcode.infrastructure.database

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.policy.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.claudeTermsRefusal
import me.nathanfallet.teamcode.infrastructure.database.tables.Accounts
import me.nathanfallet.teamcode.infrastructure.gateway.PoolGateway
import me.nathanfallet.teamcode.infrastructure.providers.ClaudeTransport
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class ClaudeTermsPersistenceTest {
    @Test fun aTermsRefusalOnOneReplicaIsSkippedOnAnotherAndRecoversAfterTheCooldown() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate()
                val team = SqlTeamService(a)
                val initial = team.bootstrap()!!
                val pending = team.login("admin", initial, "terms test")
                team.changePassword(pending.principal, initial, "terms test replacement password")
                val actor = team.login("admin", "terms test replacement password", "terms test").principal
                val tokens = ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE)
                val store = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 23 }))
                suspend fun enroll(name: String) = (store.upsert(AccountMutationActor.System,
                    VerifiedProviderAccount(AccountIdentity("default", "anthropic", name, "fixture-org"), name),
                    tokens, null, "enroll-$name", EnrollmentOrigin.SIGN_IN) as EnrollmentResult.Created).account
                val refused = enroll("terms")
                val admissionA = SqlAdmissionStore(a)
                val admissionB = SqlAdmissionStore(b)
                val source = object : CredentialSource {
                    override suspend fun fresh(account: AccountRecord, rejectedAccessToken: String?) = tokens
                }
                val policies = object : ModelPolicyStore {
                    override suspend fun get(teamId: String) = ModelPolicy()
                    override suspend fun save(actor: Principal, policy: ModelPolicy) = error("unused")
                }
                HttpClient(MockEngine { respond(claudeTermsRefusal(), HttpStatusCode.BadRequest) }) { install(HttpTimeout) }.use { http ->
                    val gateway = PoolGateway(admissionA, source, listOf(ClaudeTransport(http)), policies)
                    val request = GenerationRequest("""{"model":"claude-test","stream":true,"max_tokens":1,"messages":[{"role":"user","content":"fixture"}]}""".encodeToByteArray(), emptyMap(), actor)
                    assertEquals("provider_terms_required", assertFailsWith<TeamFailure> {
                        gateway.forward(request) { _, _ -> fail("No successful provider answer") }
                    }.code)
                }
                assertEquals(0L, admissionB.capacity(actor).chargedAndReservedUnits, "A definite rejection is settled at zero")
                val paused = admissionB.poolAccounts(actor).single()
                assertEquals(AccountUnavailability.COOLING_DOWN, paused.unavailable)
                assertEquals(AccountState.READY.name, paused.state, "No reconnect or permanent disable")
                assertEquals("pool_unavailable", assertFailsWith<TeamFailure> { admissionB.reserve(actor, 1, "claude-test") }.code)

                val healthy = enroll("healthy")
                val fallback = admissionB.reserve(actor, 1, "claude-test")
                assertEquals(healthy.id, fallback.account.id)
                admissionB.settle(fallback, 0)

                // Advance only this local fixture's cooldown; never sleep for a minute or change its credentials.
                a.query { Accounts.update({ Accounts.id eq refused.id }) { it[cooldownUntil] = nowMillis() - 1 } }
                val recovered = admissionB.reserve(actor, 1, "claude-test", excludedAccountIds = setOf(healthy.id))
                assertEquals(refused.id, recovered.account.id)
                assertEquals(refused.credentialVersion, recovered.account.credentialVersion)
                admissionB.settle(recovered, 0)
            }
            }
        }
    }
}
