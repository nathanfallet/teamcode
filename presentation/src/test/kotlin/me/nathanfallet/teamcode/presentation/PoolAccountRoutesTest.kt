package me.nathanfallet.teamcode.presentation

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class PoolAccountRoutesTest {
    @Test fun membersReadTheirAllowanceAndNeutralAccountsWhileAdministratorsSeeNames() = testApplication {
        val team = object : TeamService by FakeTeam() {
            override suspend fun authenticate(token: String): Principal? = when (token) {
                "usage-key" -> FakeTeam().authenticate("admin")!!.copy(serviceScopes = setOf(ServiceScope.USAGE_READ))
                "inference-key" -> FakeTeam().authenticate("admin")!!.copy(serviceScopes = setOf(ServiceScope.INFERENCE))
                else -> FakeTeam().authenticate(token)
            }
        }
        val gateway = object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("No inference")
        }
        application { configureGateway(gateway, team = team, admission = PoolAdmission(), publicOrigin = "http://localhost") }
        fun sdk(token: String) = TeamCodeClient("http://localhost", { token }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }

        sdk("admin").use { admin ->
            val accounts = admin.poolAccounts()
            assertEquals(listOf("Work (work@example.com)", "Spare"), accounts.map { it.label }, "Name (email), or the name while the email is unknown")
            assertEquals("work@example.com", accounts[0].email)
            assertEquals(listOf("work-id", "spare-id"), accounts.map { it.id })
            assertEquals(listOf(true, false), accounts.map { it.usable })
            assertEquals("quota_exhausted", accounts[1].unavailableReason)
            assertEquals(listOf("five_hour", "seven_day"), accounts[0].windows.map { it.window })
        }
        for (token in listOf("member", "usage-key")) sdk(token).use { member ->
            val accounts = member.poolAccounts()
            assertEquals(listOf("Account 1", "Account 2"), accounts.map { it.label }, token)
            assertTrue(accounts.all { it.id == null && it.name == null && it.email == null }, token)
            // The raw body carries no account name or identifier either.
            val raw = client.get("/api/v1/pools/accounts") { bearerAuth(token) }.bodyAsText()
            assertFalse(raw.contains("Work") || raw.contains("work-id") || raw.contains("@"), raw)
            val own = member.capacity()
            assertEquals(30, own.reservedUnits); assertEquals(80, own.chargedAndReservedUnits)
        }
        // Another member's allowance stays administrator-only, from a member or from any key.
        for (token in listOf("member", "usage-key")) {
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/members/other-id/capacity") { bearerAuth(token) }.status, token)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/quotas") { bearerAuth(token) }.status, token)
        }
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/pools/accounts") { bearerAuth("inference-key") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/pools/accounts").status)
        val serialized = Serialization.json.decodeFromString<List<PoolAccountStatusView>>(client.get("/api/v1/pools/accounts") { bearerAuth("member") }.bodyAsText())
        assertEquals(2, serialized.size)
    }
}

private class PoolAdmission : AdmissionStore {
    private val now = System.currentTimeMillis()
    private fun account(id: String, name: String, priority: Int, email: String? = null) =
        AccountRecord(id, AccountIdentity("default", "anthropic", id, "org"), name, 1, false, "home", priority, AccountState.READY, email)
    private val observed = listOf(
        PoolAccountObservation(account("work-id", "Work", 10, "work@example.com"), 0, listOf(
            AccountQuota("work-id", "seven_day", .5, now, now + 86_400_000), AccountQuota("work-id", "five_hour", .2, now, now + 3_600_000))),
        PoolAccountObservation(account("spare-id", "Spare", 0), 0, listOf(AccountQuota("spare-id", "five_hour", .99, now, now + 3_600_000))),
    )
    override suspend fun poolAccounts(actor: Principal): List<PoolAccountStatus> {
        actor.requireScope(ServiceScope.USAGE_READ)
        return DescribePoolAccountsUseCase().describe(observed, now, revealIdentity = actor.serviceScopes == null && actor.member.role.isAdministrator)
    }
    override suspend fun capacity(actor: Principal, memberId: String): MemberCapacity {
        actor.requireScope(ServiceScope.USAGE_READ); if (memberId != actor.member.id) actor.requireAdmin()
        return MemberCapacity(MemberPolicy(memberId, 0, 2, "home"), 80, 1, now + 3_600_000, 500, reservedUnits = 30)
    }
    override suspend fun quotas(actor: Principal): List<AccountQuota> { actor.requireAdmin(); return emptyList() }
    override suspend fun preferredTarget(actor: Principal, affinity: RequestAffinity): RouteTarget? = error("unused")
    override suspend fun statistics(): AdmissionStatistics = AdmissionStatistics(0, 0)
    override suspend fun reserve(actor: Principal, units: Long, model: String, provider: String, excludedAccountIds: Set<String>, affinity: RequestAffinity?): Reservation = error("unused")
    override suspend fun settle(reservation: Reservation, actualUnits: Long?, completed: Boolean) = error("unused")
    override suspend fun configure(actor: Principal, policy: MemberPolicy): MemberPolicy = error("unused")
    override suspend fun poolBudgets(actor: Principal): List<PoolBudget> = error("unused")
    override suspend fun configurePool(actor: Principal, budget: PoolBudget): PoolBudget = error("unused")
    override suspend fun observe(accountId: String, observations: List<AccountQuota>) = error("unused")
    override suspend fun coolDown(accountId: String, untilMillis: Long, reconnect: Boolean) = error("unused")
}
