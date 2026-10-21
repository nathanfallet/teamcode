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
        val admission = PoolAdmission()
        val verifier = object : AccountVerifier { override suspend fun verify(teamId: String, credentials: ProviderCredentials) = error("unused") }
        application { configureGateway(gateway, team = team, admission = admission, accounts = AccountManagement(NamedAccounts(admission.records), verifier), publicOrigin = "http://localhost") }
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
            assertTrue(accounts.all { it.id == null && it.name == null && it.email == null && it.poolId == null }, token)
            // The raw body carries no account name or identifier either.
            val raw = client.get("/api/v1/pools/accounts") { bearerAuth(token) }.bodyAsText()
            assertFalse(raw.contains("Work") || raw.contains("work-id") || raw.contains("@") || raw.contains("home"), raw)
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
        // The capacity page names observed windows' accounts with the email on a muted second line; a deleted account falls back to its id.
        val page = client.get("/dashboard/capacity") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        val windows = page.substringAfter("Observed provider windows")
        assertTrue(windows.contains("""<td>Work<br><small class="muted">work@example.com</small></td>"""), windows)
        assertTrue(windows.contains("<td>Spare</td>"), windows)
        // One row per account, at its first appearance (Spare .99, Work .5 then .2, deleted .1); Work's windows stack in the same cell.
        assertEquals(3, Regex("<tr><td>").findAll(windows).count(), windows)
        assertEquals(listOf("Spare", "Work", "deleted-id"), Regex("<tr><td>(?:<code>)?(Spare|Work|deleted-id)").findAll(windows).map { it.groupValues[1] }.toList(), windows)
        val work = windows.substringAfter("<td>Work<br>").substringBefore("</tr>")
        assertEquals(listOf("seven_day", "five_hour"), Regex("""<strong>(seven_day|five_hour)</strong>""").findAll(work).map { it.groupValues[1] }.toList(), work)
        assertEquals(2, Regex("""<small title="\d{4}-\d\d-\d\dT[^"]+Z">resets in (?:\d+d \d+h \d+m|\d+h \d+m|\d+m)</small>""").findAll(work).count(), work)
        assertFalse(windows.contains("work-id") || windows.contains("spare-id"), windows)
        assertTrue(windows.contains("<code>deleted-id</code>"), windows)
        val serialized = Serialization.json.decodeFromString<List<PoolAccountStatusView>>(client.get("/api/v1/pools/accounts") { bearerAuth("member") }.bodyAsText())
        assertEquals(2, serialized.size)
    }
}

private class NamedAccounts(private val records: List<AccountRecord>) : AccountStore {
    override suspend fun list(teamId: String) = records
    override suspend fun configure(author: AccountMutationActor, teamId: String, id: String, displayName: String, disabled: Boolean, priority: Int, poolId: String?) = error("unused")
    override suspend fun handover(author: AccountMutationActor, teamId: String, id: String, accessOnly: Boolean, requirePending: Boolean) = error("unused")
    override suspend fun credentials(teamId: String, id: String) = error("unused")
    override suspend fun find(identity: AccountIdentity): AccountRecord? = null
    override suspend fun upsert(author: AccountMutationActor, verified: VerifiedProviderAccount, credentials: ProviderCredentials, expectedVersion: Long?, operationId: String, origin: EnrollmentOrigin): EnrollmentResult = error("unused")
}
private class PoolAdmission : AdmissionStore {
    val records get() = observed.map { it.account }
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
    override suspend fun quotas(actor: Principal): List<AccountQuota> { actor.requireAdmin(); return observed.flatMap { it.windows } + AccountQuota("deleted-id", "five_hour", .1, now, now + 3_600_000) }
    override suspend fun preferredTarget(actor: Principal, affinity: RequestAffinity): RouteTarget? = error("unused")
    override suspend fun statistics(): AdmissionStatistics = AdmissionStatistics(0, 0)
    override suspend fun reserve(actor: Principal, units: Long, model: String, provider: String, excludedAccountIds: Set<String>, affinity: RequestAffinity?): Reservation = error("unused")
    override suspend fun settle(reservation: Reservation, actualUnits: Long?, completed: Boolean) = error("unused")
    override suspend fun configure(actor: Principal, policy: MemberPolicy): MemberPolicy = error("unused")
    override suspend fun poolBudgets(actor: Principal): List<PoolBudget> { actor.requireAdmin(); return listOf(PoolBudget("home", 1_000_000)) }
    override suspend fun configurePool(actor: Principal, budget: PoolBudget): PoolBudget = error("unused")
    override suspend fun observe(accountId: String, observations: List<AccountQuota>) = error("unused")
    override suspend fun coolDown(accountId: String, untilMillis: Long, reconnect: Boolean) = error("unused")
}

class RemainingLabelTest {
    @kotlin.test.Test
    fun formatsTimeLeftWithoutLeadingZeroUnits() {
        val minute = 60_000L
        assertEquals("2d 4h 12m", remainingLabel(((2 * 24 + 4) * 60 + 12) * minute, 0))
        assertEquals("4h 12m", remainingLabel((4 * 60 + 12) * minute, 0))
        assertEquals("12m", remainingLabel(12 * minute + 59_000, 0))
        assertEquals("1d 0h 5m", remainingLabel((24 * 60 + 5) * minute, 0))
        assertEquals("2h 0m", remainingLabel(120 * minute, 0))
        assertEquals("0m", remainingLabel(-5, 0))
    }
}
