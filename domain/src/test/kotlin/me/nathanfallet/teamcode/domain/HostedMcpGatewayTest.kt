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
        assertEquals(limits.callTimeoutMillis, limits.deadlineMillis(McpOperation.CALL, hosted = true), "A call does not wait longer: agents give up after about a minute")
        assertTrue(limits.leaseMillis(McpOperation.LIST, hosted = true) > limits.leaseMillis(McpOperation.LIST))
        assertTrue(limits.maxLeaseMillis >= limits.leaseMillis(McpOperation.CALL, hosted = true))
    }

    @Test fun theRetainSweepKeepsEveryAssignedProcessAndStopsNothingOnATruncatedList() = runTest {
        val kept = mutableListOf<Set<String>>()
        val host = object : McpProcessHost {
            override suspend fun launchers() = setOf(StdioLauncher.NPM)
            override suspend fun retain(keys: Collection<String>): Int { kept += keys.toSet(); return 1 }
        }
        var rows = listOf(
            HostedMcpAssignment("team", "shared", 2, VariantBinding.SHARED, "alice", 10),
            HostedMcpAssignment("team", "shared", 2, VariantBinding.SHARED, "bob", 11),
            HostedMcpAssignment("team", "personal", 1, VariantBinding.PER_MEMBER, "alice", 12),
        )
        val source = object : HostedMcpAssignmentSource {
            var limit = 0
            override suspend fun hostedMcpAssignments(limit: Int): List<HostedMcpAssignment>? { this.limit = limit; return rows.takeIf { it.size <= limit } }
        }
        val retain = RetainHostedMcpProcessesUseCase(source, host)
        assertEquals(1, retain())
        assertEquals(RetainHostedMcpProcessesUseCase.MAX_ASSIGNMENTS, source.limit)
        assertEquals(setOf(
            StdioProcessKey.of("team", "shared", 2, VariantBinding.SHARED, "alice", 10),
            StdioProcessKey.of("team", "personal", 1, VariantBinding.PER_MEMBER, "alice", 12),
        ), kept.single(), "Two members of a SHARED variant keep one process")
        rows = List(RetainHostedMcpProcessesUseCase.MAX_ASSIGNMENTS + 1) { HostedMcpAssignment("team", "v$it", 1, VariantBinding.SHARED, "m", 0) }
        assertNull(retain())
        assertEquals(1, kept.size, "A list that may be truncated never stops a process")
    }
}
