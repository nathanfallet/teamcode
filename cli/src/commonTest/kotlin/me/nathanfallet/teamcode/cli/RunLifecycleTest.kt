package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.client.RunLifecycleClient
import me.nathanfallet.teamcode.client.TeamCodeException
import me.nathanfallet.teamcode.cli.cloud.parseLabels
import me.nathanfallet.teamcode.cli.launch.agentLaunch
import me.nathanfallet.teamcode.cli.launch.trackedRun
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RunLifecycleTest {
    private open class Fake : RunLifecycleClient {
        var input: StartRunRequest? = null
        var heartbeats = 0
        val ended = mutableListOf<Pair<String, Int?>>()
        override suspend fun startRun(input: StartRunRequest): RunLeaseView { this.input = input; return RunLeaseView(input.id, 0, 0, null, "RUNNING") }
        override suspend fun heartbeatRun(id: String): RunLeaseView { heartbeats++; assertEquals(input!!.id, id); return RunLeaseView(id, 0, 1, null, "RUNNING") }
        override suspend fun endRun(id: String, exitCode: Int?): RunLeaseView { ended += id to exitCode; return RunLeaseView(id, 0, 1, 2, "ENDED") }
    }
    @Test fun wrapperHeartbeatsThenReportsTheActualExitBeforeReleasingItsScope() = runTest {
        val client = Fake(); val reports = mutableListOf<String>()
        assertEquals(7, trackedRun(client, "claude", "background", true, 20, reports::add) { id ->
            assertEquals(client.input!!.id, id); assertTrue(RunIdentifiers.isValid(id!!)); delay(65); 7
        })
        assertEquals("claude", client.input!!.agent); assertEquals("background", client.input!!.workload)
        assertEquals(3, client.heartbeats); assertEquals(listOf<Pair<String, Int?>>(client.input!!.id to 7), client.ended)
        advanceTimeBy(100000); assertEquals(3, client.heartbeats); assertTrue(reports.isEmpty())
    }
    @Test fun labelsAreSentWithTheStartRequest() = runTest {
        val client = Fake()
        trackedRun(client, "claude", "interactive", true, report = {}, labels = parseLabels(listOf("task=1234", "note=a=b", "empty="))) { 0 }
        assertEquals(mapOf("task" to "1234", "note" to "a=b", "empty" to ""), client.input!!.labels)
        val unlabeled = Fake()
        trackedRun(unlabeled, "codex", "interactive", true, report = {}) { 0 }
        assertEquals(emptyMap(), unlabeled.input!!.labels)
    }
    @Test fun labelOptionsUseTheCloudSyntax() {
        assertEquals(emptyMap(), parseLabels(emptyList()))
        assertEquals(mapOf("a" to "b=c"), parseLabels(listOf("a=b=c")))
        assertEquals(mapOf("a" to "2"), parseLabels(listOf("a=1", "a=2")), "The last value of a repeated key wins, as for cloud sessions")
        for (bad in listOf("=value", "")) assertFailsWith<com.github.ajalt.clikt.core.UsageError> { parseLabels(listOf(bad)) }
    }
    @Test fun theRunIdentifierIsExportedToBothAgentsAndAnInheritedOneIsRemovedWithoutARun() {
        val id = "11111111-1111-1111-1111-111111111111"; val token = "synthetic-local-token-long-enough"
        for (agent in listOf("claude", "codex")) {
            assertEquals(id, agentLaunch(agent, emptyList(), 1234, token, runId = id).environment["TEAMCODE_RUN_ID"], agent)
            val legacy = agentLaunch(agent, emptyList(), 1234, token).environment
            assertTrue(legacy.containsKey("TEAMCODE_RUN_ID") && legacy["TEAMCODE_RUN_ID"] == null, "A stale identifier from an outer launch is not inherited ($agent)")
        }
    }
    @Test fun cancelledWrapperClosesItsOwnMetadataAndPreservesCancellation() = runTest {
        val client = Fake()
        assertFailsWith<CancellationException> {
            trackedRun(client, "codex", "interactive", true, 20, {}) { throw CancellationException("synthetic caller cancellation") }
        }
        assertEquals(listOf<Pair<String, Int?>>(client.input!!.id to null), client.ended)
        val slowEnd = object : Fake() {
            override suspend fun endRun(id: String, exitCode: Int?): RunLeaseView { super.endRun(id, exitCode); awaitCancellation() }
        }
        val reports = mutableListOf<String>()
        assertEquals(0, trackedRun(slowEnd, "codex", "interactive", true, 20, reports::add) { 0 })
        assertEquals(1, slowEnd.ended.size); assertTrue(reports.single().contains("stale"))
    }
    @Test fun lostStartResponseIsClosedWithoutLaunchingAndHeartbeatFailuresAreRedactedAndBounded() = runTest {
        val uncertain = object : Fake() {
            override suspend fun startRun(input: StartRunRequest): RunLeaseView { super.startRun(input); error("SYNTHETIC_REMOTE_SECRET") }
        }
        val reports = mutableListOf<String>()
        assertFailsWith<IllegalStateException> { trackedRun(uncertain, "claude", "interactive", true, 20, reports::add) { error("Must not launch") } }
        assertEquals(listOf<Pair<String, Int?>>(uncertain.input!!.id to null), uncertain.ended); assertTrue(reports.isEmpty())
        val offline = object : Fake() {
            override suspend fun heartbeatRun(id: String): RunLeaseView { heartbeats++; error("SYNTHETIC_REMOTE_SECRET") }
        }
        assertEquals(0, trackedRun(offline, "claude", "interactive", true, 20, reports::add) { delay(65); 0 })
        assertEquals(3, offline.heartbeats); assertEquals(1, reports.size); assertFalse(reports.single().contains("SYNTHETIC_REMOTE_SECRET"))
        val revoked = object : Fake() {
            override suspend fun heartbeatRun(id: String): RunLeaseView { heartbeats++; throw TeamCodeException(401, "SYNTHETIC_REMOTE_SECRET") }
        }
        trackedRun(revoked, "claude", "interactive", true, 20, {}) { delay(65); 0 }
        assertEquals(1, revoked.heartbeats, "Do not retry permanently refused heartbeats")
    }
    @Test fun legacyServersKeepTheirLauncherAndRegisteredOriginsPreserveArguments() = runTest {
        val client = Fake()
        trackedRun(client, "claude", "interactive", false, report = {}) { assertNull(it); 0 }
        assertNull(client.input); assertTrue(client.ended.isEmpty())
        val id = "11111111-1111-1111-1111-111111111111"
        val args = listOf("--dangerously-skip-permissions", "--", "literal argument")
        val claude = agentLaunch("claude", args, 1234, "synthetic-local-token-long-enough", workload = "background", runId = id)
        assertEquals(args, claude.arguments)
        assertEquals("http://127.0.0.1:1234/_teamcode/runs/$id/background", claude.environment["ANTHROPIC_BASE_URL"])
        val codex = agentLaunch("codex", listOf("exec", "--", "literal"), 1234, "synthetic-local-token-long-enough", runId = id)
        assertTrue(codex.arguments.any { it.contains("/_teamcode/runs/$id/interactive/v1") })
        assertEquals(listOf("--", "literal"), codex.arguments.takeLast(2))
    }
}
