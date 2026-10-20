package me.nathanfallet.teamcode.domain

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.domain.cloud.ResolveCloudVariablesUseCase
import me.nathanfallet.teamcode.domain.variants.*
import kotlin.test.*

class ResolveCloudVariablesTest {
    private fun variant(id: String, kind: VariantKind, target: String, binding: VariantBinding = VariantBinding.SHARED, endpoint: String? = null) =
        Variant(id, kind, target, id, binding, 1, endpoint, emptyMap(), binding == VariantBinding.SHARED, 0, 0)
    private fun candidate(variant: Variant, shared: String? = null, member: String? = null, unreadable: Boolean = false) =
        AssignmentCandidate(variant.slot, AssignmentSource.DIRECT, variant, shared?.let(::SecretValue), member?.let(::SecretValue), unreadable = unreadable)

    private fun source(state: MemberVariantState) = ResolveCloudVariablesUseCase(ResolveEffectiveVariantsUseCase(object : EffectiveVariantSource {
        override suspend fun state(teamId: String, memberId: String) = state
    }))

    @Test fun environmentEntriesBecomeVariablesAndMcpServersAreLeftToTheGateway() = runTest {
        val env = variant("env", VariantKind.ENV, "NPM_TOKEN")
        val own = variant("own", VariantKind.ENV, "PERSONAL", VariantBinding.PER_MEMBER)
        val mcp = variant("mcp", VariantKind.MCP, "tracker", endpoint = "https://mcp.example.test")
        val result = source(MemberVariantState(true, listOf(candidate(env, "npm_value"), candidate(own, member = "mine"), candidate(mcp, "mcp_secret")), emptySet()))
            .resolve("default", "m1")
        assertEquals(mapOf("NPM_TOKEN" to "npm_value", "PERSONAL" to "mine"), result.values)
        assertTrue(result.unavailable.isEmpty())
        assertFalse(result.toString().contains("npm_value") || result.toString().contains("mcp_secret"))
    }

    @Test fun unreadableAndMissingSlotsAreReportedByNameOnlyAndOtherVariablesSurvive() = runTest {
        val good = variant("good", VariantKind.ENV, "GOOD")
        val broken = variant("broken", VariantKind.ENV, "BROKEN")
        val missing = variant("missing", VariantKind.ENV, "MISSING", VariantBinding.PER_MEMBER)
        val delegated = variant("delegated", VariantKind.MCP, "tracker", VariantBinding.PER_MEMBER, "https://mcp.example.test")
        val result = source(MemberVariantState(true, listOf(candidate(good, "ok"), candidate(broken, "x", unreadable = true), candidate(missing), candidate(delegated)), emptySet()))
            .resolve("default", "m1")
        assertEquals(mapOf("GOOD" to "ok"), result.values)
        assertEquals(listOf("BROKEN", "MISSING"), result.unavailable, "Environment slots only, sorted, never the MCP server")
    }

    @Test fun ineligibleMembersAndExclusionsReceiveNothing() = runTest {
        val env = variant("env", VariantKind.ENV, "TOKEN")
        assertTrue(source(MemberVariantState(false, listOf(candidate(env, "v")), emptySet())).resolve("default", "m1").values.isEmpty())
        assertTrue(source(MemberVariantState(true, listOf(candidate(env, "v")), setOf(env.slot))).resolve("default", "m1").values.isEmpty())
    }
}
