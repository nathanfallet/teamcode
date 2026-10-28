package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.automations.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*

/** `automations runs` and `list`: pages are followed up to a bound, filters reach the server. */
class AutomationHistoryTest {
    private fun run(n: Int) = AutomationRunView("r$n", "a1", 1, "cron", "succeeded", queuedAt = n.toLong())
    private fun client(urls: MutableList<String>, answer: (io.ktor.http.Url) -> String) = TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
        HttpClient(MockEngine { request -> urls += request.url.toString(); respond(answer(request.url), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }) { configure(this) }
    }

    @Test fun `the history follows the cursor until the limit and says there is more`() = runTest {
        val urls = mutableListOf<String>()
        val (runs, more) = client(urls) { url ->
            val first = url.parameters["before"] == null
            Serialization.json.encodeToString(AutomationRunPageView(if (first) listOf(run(3), run(2)) else listOf(run(1)), if (first) "r2" else null))
        }.use { listRuns(it, "a1", emptyList(), emptyList(), 10) }
        assertEquals(listOf("r3", "r2", "r1"), runs.map { it.id }); assertFalse(more)
        assertEquals(2, urls.size); assertContains(urls[1], "before=r2")

        val (cut, truncated) = client(mutableListOf()) { Serialization.json.encodeToString(AutomationRunPageView(listOf(run(3), run(2)), "r2")) }.use { listRuns(it, "a1", emptyList(), emptyList(), 2) }
        assertEquals(2, cut.size); assertTrue(truncated)
    }

    @Test fun `source and status filters are sent`() = runTest {
        val urls = mutableListOf<String>()
        client(urls) { Serialization.json.encodeToString(AutomationRunPageView(emptyList())) }.use { listRuns(it, "a1", listOf("webhook", "test"), listOf("failed"), 50) }
        assertContains(urls.single(), "source=webhook"); assertContains(urls.single(), "source=test"); assertContains(urls.single(), "status=failed")
    }

    @Test fun `the list asks for another owner when given`() = runTest {
        val urls = mutableListOf<String>()
        val (automations, more) = client(urls) { Serialization.json.encodeToString(AutomationPageView(emptyList())) }.use { listAutomations(it, "all") }
        assertTrue(automations.isEmpty()); assertFalse(more); assertContains(urls.single(), "owner=all")
    }
}
