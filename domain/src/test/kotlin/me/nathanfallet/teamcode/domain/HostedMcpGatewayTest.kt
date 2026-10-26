package me.nathanfallet.teamcode.domain

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.domain.mcp.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.variants.*
import kotlin.test.*

/** The gateway in front of hosted commands (transport stdio): same admission, a longer list lease, warm-up and the retain sweep. */
class HostedMcpGatewayTest {
    private val member = Principal(Member("member", "member", Role.MEMBER, false, false), "token", "team")
    private val hosted = Variant("hosted", VariantKind.MCP, "everything", "team", VariantBinding.SHARED, 3, null, mapOf("MODE" to "x"), true, 1_000, 1_000,
        McpTransport.STDIO, StdioCommand(StdioLauncher.NPM, "fixture@1.0.0"))
    private val remote = Variant("remote", VariantKind.MCP, "tracker", "team", VariantBinding.SHARED, 1, "https://tracker.example.test", emptyMap(), true, 0, 0)

    private class Store : McpAccessStore {
        val admitted = mutableListOf<McpServerRef>()
        override suspend fun policies(teamId: String, variantIds: Collection<String>) = emptyMap<String, McpToolPolicy>()
        override suspend fun admit(actor: Principal, server: McpServerRef, operation: McpOperation, tool: String?, limits: McpLimits): McpLease {
            admitted += server
            return McpLease(1, actor.teamId, actor.member.id, server.name)
        }
        override suspend fun finish(lease: McpLease, outcome: McpOutcome, durationMillis: Long, responseBytes: Long) = Unit
        override suspend fun denied(actor: Principal, server: String, tool: String?, limits: McpLimits) = Unit
        override suspend fun toolPolicy(actor: Principal, variantId: String) = error("unused")
        override suspend fun setToolPolicy(actor: Principal, variantId: String, policy: McpToolPolicy) = error("unused")
        override suspend fun usage(actor: Principal, days: Int) = error("unused")
        override suspend fun calls(actor: Principal, beforeId: Long?) = error("unused")
        override suspend fun maintain() = Unit
    }

    private class Upstream : McpUpstream {
        val warmed = mutableListOf<List<String>>()
        val listed = mutableListOf<EffectiveMcpServer>()
        override val hostsCommands get() = true
        override suspend fun listTools(server: EffectiveMcpServer, limits: McpLimits): List<UpstreamTool> {
            listed += server
            return listOf(UpstreamTool("echo", """{"name":"echo"}"""))
        }
        override suspend fun callTool(server: EffectiveMcpServer, tool: String, arguments: String?, limits: McpLimits) = UpstreamCallResult("{}", false, 2)
        override fun warm(servers: List<EffectiveMcpServer>) { warmed += servers.map { it.name } }
    }

    private fun gateway(store: Store, upstream: Upstream) = McpGateway(ResolveEffectiveVariantsUseCase(object : EffectiveVariantSource {
        override suspend fun state(teamId: String, memberId: String) = MemberVariantState(true, listOf(
            AssignmentCandidate(hosted.slot, AssignmentSource.DIRECT, hosted, StdioEnvironment.encode(mapOf("TOKEN" to "synthetic-token")), null, assignedAt = 7),
            AssignmentCandidate(remote.slot, AssignmentSource.DIRECT, remote, SecretValue("synthetic-remote"), null, assignedAt = 8),
        ), emptySet())
    }, hostedCommands = true), store, upstream)

    @Test fun readingTheServerListWarmsOnlyTheHostedServers() = runTest {
        val upstream = Upstream()
        val gateway = gateway(Store(), upstream)
        assertTrue(gateway.hostsCommands)
        assertEquals(listOf("everything", "tracker"), gateway.servers(member))
        assertEquals(listOf(listOf("everything")), upstream.warmed)
    }

