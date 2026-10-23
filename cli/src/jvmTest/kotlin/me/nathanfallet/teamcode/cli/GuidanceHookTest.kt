package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import io.ktor.server.routing.routing
import me.nathanfallet.teamcode.cli.proxy.guidanceHook
import me.nathanfallet.teamcode.cli.proxy.localProxy
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class GuidanceHookTest {
    private fun state(body: String) = Json.parseToJsonElement(body).jsonObject["state"]!!.jsonPrimitive.content
    private val localToken = "synthetic-guidance-local-capability-1234"
    @Test fun sessionOnlyClaimAndReceiptNeverTransmitToolContent() = testApplication {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var emitted = false
        val sdk = TeamCodeClient("https://team.example", { "server-secret" }) { configure -> HttpClient(MockEngine { request ->
            assertEquals("Bearer server-secret", request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            calls += request.url.encodedPath to body
            assertFalse(body.toString().contains("private-tool-input"))
            val response = when (request.url.encodedPath) {
                "/api/v1/directives/claim" -> if (emitted) """{"directive":null}""" else """{"claimId":"claim","directive":{"id":"directive","pace":"SLOW_DOWN","maxConcurrent":1,"minIntervalMillis":10000,"message":"Reduce optional parallel work.","createdAtMillis":1,"expiresAtMillis":9223372036854775807}}"""
                "/api/v1/directives/receipt" -> { emitted = true; """{"success":true}""" }
                else -> error("Unexpected management call")
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { configure(this) } }
        application { localProxy(sdk, localToken) }
        try {
            suspend fun hook(event: String = "PreToolUse", content: String = "private-tool-input") = client.post("/_teamcode/hooks/claude") {
                bearerAuth(localToken); contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("session_id", "test-session"); put("hook_event_name", event); put("tool_input", content); put("transcript_path", "private-tool-input") }.toString())
            }
            val first = hook().bodyAsText()
            assertTrue(first.contains("Reduce optional parallel work."))
            assertFalse(first.contains("permissionDecision"))
            assertEquals(setOf("sessionId", "stage"), calls.first().second.keys)
            assertEquals("claim", calls.last().second["claimId"]!!.jsonPrimitive.content)
            assertEquals("{}", hook("UserPromptSubmit").bodyAsText())
            val count = calls.size
            assertEquals("{}", hook(content = "x".repeat(65_537)).bodyAsText())
            assertEquals("{}", hook("Stop").bodyAsText())
            assertEquals(count, calls.size)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/_teamcode/hooks/claude").status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/_teamcode/hooks/claude") { bearerAuth(localToken); header(HttpHeaders.Origin, "https://browser.example") }.status)
        } finally { sdk.close() }
    }
    @Test fun unavailableGuidanceReturnsEmptyContextWithoutProviderDiagnostics() = testApplication {
        val sdk = TeamCodeClient("https://team.example", { "token" }) { configure -> HttpClient(MockEngine {
            respond("sensitive-upstream-diagnostic", HttpStatusCode.ServiceUnavailable)
        }) { configure(this) } }
        application { localProxy(sdk, localToken) }
        try {
            val response = client.post("/_teamcode/hooks/claude") {
                bearerAuth(localToken); contentType(ContentType.Application.Json)
                setBody("""{"session_id":"test","hook_event_name":"PreToolUse"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("{}", response.bodyAsText())
        } finally { sdk.close() }
    }
    @Test fun activityIsReportedOnceFromTheLaunchHeaderWithoutAnyContent() = testApplication {
        val run = "11111111-2222-3333-4444-555555555555"
        val reports = java.util.Collections.synchronizedList(mutableListOf<String>())
        var failWith: HttpStatusCode? = null
        val sdk = TeamCodeClient("https://team.example", { "server-secret" }) { configure -> HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/runs/$run/activity" -> {
                    val text = (request.body as TextContent).text
                    reports += text
                    failWith?.let { return@MockEngine respond("""{"error":"x"}""", it, headersOf(HttpHeaders.ContentType, "application/json")) }
                    respond("""{"id":"$run","state":"working","sinceMillis":1}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> respond("""{"directive":null}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }) { configure(this) } }
        application { routing { guidanceHook(sdk, localToken, activityRetryDelays = listOf(10.milliseconds, 10.milliseconds)) } }
        suspend fun hook(payload: JsonObjectBuilder.() -> Unit, launch: String? = run) = client.post("/_teamcode/hooks/claude") {
            bearerAuth(localToken); contentType(ContentType.Application.Json)
            launch?.let { header("X-TeamCode-Run", it) }
            setBody(buildJsonObject { put("session_id", "test-session"); put("transcript_path", "private-path"); payload() }.toString())
        }
        suspend fun settled(count: Int) = withContext(Dispatchers.Default) { withTimeout(5_000) { while (reports.size < count) delay(10) } }
        try {
            assertEquals("{}", hook({ put("hook_event_name", "Stop"); put("last_assistant_message", "private-message") }).bodyAsText())
            settled(1)
            assertEquals("waiting_for_human", state(reports.single()))
            // Only the state and when the hook fired: nothing else of the payload.
            assertEquals(setOf("state", "observedAtMillis"), Json.parseToJsonElement(reports.single()).jsonObject.keys)
            // The same state again, from any event, is not sent again.
            hook({ put("hook_event_name", "Notification"); put("notification_type", "permission_prompt"); put("message", "private") })
            hook({ put("hook_event_name", "Notification"); put("notification_type", "auth_success") })
            hook({ put("hook_event_name", "PreToolUse"); put("tool_input", "private-tool-input") })
            hook({ put("hook_event_name", "PostToolUse"); put("tool_name", "Bash"); put("tool_response", "private") })
            settled(2)
            assertEquals("working", state(reports[1]))
            hook({ put("hook_event_name", "PostToolUse") })
            hook({ put("hook_event_name", "Stop") })
            settled(3)
            assertEquals(3, reports.size)
            // Neither a missing nor a malformed launch header reports anything.
            hook({ put("hook_event_name", "Stop") }, launch = null)
            hook({ put("hook_event_name", "Stop") }, launch = "../etc")
            // A refusal the server will repeat is remembered; an outage is retried, and a newer state replaces the retry.
            failWith = HttpStatusCode.ServiceUnavailable
            hook({ put("hook_event_name", "UserPromptSubmit") })
            // The first try and two bounded retries, all of the same state and hook time.
            settled(6)
            withContext(Dispatchers.Default) { delay(200) }
            assertEquals(6, reports.size)
            assertEquals(1, reports.drop(3).map { Json.parseToJsonElement(it).jsonObject }.toSet().size)
            failWith = HttpStatusCode.Conflict
            hook({ put("hook_event_name", "UserPromptSubmit") })
            settled(7)
            hook({ put("hook_event_name", "UserPromptSubmit") })
            // The launch is refused for good: no other state is tried either.
            hook({ put("hook_event_name", "Stop") })
            withContext(Dispatchers.Default) { delay(300) }
            assertEquals(7, reports.size)
            assertTrue(reports.none { "private" in it })
        } finally { sdk.close() }
    }
    @Test fun anOversizedEventIsReportedAndAForeignCredentialIsSaidOnce() = testApplication {
        val run = "11111111-2222-3333-4444-555555555555"
        val reports = java.util.Collections.synchronizedList(mutableListOf<String>())
        val notices = java.util.Collections.synchronizedList(mutableListOf<String>())
        var status = HttpStatusCode.OK
        val sdk = TeamCodeClient("https://team.example", { "server-secret" }) { configure -> HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/v1/runs/$run/activity") {
                reports += (request.body as TextContent).text
                respond(if (status == HttpStatusCode.OK) """{"id":"$run","state":"working","sinceMillis":1}""" else """{"error":"x"}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
            } else respond("""{"directive":null}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { configure(this) } }
        application { localProxy(sdk, localToken, report = { notices += it }) }
        suspend fun hook(event: String, big: Boolean = false) = client.post("/_teamcode/hooks/claude") {
            bearerAuth(localToken); contentType(ContentType.Application.Json); header("X-TeamCode-Run", run)
            setBody("""{"session_id":"s","hook_event_name":"$event","tool_response":"${if (big) "x".repeat(200_000) else ""}"}""")
        }
        suspend fun settled(count: Int) = withContext(Dispatchers.Default) { withTimeout(5_000) { while (reports.size < count) delay(10) } }
        try {
            assertEquals("{}", hook("PostToolUse", big = true).bodyAsText())
            settled(1)
            assertEquals("working", state(reports.single()))
            status = HttpStatusCode.Forbidden
            hook("Stop"); settled(2)
            hook("UserPromptSubmit"); hook("Stop")
            withContext(Dispatchers.Default) { delay(300) }
            assertEquals(1, notices.size)
            assertTrue("403" in notices.single() && "credential" in notices.single())
        } finally { sdk.close() }
    }
}
