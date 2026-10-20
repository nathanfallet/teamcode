package me.nathanfallet.teamcode

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Timeout
import kotlin.test.*

class UsageReconciliationHttpTest {
    @Test @Timeout(120)
    fun missingTerminalUsageKeepsItsChargeUntilVerifiedCorrectionFromAnotherHttpReplica() = runBlocking {
        AgentRunHttpFixture(reportUsage = false).use { fixture ->
            fixture.start()
            HttpClient(CIO) { followRedirects = false; install(HttpTimeout) { requestTimeoutMillis = 10000 } }.use { http ->
                val a = fixture.origins[0]; val b = fixture.origins[1]; val run = UUID.randomUUID().toString()
                val member = fixture.member.token; val admin = fixture.admin.token
                assertEquals(HttpStatusCode.Created, http.post("$a/api/v1/runs") {
                    bearerAuth(member); contentType(ContentType.Application.Json); setBody("""{"id":"$run","agent":"claude","workload":"interactive"}""")
                }.status)
                val response = http.post("$a/v1/messages") {
                    bearerAuth(member); header("x-teamcode-run-id", run); header("request-id", "caller_must_not_choose_provider_identity")
                    contentType(ContentType.Application.Json); setBody("""{"model":"claude-test","stream":true,"max_tokens":1,"messages":[{"role":"user","content":"synthetic"}]}""")
                }
                assertEquals(HttpStatusCode.OK, response.status); assertTrue(response.bodyAsText().contains("message_stop"))
                assertNull(response.headers["request-id"])
                val id = assertNotNull(response.headers["x-teamcode-request-id"])
                suspend fun get(path: String, credential: String = admin) = http.get(b + path) { bearerAuth(credential) }
                val pending = withTimeout(10000) {
                    var rows = Json.parseToJsonElement(get("/api/v1/usage/pending").bodyAsText()).jsonObject.getValue("requests").jsonArray
                    while (rows.isEmpty()) { delay(20); rows = Json.parseToJsonElement(get("/api/v1/usage/pending").bodyAsText()).jsonObject.getValue("requests").jsonArray }
                    rows.single().jsonObject
                }
                assertEquals(id, pending.getValue("id").jsonPrimitive.content)
                assertEquals("req_fixture_anthropic", pending.getValue("providerRequestId").jsonPrimitive.content)
                assertTrue(pending.getValue("units").jsonPrimitive.long > 6)
                val before = Json.parseToJsonElement(get("/api/v1/capacity", member).bodyAsText()).jsonObject
                assertEquals(pending.getValue("units"), before.getValue("chargedAndReservedUnits"))
                assertEquals(HttpStatusCode.Forbidden, get("/api/v1/usage/pending", member).status)
                val body = buildJsonObject {
                    put("expectedUnits", pending.getValue("units")); put("actualUnits", 6); put("source", "PROVIDER_RECORD")
                    put("reference", "req_fixture_anthropic"); put("reason", "Verified synthetic provider usage"); put("expectedProviderRequestId", "req_fixture_anthropic")
                }.toString()
                suspend fun correct() = http.post("$b/api/v1/usage/reservations/$id/reconcile") {
                    bearerAuth(admin); contentType(ContentType.Application.Json); setBody(body)
                }
                val corrected = correct(); assertEquals(HttpStatusCode.OK, corrected.status)
                val record = Json.parseToJsonElement(corrected.bodyAsText()).jsonObject
                assertEquals(6, record.getValue("actualUnits").jsonPrimitive.long)
                assertEquals(record, Json.parseToJsonElement(correct().bodyAsText()).jsonObject)
                val capacity = Json.parseToJsonElement(http.get("$a/api/v1/capacity") { bearerAuth(member) }.bodyAsText()).jsonObject
                assertEquals(6, capacity.getValue("chargedAndReservedUnits").jsonPrimitive.long)
                val detail = Json.parseToJsonElement(http.get("$a/api/v1/runs/$run") { bearerAuth(member) }.bodyAsText()).jsonObject.getValue("run").jsonObject
                assertEquals(6, detail.getValue("chargedAndReservedUnits").jsonPrimitive.long)
                assertEquals(0, detail.getValue("completed").jsonPrimitive.long, "Verified billing is not proof of delivered completion")
                assertEquals(1, fixture.dispatches.get(), "Reconciliation must never replay the provider request")
                assertTrue(Json.parseToJsonElement(get("/api/v1/usage/pending").bodyAsText()).jsonObject.getValue("requests").jsonArray.isEmpty())
                assertEquals(1, Json.parseToJsonElement(get("/api/v1/usage/reconciliations").bodyAsText()).jsonObject.getValue("reconciliations").jsonArray.size)
                val page = http.get("$a/dashboard/usage/$id") { header(HttpHeaders.Cookie, "teamcode_session=$admin") }
                assertEquals(HttpStatusCode.OK, page.status); assertTrue(page.bodyAsText().contains("Completion counters remain unchanged"))
            }
        }
    }
}