    @Test fun aHostedServerIsAdmittedLikeAnyOtherWithItsProcessDefinitionAndALongerLease() = runTest {
        val store = Store()
        val upstream = Upstream()
        val gateway = gateway(store, upstream)
        assertEquals(listOf("echo"), gateway.listTools(member, "everything").map { it.name })
        assertEquals(listOf("echo"), gateway.listTools(member, "tracker").map { it.name })
        assertEquals(listOf(true, false), store.admitted.map { it.hosted })
        assertEquals(McpServerRef("everything", "hosted", 3, 7, hosted = true), store.admitted[0], "Admission checks the same variant, version and assignment")
        val transport = upstream.listed[0].transport as McpServerTransport.Stdio
        assertEquals(StdioProcessKey.of("team", "hosted", 3, VariantBinding.SHARED, "member", 7), transport.key)
        assertEquals(1_000, transport.declaredAt)
        assertEquals(mapOf("MODE" to "x"), upstream.listed[0].config)
        val limits = McpLimits()
        assertEquals(limits.listTimeoutMillis + limits.stdioStartTimeoutMillis, limits.deadlineMillis(McpOperation.LIST, hosted = true))
        assertEquals(limits.callTimeoutMillis + limits.stdioStartTimeoutMillis, limits.deadlineMillis(McpOperation.CALL, hosted = true), "A call may also wait for a stopped process to start")
        assertEquals(limits.callTimeoutMillis, limits.deadlineMillis(McpOperation.CALL))
        assertTrue(limits.leaseMillis(McpOperation.LIST, hosted = true) > limits.leaseMillis(McpOperation.LIST))
        assertTrue(limits.maxLeaseMillis >= limits.leaseMillis(McpOperation.CALL, hosted = true))
    }

    @Test fun theRetainSweepKeepsEveryAssignedProcessAndStopsNothingOnATruncatedList() = runTest {
        val kept = mutableListOf<Set<String>>()
        val host = object : McpProcessHost {
            override suspend fun launchers() = setOf(StdioLauncher.NPM)
            override suspend fun retain(keys: Collection<String>): Int { kept += keys.toSet(); return 1 }
            override suspend fun status() = HostStatusSnapshot("test", emptyList())
            override suspend fun stop(key: String) = false
        }
        var rows = listOf(
            HostedMcpAssignment("team", "shared", 2, VariantBinding.SHARED, "alice", 10),
            HostedMcpAssignment("team", "shared", 2, VariantBinding.SHARED, "bob", 11),
            HostedMcpAssignment("team", "personal", 1, VariantBinding.PER_MEMBER, "alice", 12),
        )
        val source = object : HostedMcpAssignmentSource {
            var limit = 0
            override suspend fun hostedMcpAssignments(limit: Int): List<HostedMcpAssignment>? { this.limit = limit; return rows.takeIf { it.size <= limit } }
            override suspend fun teamHostedMcpAssignments(teamId: String, limit: Int) = error("unused")
        }
        val retain = RetainHostedMcpProcessesUseCase(source, host)
        assertEquals(HostedMcpSweep.Done(1, 0), retain())
        assertEquals(RetainHostedMcpProcessesUseCase.MAX_ASSIGNMENTS, source.limit)
        assertEquals(setOf(
            StdioProcessKey.of("team", "shared", 2, VariantBinding.SHARED, "alice", 10),
            StdioProcessKey.of("team", "personal", 1, VariantBinding.PER_MEMBER, "alice", 12),
        ), kept.single(), "Two members of a SHARED variant keep one process")
        rows = List(RetainHostedMcpProcessesUseCase.MAX_ASSIGNMENTS + 1) { HostedMcpAssignment("team", "v$it", 1, VariantBinding.SHARED, "m", 0) }
        assertEquals(HostedMcpSweep.TooMany, retain())
        assertEquals(1, kept.size, "A list that may be truncated never stops a process")
    }

