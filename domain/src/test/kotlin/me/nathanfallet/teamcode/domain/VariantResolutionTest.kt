package me.nathanfallet.teamcode.domain

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.variants.*
import kotlin.test.*

class VariantResolutionTest {
    private fun variant(id: String, kind: VariantKind, target: String, binding: VariantBinding = VariantBinding.SHARED, endpoint: String? = null) =
        Variant(id, kind, target, id, binding, 1, endpoint, emptyMap(), binding == VariantBinding.SHARED, 0, 0)
    private fun candidate(variant: Variant, source: AssignmentSource, shared: String? = "shared-${variant.id}", member: String? = null) =
        AssignmentCandidate(variant.slot, source, variant, shared?.let(::SecretValue), member?.let(::SecretValue))

    @Test fun directBeatsGroupAndBulkAndExclusionsBeatEverything() {
        val token = VariantTarget(VariantKind.ENV, "TOKEN")
        val bulk = candidate(variant("bulk", VariantKind.ENV, "TOKEN"), AssignmentSource.BULK)
        val group = candidate(variant("group", VariantKind.ENV, "TOKEN"), AssignmentSource.GROUP)
        val direct = candidate(variant("direct", VariantKind.ENV, "TOKEN"), AssignmentSource.DIRECT)
        assertEquals("direct", VariantResolution.select(listOf(bulk, direct, group), emptySet()).single().variant.id)
        assertEquals("group", VariantResolution.select(listOf(bulk, group), emptySet()).single().variant.id)
        assertTrue(VariantResolution.select(listOf(bulk, group, direct), setOf(token)).isEmpty())
        assertTrue(VariantResolution.select(emptyList(), emptySet()).isEmpty(), "No assignment means nothing")
        // A candidate whose stored slot disagrees with its variant is ignored rather than leaking into another slot.
        assertTrue(VariantResolution.select(listOf(direct.copy(target = VariantTarget(VariantKind.ENV, "OTHER"))), emptySet()).isEmpty())
    }

    @Test fun useCaseReturnsNothingForIneligibleMembersAndSkipsIncompleteMcpBindings() = runTest {
        val env = variant("env", VariantKind.ENV, "TOKEN")
        val delegated = variant("delegated", VariantKind.MCP, "tracker", VariantBinding.PER_MEMBER, "https://mcp.example.test")
        var state = MemberVariantState(true, listOf(candidate(env, AssignmentSource.DIRECT, "value"), candidate(delegated, AssignmentSource.DIRECT, null, null)), emptySet())
        val resolve = ResolveEffectiveVariantsUseCase(object : EffectiveVariantSource {
            override suspend fun state(teamId: String, memberId: String) = state
        })
        val effective = resolve("team", "member")
        assertEquals("value", effective.environment.single().value.reveal())
        assertTrue(effective.mcpServers.isEmpty(), "A delegated server without the member's credential is not usable")
        assertEquals(listOf(delegated.slot), effective.unavailable)
        state = state.copy(candidates = listOf(candidate(env, AssignmentSource.DIRECT, "value").copy(unreadable = true, sharedSecret = null), candidate(variant("other", VariantKind.ENV, "OTHER"), AssignmentSource.BULK, "kept")))
        val degraded = resolve("team", "member")
        assertEquals(listOf(env.slot), degraded.unavailable)
        assertEquals(listOf("OTHER"), degraded.environment.map { it.name }, "One unreadable slot does not withhold the others")
        assertTrue(effective.toString().contains("value=SecretValue([REDACTED])"))
        state = state.copy(eligible = false)
        assertEquals(EffectiveVariants.none("member"), resolve("team", "member"))
    }

    @Test fun validationRejectsUnsafeVariants() {
        fun invalid(variant: NewVariant) = assertEquals("invalid_variant", assertFailsWith<TeamFailure> { VariantService.validate(variant) }.code)
        val secret = SecretValue("synthetic")
        VariantService.validate(NewVariant(VariantKind.ENV, "GITHUB_TOKEN", "org", VariantBinding.SHARED, sharedSecret = secret))
        VariantService.validate(NewVariant(VariantKind.MCP, "tracker", "team", VariantBinding.SHARED, "https://mcp.example.test/v1", mapOf("workspace" to "core")))
        invalid(NewVariant(VariantKind.ENV, "lower", "org", VariantBinding.SHARED, sharedSecret = secret))
        for (reserved in listOf("PATH", "LD_PRELOAD", "DYLD_INSERT_LIBRARIES", "NODE_OPTIONS", "ANTHROPIC_BASE_URL", "OPENAI_API_KEY", "TEAMCODE_TOKEN", "HTTPS_PROXY"))
            invalid(NewVariant(VariantKind.ENV, reserved, "org", VariantBinding.SHARED, sharedSecret = secret))
        val mcpVariant = variant("mcp", VariantKind.MCP, "tracker", endpoint = "https://mcp.example.test")
        assertFailsWith<TeamFailure> { VariantService.validate(mcpVariant, VariantUpdate(1, "mcp", sharedSecret = secret, clearSharedSecret = true)) }
        assertFailsWith<TeamFailure> { VariantService.validate(variant("env", VariantKind.ENV, "TOKEN"), VariantUpdate(1, "env", clearSharedSecret = true)) }
        VariantService.validate(mcpVariant, VariantUpdate(1, "mcp", clearSharedSecret = true))
        invalid(NewVariant(VariantKind.ENV, "TOKEN", "org", VariantBinding.SHARED))
        invalid(NewVariant(VariantKind.ENV, "TOKEN", "org", VariantBinding.PER_MEMBER, sharedSecret = secret))
        invalid(NewVariant(VariantKind.ENV, "TOKEN", "org", VariantBinding.SHARED, "https://x.test", sharedSecret = secret))
        invalid(NewVariant(VariantKind.ENV, "TOKEN", "", VariantBinding.SHARED, sharedSecret = secret))
        invalid(NewVariant(VariantKind.ENV, "TOKEN", "org", VariantBinding.SHARED, sharedSecret = SecretValue("x".repeat(VariantService.SECRET_LIMIT + 1))))
        invalid(NewVariant(VariantKind.MCP, "tracker", "team", VariantBinding.SHARED, "http://mcp.example.test"))
        invalid(NewVariant(VariantKind.MCP, "tracker", "team", VariantBinding.SHARED, "https://user:pass@mcp.example.test"))
        invalid(NewVariant(VariantKind.MCP, "tracker", "team", VariantBinding.SHARED, null))
        invalid(NewVariant(VariantKind.MCP, "Tracker", "team", VariantBinding.SHARED, "https://mcp.example.test"))
        invalid(NewVariant(VariantKind.MCP, "tracker", "team", VariantBinding.SHARED, "https://mcp.example.test", mapOf("bad key" to "v")))
        assertFailsWith<TeamFailure> { VariantService.validateMemberSecret(VariantBinding.PER_MEMBER, null) }
        assertFailsWith<TeamFailure> { VariantService.validateMemberSecret(VariantBinding.SHARED, secret) }
        assertEquals("SecretValue([REDACTED])", secret.toString())
    }
}
