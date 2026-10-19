package me.nathanfallet.teamcode.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.domain.mcp.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.variants.*
import kotlin.test.*

class McpGatewayTest {
    private val member = Principal(Member("member", "member", Role.MEMBER, false, false), "token", "team")

    private fun server(name: String, variant: String = "v-$name") = EffectiveMcpServer(name, variant, variant, 1, 0, "https://$name.example.test", emptyMap(), SecretValue("synthetic-$name"))

    private class Store(var policies: Map<String, McpToolPolicy> = emptyMap()) : McpAccessStore {
        val finished = mutableListOf<McpOutcome>()
        val denials = mutableListOf<Pair<String, String?>>()
        var refuse: TeamFailure? = null
        override suspend fun policies(teamId: String, variantIds: Collection<String>) = policies.filterKeys { it in variantIds }
        override suspend fun admit(actor: Principal, server: McpServerRef, operation: McpOperation, tool: String?, limits: McpLimits): McpLease {
            refuse?.let { throw it }
            return McpLease(1, actor.teamId, actor.member.id, server.name)
        }
        override suspend fun finish(lease: McpLease, outcome: McpOutcome, durationMillis: Long, responseBytes: Long) { finished += outcome }
        override suspend fun denied(actor: Principal, server: String, tool: String?, limits: McpLimits) { denials += server to tool }
        override suspend fun toolPolicy(actor: Principal, variantId: String) = error("unused")
        override suspend fun setToolPolicy(actor: Principal, variantId: String, policy: McpToolPolicy) = error("unused")
        override suspend fun usage(actor: Principal, days: Int) = error("unused")
        override suspend fun calls(actor: Principal, beforeId: Long?) = error("unused")
        override suspend fun maintain() = Unit
    }

    private class Upstream(val tools: Map<String, List<String>>) : McpUpstream {
        val called = mutableListOf<String>()
        var block: CompletableDeferred<Unit>? = null
        override suspend fun listTools(server: EffectiveMcpServer, limits: McpLimits) =
            tools[server.name]?.map { UpstreamTool(it, """{"name":"$it"}""") } ?: throw McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "down")
        override suspend fun callTool(server: EffectiveMcpServer, tool: String, arguments: String?, limits: McpLimits): UpstreamCallResult {
            called += "${server.name}/$tool"
            block?.await()
            return UpstreamCallResult("""{"content":[]}""", tool == "fails", 12)
        }
    }

    private fun gateway(servers: List<EffectiveMcpServer>, store: Store, upstream: Upstream) =
        McpGateway(ResolveEffectiveVariantsUseCase(object : EffectiveVariantSource {
            override suspend fun state(teamId: String, memberId: String) = MemberVariantState(true, servers.map { server ->
                val variant = Variant(server.variantId, VariantKind.MCP, server.name, server.variantName, VariantBinding.SHARED, 1, server.endpoint, emptyMap(), true, 0, 0)
                AssignmentCandidate(variant.slot, AssignmentSource.DIRECT, variant, server.credential, null)
            }, emptySet())
        }), store, upstream)

    @Test fun namesResolveToTheLongestServerPrefix() {
        assertEquals("a" to "b", McpToolNames.resolve("a__b", listOf("a")))
        assertEquals("a__b" to "c", McpToolNames.resolve("a__b__c", listOf("a", "a__b")))
        assertNull(McpToolNames.resolve("a__", listOf("a")))
        assertNull(McpToolNames.resolve("b__x", listOf("a")))
    }

    @Test fun policiesValidate() {
        McpToolPolicy.validate(McpToolPolicy.ALL)
        McpToolPolicy.validate(McpToolPolicy(McpToolMode.ALLOWLIST, setOf("search")))
        for (invalid in listOf(McpToolPolicy(McpToolMode.ALLOWLIST), McpToolPolicy(McpToolMode.ALL, setOf("x")), McpToolPolicy(McpToolMode.ALLOWLIST, setOf("bad name"))))
            assertEquals("invalid_tool_policy", assertFailsWith<TeamFailure> { McpToolPolicy.validate(invalid) }.code)
    }

    @Test fun discoveryFiltersByPolicyAndSkipsFailingOrCollidingServers() = runTest {
        val store = Store(mapOf("v-tracker" to McpToolPolicy(McpToolMode.ALLOWLIST, setOf("search"))))
        // `a__b` on server `a` would collide with server `a__b`'s namespace, so it is not exposed.
        val upstream = Upstream(mapOf("tracker" to listOf("search", "delete", "bad name"), "a" to listOf("b__x", "ok"), "a__b" to listOf("x")))
        val tools = gateway(listOf(server("tracker"), server("down"), server("a"), server("a__b")), store, upstream).listTools(member)
        assertEquals(listOf("a__b__x", "a__ok", "tracker__search"), tools.map { it.name })
        assertEquals(4, store.finished.size, "Every admitted listing settles its lease")
    }

    @Test fun callsAreDeniedUnlessAssignedAndAllowed() = runTest {
        val store = Store(mapOf("v-tracker" to McpToolPolicy(McpToolMode.ALLOWLIST, setOf("search", "fails"))))
        val upstream = Upstream(emptyMap())
        val gateway = gateway(listOf(server("tracker")), store, upstream)
        for (name in listOf("tracker__delete", "wiki__page", "search", "tracker__bad name")) assertFailsWith<McpToolDenied>(name) { gateway.callTool(member, name, null) }
        assertEquals(listOf("tracker" to "delete", "" to "wiki__page", "" to "search", "" to null), store.denials)
        assertTrue(upstream.called.isEmpty(), "A denied call never reaches the upstream")
        assertIs<McpCallOutcome.Completed>(gateway.callTool(member, "tracker__search", "{}"))
        assertTrue(assertIs<McpCallOutcome.Completed>(gateway.callTool(member, "tracker__fails", null)).result.isError)
        assertEquals(listOf(McpOutcome.OK, McpOutcome.TOOL_ERROR), store.finished)
        store.refuse = TeamFailure("mcp_tool_denied", 403, "Unknown tool.")
        assertFailsWith<McpToolDenied> { gateway.callTool(member, "tracker__search", null) }
        store.refuse = TeamFailure("mcp_member_rate", 429, "slow down")
        assertEquals("mcp_member_rate", assertIs<McpCallOutcome.Failed>(gateway.callTool(member, "tracker__search", null)).code)
        store.refuse = TeamFailure("authentication_required", 401, "Sign in")
        assertFailsWith<TeamFailure> { gateway.callTool(member, "tracker__search", null) }
        val service = member.copy(serviceScopes = setOf(ServiceScope.INFERENCE))
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { gateway.callTool(service, "tracker__search", null) }.code)
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { gateway.listTools(service) }.code)
    }

    @Test fun aCancelledCallStillReleasesItsLease() = runTest {
        val store = Store()
        val upstream = Upstream(emptyMap()).apply { block = CompletableDeferred() }
        val gateway = gateway(listOf(server("tracker")), store, upstream)
        val job = launch { gateway.callTool(member, "tracker__search", null) }
        testScheduler.advanceUntilIdle()
        job.cancel(); job.join()
        assertEquals(listOf(McpOutcome.CANCELLED), store.finished)
    }
}
