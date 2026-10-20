package me.nathanfallet.teamcode.presentation

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class AgentRunRoutesTest {
    @Test fun typedClientScopesAndEscapedDashboardKeepLifecycleReceiptsNarrow() = testApplication {
        val id = "11111111-1111-1111-1111-111111111111"
        val policy = RunPolicy()
        var writes = 0
        val calls = mutableListOf<String>()
        fun run() = AgentRun(id, "admin-id", "<member>", "claude", "background", null, 1000, 2000, null, null, null,
            RunState.STALE, policy, 40, 1, 1, 0, false)
        val service = object : AgentRunService {
            override suspend fun start(actor: Principal, id: String, agent: String, workload: String): RunLease {
                actor.requireScope(ServiceScope.INFERENCE); AgentRunService.validateStart(id, agent, workload)
                assertEquals("claude", agent); assertEquals("background", workload); calls += "start"; return RunLease(id, 1000, 2000, null, RunState.RUNNING)
            }
            override suspend fun heartbeat(actor: Principal, id: String): RunLease { actor.requireScope(ServiceScope.INFERENCE); calls += "heartbeat"; return RunLease(id, 1000, 3000, null, RunState.RUNNING) }
            override suspend fun end(actor: Principal, id: String, exitCode: Int?): RunLease { actor.requireScope(ServiceScope.INFERENCE); assertEquals(7, exitCode); calls += "end"; return RunLease(id, 1000, 3000, 4000, RunState.ENDED) }
            override suspend fun list(actor: Principal, memberId: String?, all: Boolean, beforeId: String?): RunPage {
                actor.requireScope(ServiceScope.USAGE_READ); if (all || memberId != null) actor.requireAdmin(); calls += "list:$memberId:$all:$beforeId"
                return RunPage(listOf(run()), id)
            }
            override suspend fun get(actor: Principal, id: String): RunDetail { actor.requireScope(ServiceScope.USAGE_READ); calls += "get"; return RunDetail(run(), emptyList(), true) }
            override suspend fun configure(actor: Principal, id: String, policy: RunPolicy): AgentRun {
                actor.requireAdmin(); AgentRunService.validate(policy); writes++; return run().copy(policy = policy)
            }
        }
        val team = object : TeamService by FakeTeam() {
            override suspend fun authenticate(token: String): Principal? = when (token) {
                "inference" -> FakeTeam().authenticate("member")!!.copy(serviceScopes = setOf(ServiceScope.INFERENCE))
                "reader" -> FakeTeam().authenticate("member")!!.copy(serviceScopes = setOf(ServiceScope.USAGE_READ))
                "admin-service" -> FakeTeam().authenticate("admin")!!.copy(serviceScopes = ServiceScope.entries.toSet())
                else -> FakeTeam().authenticate(token)
            }
        }
        val gateway = object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("No inference expected")
        }
        application { configureGateway(gateway, team = team, runs = service, publicOrigin = "http://localhost") }
        TeamCodeClient("http://localhost", { "admin" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }.use { sdk ->
            assertEquals(id, sdk.startRun(StartRunRequest(id, "claude", "background")).id)
            assertEquals(3000, sdk.heartbeatRun(id).heartbeatAtMillis)
            assertEquals("ENDED", sdk.endRun(id, 7).state)
            assertEquals(id, sdk.runs(all = true, beforeId = id).nextBeforeId)
            assertEquals("<member>", sdk.run(id).run.username)
            assertTrue(sdk.run(id).observationOnly)
            assertEquals(120, sdk.saveRunPolicy(id, RunPolicyView(120, 2, true)).policy.budgetUnits)
            assertTrue(calls.contains("list:null:true:$id")); assertEquals(1, writes)
            assertTrue(sdk.capabilities().features.contains("team.runs.v1"))
        }
        val lease = client.post("/api/v1/runs") {
            bearerAuth("inference"); contentType(ContentType.Application.Json); setBody("""{"id":"$id","agent":"claude","workload":"background"}""")
        }
        assertEquals(HttpStatusCode.Created, lease.status)
        val receipt = lease.bodyAsText()
        assertFalse(receipt.contains("charged")); assertFalse(receipt.contains("token"))
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/runs") { bearerAuth("inference") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/runs") { bearerAuth("reader") }.status)
        for (token in listOf("reader", "member", "admin-service")) {
            assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/runs/$id/policy") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"budgetUnits":1}""")
            }.status)
        }
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/runs") {
            bearerAuth("reader"); contentType(ContentType.Application.Json); setBody("""{"id":"$id","agent":"claude"}""")
        }.status)
        val page = client.get("/dashboard/sessions/$id") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertTrue(page.contains("&lt;member&gt;")); assertFalse(page.contains("<member>"))
        assertTrue(page.contains("Observation-only mode")); assertTrue(page.contains("Save run limits"))
        assertFalse(client.get("/dashboard/sessions/$id") { header(HttpHeaders.Cookie, "teamcode_session=member") }.bodyAsText().contains("Save run limits"))
        for ((origin, csrf) in listOf("https://attacker.test" to "session", "http://localhost" to "wrong")) {
            assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/sessions/$id/policy") {
                header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, origin)
                contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=$csrf&budget=10&concurrent=1")
            }.status)
        }
        assertEquals(1, writes)
        assertEquals(HttpStatusCode.Found, client.post("/dashboard/sessions/$id/policy") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session&budget=10&concurrent=1")
        }.status)
        assertEquals(2, writes)
    }
}
