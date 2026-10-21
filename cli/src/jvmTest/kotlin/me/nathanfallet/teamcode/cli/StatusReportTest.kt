package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.cli.status.StatusReport
import me.nathanfallet.teamcode.cli.status.readStatusReport
import me.nathanfallet.teamcode.cli.status.renderStatus
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
        assertEquals("/api/v1/status", calls.first())
        assertEquals(4, calls.size)
        assertEquals(setOf("/api/v1/capacity", "/api/v1/directives/current", "/api/v1/pools/accounts"), calls.drop(1).toSet())
        assertFalse(calls.any { it.startsWith("/api/v1/members/") || it == "/api/v1/quotas" || it == "/api/v1/accounts" })
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
}
