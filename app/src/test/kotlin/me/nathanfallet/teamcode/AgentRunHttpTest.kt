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

class AgentRunHttpTest {
    @Test @Timeout(120)
    fun runAwareHttpStreamingRechecksPoliciesFromAnotherReplicaAndNeverForwardsTeamMetadata() = runBlocking {
        AgentRunHttpFixture().use { fixture ->
            fixture.start()
            HttpClient(CIO) { followRedirects = false; install(HttpTimeout) { requestTimeoutMillis = 10000 } }.use { http ->
                val id = UUID.randomUUID().toString(); val a = fixture.origins[0]; val b = fixture.origins[1]
                val token = fixture.member.token
                assertEquals(HttpStatusCode.Created, http.post("$a/api/v1/runs") {
                    bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"id":"$id","agent":"claude","workload":"background"}""")
                }.status)
                suspend fun infer(ids: List<String> = listOf(id), workload: String = "background") = http.post("$a/v1/messages") {
                    bearerAuth(token); ids.forEach { header("x-teamcode-run-id", it) }; header("x-teamcode-workload", workload)
                    header("x-claude-code-session-id", "synthetic-native-session")
                    contentType(ContentType.Application.Json); setBody("""{"model":"claude-test","stream":true,"max_tokens":1,"messages":[{"role":"user","content":"synthetic"}]}""")
                }
                val first = infer(); assertEquals(HttpStatusCode.OK, first.status); assertTrue(first.bodyAsText().contains("message_stop"))
                suspend fun detail() = Json.parseToJsonElement(http.get("$b/api/v1/runs/$id") { bearerAuth(token) }.bodyAsText()).jsonObject
                val view = withTimeout(5000) {
                    var current = detail()
                    while (current.getValue("run").jsonObject.getValue("completed").jsonPrimitive.long != 1L) { delay(20); current = detail() }
                    current
                }
                assertEquals(6, view.getValue("run").jsonObject.getValue("chargedAndReservedUnits").jsonPrimitive.long)
                assertEquals(1, view.getValue("conversations").jsonArray.size)
                assertFalse(view.toString().contains("synthetic-native-session"))
                assertEquals(HttpStatusCode.BadRequest, infer(listOf(id, id)).status)
                assertEquals(HttpStatusCode.Conflict, infer(workload = "interactive").status)
                assertEquals(1, fixture.dispatches.get())
                assertEquals(HttpStatusCode.OK, http.put("$b/api/v1/runs/$id/policy") {
                    bearerAuth(fixture.admin.token); contentType(ContentType.Application.Json); setBody("""{"paused":true}""")
                }.status)
                assertEquals(HttpStatusCode.Forbidden, infer().status); assertEquals(1, fixture.dispatches.get())
                assertEquals(HttpStatusCode.OK, http.put("$b/api/v1/runs/$id/policy") {
                    bearerAuth(fixture.admin.token); contentType(ContentType.Application.Json); setBody("""{"budgetUnits":5}""")
                }.status)
                assertEquals(HttpStatusCode.TooManyRequests, infer().status); assertEquals(1, fixture.dispatches.get())
                assertEquals(HttpStatusCode.OK, http.post("$b/api/v1/runs/$id/end") {
                    bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"exitCode":0}""")
                }.status)
                assertEquals(HttpStatusCode.Conflict, infer().status); assertEquals(1, fixture.dispatches.get())
                val page = http.get("$a/dashboard/sessions/$id") { header(HttpHeaders.Cookie, "teamcode_session=$token") }
                assertEquals(HttpStatusCode.OK, page.status); assertTrue(page.bodyAsText().contains("ENDED"))
            }
        }
    }
}