    /** A host whose status the test sets, and an in-memory state store with compare-and-set. */
    private class FakeHost : McpProcessHost {
        var processes = listOf<HostedProcessStatus>()
        var reachable = true
        var refused = false
        val stopped = mutableListOf<String>()
        override suspend fun launchers() = setOf(StdioLauncher.NPM)
        override suspend fun retain(keys: Collection<String>): Int { if (refused) throw McpHostRefused("unauthorized"); if (!reachable) error("unreachable"); return 0 }
        override suspend fun status(): HostStatusSnapshot { if (!reachable) error("unreachable"); return HostStatusSnapshot("1.0.0", processes) }
        override suspend fun stop(key: String): Boolean { stopped += key; return true }
    }

    private class States : HostedProcessStateStore {
        val rows = mutableMapOf<Pair<String, String>, HostedProcessRecord>()
        val events = mutableListOf<Pair<HostedProcessEvent, HostedProcessChange>>()
        val unreachable = mutableListOf<Pair<Set<String>, String>>()
        val restarts = mutableListOf<Pair<String, String?>>()
        var failReads = false
        override suspend fun teamsWithStates(limit: Int) = rows.values.mapTo(HashSet()) { it.teamId }.takeIf { it.size <= limit }
        override suspend fun records(teamIds: Set<String>, limit: Int) = rows.values.filter { it.teamId in teamIds }.takeIf { it.size <= limit }
        override suspend fun records(teamId: String, variantIds: Collection<String>, memberId: String): List<HostedProcessRecord> {
            if (failReads) error("database down")
            return rows.values.filter { it.teamId == teamId && it.variantId in variantIds && it.memberId in setOf("", memberId) }
        }
        override suspend fun apply(changes: List<HostedProcessChange>, removals: List<HostedProcessRecord>): Int {
            removals.forEach { rows.remove(it.variantId to it.memberId, it) }
            var applied = 0
            for (change in changes) {
                val key = change.next.variantId to change.next.memberId
                if (rows[key] != change.previous) continue
                rows[key] = change.next
                applied++
                change.event?.let { events += it to change }
            }
            return applied
        }
        override suspend fun publishUnreachable(teamIds: Set<String>, reason: String) { unreachable += teamIds to reason }
        override suspend fun recordRestart(actor: Principal, variantId: String, memberId: String?) { restarts += variantId to memberId }
    }

    private val sharedAlice = HostedMcpAssignment("team", "shared", 2, VariantBinding.SHARED, "alice", 10, "everything")
    private val sharedBob = HostedMcpAssignment("team", "shared", 2, VariantBinding.SHARED, "bob", 11, "everything")
    private val personal = HostedMcpAssignment("team", "personal", 1, VariantBinding.PER_MEMBER, "alice", 12, "notes")

    private fun source(rows: () -> List<HostedMcpAssignment>) = object : HostedMcpAssignmentSource {
        override suspend fun hostedMcpAssignments(limit: Int) = rows()
        override suspend fun teamHostedMcpAssignments(teamId: String, limit: Int) = rows().filter { it.teamId == teamId }.map { it to it.memberId }
    }

    private fun status(assignment: HostedMcpAssignment, state: String, failure: String? = null) =
        HostedProcessStatus(assignment.key, state, "npm", "fixture@1.0.0", lastFailure = failure, stderr = "boom")

