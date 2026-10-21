package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.cli.status.readStatusReport
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
        val calls = mutableListOf<String>()
        val accounts = "/api/v1/pools/accounts" to (HttpStatusCode.OK to """[{"label":"Account 1","provider":"anthropic","state":"READY","disabled":false,"usable":true}]""")
        val directive = "/api/v1/directives/current" to (HttpStatusCode.OK to """{"directive":null}""")
        val report = sdk(mapOf(gateway, capacity, accounts, directive), calls).use { readStatusReport(it) }
        assertEquals(4, report.capacity?.reservedUnits); assertEquals("Account 1", report.accounts?.single()?.label); assertNull(report.pacing)
        assertEquals(listOf("/api/v1/status", "/api/v1/capacity", "/api/v1/directives/current", "/api/v1/pools/accounts"), calls)
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
        assertEquals(500, assertFailsWith<TeamCodeException> { sdk(mapOf(gateway, failing), mutableListOf()).use { readStatusReport(it) } }.statusCode)
    }
}
