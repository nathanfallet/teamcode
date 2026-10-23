package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.cli.agents.ActivityReporter
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class ActivityReporterTest {
    private val run = "11111111-2222-3333-4444-555555555555"
    private class Report(val state: String, val observedAtMillis: Long)

    /** A client whose activity endpoint answers [answer] for the n-th report (0-based), recording each one. */
    private fun client(reports: MutableList<Report>, answer: suspend (Int) -> Pair<HttpStatusCode, String>) =
        TeamCodeClient("https://team.example", { "token" }) { configure -> HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val index = synchronized(reports) { reports += Report(body["state"]!!.jsonPrimitive.content, body["observedAtMillis"]!!.jsonPrimitive.long); reports.size - 1 }
            val (status, text) = answer(index)
            respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { configure(this) } }

    private fun ok(state: String, superseded: Boolean = false) =
        HttpStatusCode.OK to """{"id":"$run","state":"$state","sinceMillis":1,"superseded":$superseded}"""

    @Test fun aTemporaryFailureIsSentAgainWithTheTimeItsHookFired() = runBlocking {
        val reports = mutableListOf<Report>()
        client(reports) { if (it == 0) HttpStatusCode.ServiceUnavailable to """{"error":"x"}""" else ok("waiting_for_human") }.use { sdk ->
            ActivityReporter(sdk, retryDelays = listOf(20.milliseconds, 20.milliseconds)).report(run, "waiting_for_human")
        }
        assertEquals(listOf("waiting_for_human", "waiting_for_human"), reports.map { it.state })
        assertEquals(reports[0].observedAtMillis, reports[1].observedAtMillis, "The retry keeps its hook's time, so a newer report still wins on the server")
    }

    @Test fun retriesAreBoundedAndThenLeftToTheNextEvent() = runBlocking {
        val reports = mutableListOf<Report>()
        client(reports) { HttpStatusCode.ServiceUnavailable to """{"error":"x"}""" }.use { sdk ->
            ActivityReporter(sdk, retryDelays = listOf(10.milliseconds, 10.milliseconds)).report(run, "working")
        }
        assertEquals(3, reports.size)
    }

    @Test fun aNewerStateArrivedDuringTheFailedReportReplacesItsRetry() = runBlocking {
        val reports = mutableListOf<Report>()
        lateinit var reporter: ActivityReporter
        client(reports) { index ->
            // While the first report is in flight, the agent's next hook arrives and is queued behind it.
            if (index == 0) { reporter.report(run, "working"); HttpStatusCode.GatewayTimeout to """{"error":"x"}""" } else ok("working")
        }.use { sdk ->
            reporter = ActivityReporter(sdk, retryDelays = listOf(10.milliseconds, 10.milliseconds))
            reporter.report(run, "waiting_for_human")
        }
        assertEquals(listOf("waiting_for_human", "working"), reports.map { it.state }, "The older state is not sent after the newer one")
        assertTrue(reports[1].observedAtMillis >= reports[0].observedAtMillis)
    }

    @Test fun aReportTheServerFoundOlderIsNeitherRetriedNorRemembered() = runBlocking {
        val reports = mutableListOf<Report>()
        client(reports) { index -> if (index == 0) ok("working", superseded = true) else ok("waiting_for_human") }.use { sdk ->
            val reporter = ActivityReporter(sdk, retryDelays = listOf(10.milliseconds))
            reporter.report(run, "waiting_for_human")
            assertEquals(1, reports.size)
            // Not remembered as sent: the same state from the next hook is reported.
            reporter.report(run, "waiting_for_human")
        }
        assertEquals(2, reports.size)
    }
}