    @Test fun theStateSweepRecordsTransitionsAndPublishesFailedAndRecoveredOncePerStreak() = runTest {
        val host = FakeHost()
        val states = States()
        var now = 1_000L
        var rows = listOf(sharedAlice, sharedBob, personal)
        val sweep = RetainHostedMcpProcessesUseCase(source { rows }, host, states) { now }
        // Nothing known by the host: idle processes that never failed need no row.
        assertEquals(HostedMcpSweep.Done(0, 0), sweep())
        assertTrue(states.rows.isEmpty())
        host.processes = listOf(status(sharedAlice, HostedProcessStates.READY), status(personal, HostedProcessStates.FAILED, "start_failed"))
        now = 2_000
        assertEquals(HostedMcpSweep.Done(0, 2), sweep())
        assertEquals(HostedProcessRecord("team", "shared", "", sharedAlice.key, "ready", null, 2_000), states.rows["shared" to ""], "One row for the team's shared process")
        assertEquals("start_failed", states.rows["personal" to "alice"]!!.failure)
        assertEquals(listOf(HostedProcessEvent.FAILED), states.events.map { it.first })
        assertEquals("notes", states.events.single().second.server)
        // failed -> backoff: still the same streak, no second event; the same state twice writes nothing.
        host.processes = listOf(status(sharedAlice, HostedProcessStates.READY), status(personal, HostedProcessStates.BACKOFF, "start_failed"))
        now = 3_000
        assertEquals(HostedMcpSweep.Done(0, 1), sweep())
        assertEquals(HostedMcpSweep.Done(0, 0), sweep())
        assertEquals(1, states.events.size)
        // A retry that starts and fails again is the same streak.
        host.processes = listOf(status(sharedAlice, HostedProcessStates.READY), status(personal, HostedProcessStates.STARTING))
        sweep()
        host.processes = listOf(status(sharedAlice, HostedProcessStates.READY), status(personal, HostedProcessStates.FAILED, "start_failed"))
        sweep()
        assertEquals(1, states.events.size)
        // The backoff expires and the process goes idle: the failure is kept until a start succeeds, which recovers it once.
        host.processes = listOf(status(sharedAlice, HostedProcessStates.READY))
        assertEquals(HostedMcpSweep.Done(0, 1), sweep())
        assertEquals("idle", states.rows["personal" to "alice"]!!.state)
        assertEquals("start_failed", states.rows["personal" to "alice"]!!.failure)
        host.processes = listOf(status(sharedAlice, HostedProcessStates.READY), status(personal, HostedProcessStates.READY))
        assertEquals(HostedMcpSweep.Done(0, 1), sweep())
        assertEquals(listOf(HostedProcessEvent.FAILED, HostedProcessEvent.RECOVERED), states.events.map { it.first })
        assertEquals("start_failed", states.events.last().second.previous!!.failure, "The recovery names the failure it recovered from")
        assertNull(states.rows["personal" to "alice"]!!.failure)
        // Unassigned: its row goes.
        rows = listOf(sharedAlice, sharedBob)
        sweep()
        assertNull(states.rows["personal" to "alice"])
        // A team whose last hosted assignment went: its rows are still read, and removed.
        rows = emptyList()
        assertEquals(HostedMcpSweep.Done(0, 0), sweep())
        assertTrue(states.rows.isEmpty(), "No row outlives every assignment of its team")
    }

    @Test fun aStateTheHostDoesNotDocumentIsStoredAsUnknown() = runTest {
        val host = FakeHost()
        val states = States()
        val sweep = RetainHostedMcpProcessesUseCase(source { listOf(personal) }, host, states) { 5 }
        host.processes = listOf(status(personal, "draining_for_upgrade"))
        sweep()
        assertEquals(HostedProcessStates.UNKNOWN, states.rows["personal" to "alice"]!!.state)
    }

    @Test fun aRotatedProcessStartsFromIdleAndDoesNotInheritTheOldFailure() = runTest {
        val host = FakeHost()
        val states = States()
        var rows = listOf(personal)
        val sweep = RetainHostedMcpProcessesUseCase(source { rows }, host, states) { 5 }
        host.processes = listOf(status(personal, HostedProcessStates.FAILED, "crashed"))
        sweep()
        val rotated = personal.copy(assignedAt = 99)
        rows = listOf(rotated)
        host.processes = listOf(status(rotated, HostedProcessStates.READY))
        sweep()
        assertEquals(rotated.key, states.rows["personal" to "alice"]!!.processKey)
        assertEquals(listOf(HostedProcessEvent.FAILED), states.events.map { it.first }, "A new process is not a recovery of the old one")
        assertEquals("idle", states.rows["personal" to "alice"]!!.stateFor(personal.key), "The old key's state no longer applies")
    }

