package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.teamcode.cli.status.StatusReport
import me.nathanfallet.teamcode.cli.status.readStatusReport
import me.nathanfallet.teamcode.cli.status.renderStatus
import me.nathanfallet.teamcode.cli.status.statusJson
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import kotlin.test.*

class StatusReportTest {
    private fun sdk(routes: Map<String, Pair<HttpStatusCode, String>>, calls: MutableList<String>) = TeamCodeClient("https://team.example", { "token" }) { configure ->
        HttpClient(MockEngine { request ->
            calls += request.url.encodedPath
            val (status, body) = routes[request.url.encodedPath] ?: (HttpStatusCode.NotFound to """{"error":{"type":"not_found","message":"Not found."}}""")
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { configure(this) }
    }
    private val gateway = "/api/v1/status" to (HttpStatusCode.OK to """{"mode":"pool","activeRequests":1,"completedRequests":2}""")
    private val capacity = "/api/v1/capacity" to (HttpStatusCode.OK to """{"memberId":"m","dailyUnits":100,"maxConcurrent":2,"poolId":null,"chargedAndReservedUnits":10,"activeRequests":1,"resetsAtMillis":5,"reservedUnits":4}""")

    @Test fun readsOnlyTheCallersOwnRoutes() = runTest {
        // The MockEngine answers on its own threads: the list is shared by concurrent requests.
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val accounts = "/api/v1/pools/accounts" to (HttpStatusCode.OK to """[{"label":"Account 1","provider":"anthropic","state":"READY","disabled":false,"usable":true}]""")
        val directive = "/api/v1/directives/current" to (HttpStatusCode.OK to """{"directive":null}""")
        val report = sdk(mapOf(gateway, capacity, accounts, directive), calls).use { readStatusReport(it) }
        assertEquals(4, report.capacity?.reservedUnits); assertEquals("Account 1", report.accounts?.single()?.label); assertNull(report.pacing)
        // The gateway is read first; the three sections are read concurrently, so their order is not part of the contract.
        val seen = synchronized(calls) { calls.toList() }
        assertEquals("/api/v1/status", seen.first())
        assertEquals(4, seen.size)
        assertEquals(setOf("/api/v1/capacity", "/api/v1/directives/current", "/api/v1/pools/accounts"), seen.drop(1).toSet())
        assertFalse(seen.any { it.startsWith("/api/v1/members/") || it == "/api/v1/quotas" || it == "/api/v1/accounts" })
    }

    @Test fun olderServersAndNarrowKeysStillShowWhatTheyServe() = runTest {
        val forbidden = HttpStatusCode.Forbidden to """{"error":{"type":"credential_scope","message":"No."}}"""
        val report = sdk(mapOf(gateway, "/api/v1/directives/current" to forbidden), mutableListOf()).use { readStatusReport(it) }
        assertNull(report.capacity); assertNull(report.accounts); assertNull(report.pacing)
        val broken = HttpStatusCode.InternalServerError to """{"error":{"type":"internal","message":"Boom."}}"""
        val partial = sdk(mapOf(gateway, capacity, "/api/v1/pools/accounts" to broken, "/api/v1/directives/current" to broken), mutableListOf()).use { readStatusReport(it) }
        assertEquals(4, partial.capacity?.reservedUnits, "A failing extra never hides the allowance"); assertNull(partial.accounts); assertNull(partial.pacing)
        val failing = "/api/v1/capacity" to (HttpStatusCode.InternalServerError to """{"error":{"type":"internal","message":"Boom."}}""")
        val noCapacity = sdk(mapOf(gateway, failing), mutableListOf()).use { readStatusReport(it) }
        assertNull(noCapacity.capacity, "A failing quota read never fails status"); assertEquals("pool", noCapacity.gateway.mode)
        assertEquals("read_failed", noCapacity.unavailable["capacity"]); assertEquals("not_served", noCapacity.unavailable["accounts"])
        assertTrue(renderStatus(noCapacity, 0).contains("Your quota: unavailable (the read failed; try again)."))
    }

    @Test fun anOlderServerWithoutTheQuotaRoutesStillShowsTheGateway() = runTest {
        val report = sdk(mapOf(gateway), mutableListOf()).use { readStatusReport(it) }
        assertNull(report.capacity); assertNull(report.accounts); assertNull(report.pacing)
        assertEquals(mapOf("capacity" to "not_served", "pacing" to "not_served", "accounts" to "not_served"), report.unavailable)
        val text = renderStatus(report, 0)
        assertTrue(text.startsWith("Mode: pool | Active: 1 | Waiting: 0 | Completed: 2"), text)
        assertTrue(text.contains("Your quota: unavailable from this server or credential."), text)
        assertTrue(text.endsWith("Pool accounts: unavailable from this server or credential."), text)
    }

    @Test fun anUnexpectedBodyLeavesTheSectionOutInsteadOfFailingStatus() = runTest {
        // The shape a test fixture or a misrouting proxy answers unknown GETs with: valid JSON, not these models.
        val other = HttpStatusCode.OK to """{"serverVersion":"test","mode":"fixture","features":[]}"""
        val routes = listOf("/api/v1/capacity", "/api/v1/directives/current", "/api/v1/pools/accounts")
        val unexpected = sdk(mapOf(gateway) + routes.associateWith { other }, mutableListOf()).use { readStatusReport(it) }
        assertNull(unexpected.capacity); assertNull(unexpected.accounts)
        assertEquals("unexpected_response", unexpected.unavailable["capacity"]); assertEquals("unexpected_response", unexpected.unavailable["accounts"])
        assertTrue(renderStatus(unexpected, 0).contains("Your quota: unavailable (unexpected response from the server)."))
        val broken = sdk(mapOf(gateway) + routes.associateWith { HttpStatusCode.OK to "not-json{" }, mutableListOf()).use { readStatusReport(it) }
        assertNull(broken.capacity); assertNull(broken.accounts); assertNull(broken.pacing)
        assertEquals(setOf("unexpected_response"), broken.unavailable.values.toSet())
        // One wrong route only hides its own section; a pacing failure is shown as unknown, never as "none".
        val pacingOnly = sdk(mapOf(gateway, capacity, "/api/v1/directives/current" to (HttpStatusCode.OK to "[]"),
            "/api/v1/pools/accounts" to (HttpStatusCode.OK to "[]")), mutableListOf()).use { readStatusReport(it) }
        assertEquals(4, pacingOnly.capacity?.reservedUnits); assertEquals(emptyList(), pacingOnly.accounts)
        assertEquals(mapOf("pacing" to "unexpected_response"), pacingOnly.unavailable)
        assertTrue(renderStatus(pacingOnly, 0).contains("Pacing      unavailable (unexpected response from the server)"))
    }

    @Test fun aNetworkFailureOnAnExtraIsReportedAsAFailedRead() = runTest {
        val client = TeamCodeClient("https://team.example", { "token" }) { configure ->
            HttpClient(MockEngine { request ->
                if (request.url.encodedPath == "/api/v1/status") respond(gateway.second.second, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else throw kotlinx.io.IOException("Connection reset.")
            }) { configure(this) }
        }
        val report = client.use { readStatusReport(it) }
        assertEquals(setOf("read_failed"), report.unavailable.values.toSet()); assertEquals(3, report.unavailable.size)
    }

    @Test fun cancellationIsNeverTurnedIntoAnUnavailableSection() = runTest {
        val reached = CompletableDeferred<Unit>()
        val client = TeamCodeClient("https://team.example", { "token" }) { configure ->
            HttpClient(MockEngine { request ->
                if (request.url.encodedPath == "/api/v1/status") respond(gateway.second.second, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else { reached.complete(Unit); awaitCancellation() }
            }) { configure(this) }
        }
        var report: StatusReport? = null
        val job = launch { report = client.use { readStatusReport(it) } }
        reached.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled); assertNull(report, "A cancelled status must not complete with unavailable sections")
    }

    @Test fun theGatewayReadStillFailsStatus() = runTest {
        val down = "/api/v1/status" to (HttpStatusCode.InternalServerError to """{"error":{"type":"internal","message":"Boom."}}""")
        assertEquals(500, assertFailsWith<TeamCodeException> { sdk(mapOf(down, capacity), mutableListOf()).use { readStatusReport(it) } }.statusCode)
    }

    // Bodies written out as the server sends them: every DTO field present, nulls explicit (PoolAccountRoutesTest).
    private val fullGateway = """{"mode":"pool","activeRequests":1,"completedRequests":2,"queuedRequests":3}"""
    private val fullCapacity = """{"memberId":"m","dailyUnits":100,"maxConcurrent":2,"poolId":null,"chargedAndReservedUnits":10,"activeRequests":1,""" +
        """"resetsAtMillis":5,"hardCapUnits":0,"weight":1,"poolTotalWeight":1,"poolDailyUnits":1000000,"queuedRequests":0,""" +
        """"groups":[{"id":"g","name":"Support","dailyUnits":0,"maxConcurrent":0,"paused":false,"chargedAndReservedUnits":0,"activeRequests":0}],""" +
        """"budgetOverride":null,"baseDailyUnits":100,"allowSharedOverflow":false,"reservedUnits":4}"""
    private val fullAccounts = "[" + listOf(
        // Observed windows, one whose reset the provider did not report.
        """{"label":"Account 1","provider":"anthropic","state":"READY","disabled":false,"usable":true,"unavailableReason":null,"cooldownUntilMillis":null,""" +
            """"windows":[{"window":"five_hour","utilization":0.42,"observedAtMillis":1000,"resetsAtMillis":9000},""" +
            """{"window":"seven_day","utilization":0.1,"observedAtMillis":1000,"resetsAtMillis":null}],"poolId":null,"id":null,"name":null,"email":null}""",
        // Nothing observed: no window, rather than a guessed 0%.
        """{"label":"Account 2","provider":"openai","state":"READY","disabled":false,"usable":true,"unavailableReason":null,"cooldownUntilMillis":null,""" +
            """"windows":[],"poolId":null,"id":null,"name":null,"email":null}""",
        """{"label":"Account 3","provider":"anthropic","state":"READY","disabled":false,"usable":false,"unavailableReason":"cooling_down","cooldownUntilMillis":7000,""" +
            """"windows":[],"poolId":null,"id":null,"name":null,"email":null}""",
    ).joinToString(",") + "]"
    private val fullDirective = """{"id":"d","pace":"SLOW","maxConcurrent":1,"minIntervalMillis":500,"message":"Slow down","createdAtMillis":1,"expiresAtMillis":9}"""
    private suspend fun printedStatus(routes: Map<String, Pair<HttpStatusCode, String>>) =
        Json.parseToJsonElement(statusJson(sdk(routes, java.util.Collections.synchronizedList(mutableListOf())).use { readStatusReport(it) })).jsonObject

    @Test fun theJsonViewIsTheApiBodiesFieldForField() = runTest {
        val printed = printedStatus(mapOf(
            "/api/v1/status" to (HttpStatusCode.OK to fullGateway), "/api/v1/capacity" to (HttpStatusCode.OK to fullCapacity),
            "/api/v1/pools/accounts" to (HttpStatusCode.OK to fullAccounts), "/api/v1/directives/current" to (HttpStatusCode.OK to """{"directive":$fullDirective}"""),
        ))
        assertEquals(Json.parseToJsonElement(fullGateway), printed["gateway"])
        assertEquals(Json.parseToJsonElement(fullCapacity), printed["capacity"], "The allowance is the API's, field for field")
        assertEquals(Json.parseToJsonElement(fullAccounts), printed["accounts"], "The accounts are the API's, field for field")
        assertEquals(Json.parseToJsonElement(fullDirective), printed["pacing"], "Pacing is the directive of /directives/current")
        assertEquals(JsonObject(emptyMap()), printed["unavailable"])
    }

    @Test fun theJsonViewKeepsUnknownValuesUnknown() = runTest {
        val gateway = "/api/v1/status" to (HttpStatusCode.OK to fullGateway)
        val printed = printedStatus(mapOf(gateway, "/api/v1/capacity" to (HttpStatusCode.OK to fullCapacity),
            "/api/v1/pools/accounts" to (HttpStatusCode.OK to fullAccounts), "/api/v1/directives/current" to (HttpStatusCode.OK to """{"directive":null}""")))
        val accounts = printed.getValue("accounts").jsonArray.map { it.jsonObject }
        assertEquals(JsonNull, accounts[0].getValue("windows").jsonArray[1].jsonObject["resetsAtMillis"], "An unreported reset stays null")
        assertEquals(JsonArray(emptyList()), accounts[1]["windows"], "An account without observation has no window")
        assertEquals(JsonNull, accounts[1]["cooldownUntilMillis"])
        assertEquals(JsonNull, printed["pacing"]); assertFalse("pacing" in printed.getValue("unavailable").jsonObject, "No advice is not a failed read")
        // A section the server or key does not serve is null with its reason, never a default value.
        val narrow = printedStatus(mapOf(gateway))
        assertEquals(JsonNull, narrow["capacity"]); assertEquals(JsonNull, narrow["accounts"]); assertEquals(JsonNull, narrow["pacing"])
        assertEquals("not_served", narrow.getValue("unavailable").jsonObject.getValue("capacity").jsonPrimitive.content)
    }
}