    @Test fun anUnreachableHostIsReportedWithTheTeamsThatHaveHostedServers() = runTest {
        val host = FakeHost().apply { reachable = false }
        val states = States()
        val sweep = RetainHostedMcpProcessesUseCase(source { listOf(sharedAlice, personal) }, host, states)
        assertEquals(HostedMcpSweep.HostUnavailable(setOf("team"), HostedMcpSweep.UNREACHABLE), sweep())
        assertTrue(states.rows.isEmpty(), "Nothing is recorded when the host cannot be read")
        host.refused = true
        assertEquals(HostedMcpSweep.HostUnavailable(setOf("team"), HostedMcpSweep.REFUSED), sweep(), "A refusal is a deployment mismatch, told apart from an outage")
    }

    @Test fun theServerListCarriesTransportAndTheRecordedStateWithoutFailingOnAStateReadError() = runTest {
        val states = States()
        val key = StdioProcessKey.of("team", "hosted", 3, VariantBinding.SHARED, "member", 7)
        states.rows["hosted" to ""] = HostedProcessRecord("team", "hosted", "", key, "backoff", "memory", 1)
        val gateway = McpGateway(ResolveEffectiveVariantsUseCase(object : EffectiveVariantSource {
            override suspend fun state(teamId: String, memberId: String) = MemberVariantState(true, listOf(
                AssignmentCandidate(hosted.slot, AssignmentSource.DIRECT, hosted, StdioEnvironment.encode(mapOf("TOKEN" to "synthetic-token")), null, assignedAt = 7),
                AssignmentCandidate(remote.slot, AssignmentSource.DIRECT, remote, SecretValue("synthetic-remote"), null, assignedAt = 8),
            ), emptySet())
        }, hostedCommands = true), Store(), Upstream(), hostedStates = states)
        assertEquals(listOf(
            AssignedMcpServer("everything", hosted = true, state = "backoff", failure = "memory"),
            AssignedMcpServer("tracker", hosted = false, state = null, failure = null),
        ), gateway.assigned(member))
        states.rows["hosted" to ""] = HostedProcessRecord("team", "hosted", "", "0".repeat(64), "failed", "crashed", 1)
        assertEquals(AssignedMcpServer("everything", true, "idle", null), gateway.assigned(member)[0], "A state recorded for a previous process reads as idle")
        states.failReads = true
        assertEquals(listOf("everything", "tracker"), gateway.assigned(member).map { it.name }, "Discovery never depends on the state table")
        assertNull(gateway.assigned(member)[0].state)
    }

    @Test fun anAdministratorRestartsAProcessOfTheTeamOnly() = runTest {
        val admin = Principal(Member("admin", "admin", Role.ADMIN, false, false), "token", "team")
        val host = FakeHost()
        val states = States()
        val variants = object : VariantService by NotUsedVariants {
            override suspend fun list(actor: Principal) = listOf(
                hosted.copy(id = "shared", target = "everything"),
                hosted.copy(id = "personal", target = "notes", binding = VariantBinding.PER_MEMBER),
            )
        }
        val admin2 = HostedMcpAdministration(source { listOf(sharedAlice, sharedBob, personal) }, states, host, variants)
        assertFailsWith<TeamFailure> { admin2.restart(member, "shared", null) }
        assertTrue(admin2.restart(admin, "shared", null))
        assertTrue(admin2.restart(admin, "personal", "alice"))
        assertEquals(listOf(sharedAlice.key, personal.key), host.stopped)
        assertEquals(listOf("shared" to null, "personal" to "alice"), states.restarts)
        assertEquals("not_found", assertFailsWith<TeamFailure> { admin2.restart(admin, "personal", "bob") }.code)
        val listed = admin2.processes(admin)
        assertTrue(listed.hostReachable)
        assertEquals(listOf("everything" to null, "notes" to "alice"), listed.processes.map { it.assignment.target to it.username }, "Two members of a SHARED variant share one process")
        host.reachable = false
        assertFalse(admin2.processes(admin).hostReachable)
    }

    private object NotUsedVariants : VariantService by (java.lang.reflect.Proxy.newProxyInstance(VariantService::class.java.classLoader,
        arrayOf<Class<*>>(VariantService::class.java), java.lang.reflect.InvocationHandler { _, _, _ -> error("unused") }) as VariantService)
}
