package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.variants.ResolveEffectiveVariantsUseCase
import me.nathanfallet.teamcode.domain.variants.VariantKind

/**
 * The environment variables a cloud session's owner is assigned through variants. Only environment entries are used
 * (MCP servers are reached through the gateway); a slot withheld because its value cannot be read is reported by name
 * in [CloudVariables.unavailable], never with a value. The owner is resolved at every preparation, so a rotation,
 * an exclusion or a transfer takes effect on the next start or resume.
 */
class ResolveCloudVariablesUseCase(private val variants: ResolveEffectiveVariantsUseCase) : CloudVariableSource {
    override suspend fun resolve(teamId: String, memberId: String): CloudVariables {
        val effective = variants(teamId, memberId)
        return CloudVariables(
            effective.environment.associate { it.name to it.value.reveal() },
            effective.unavailable.filter { it.kind == VariantKind.ENV }.map { it.name }.sorted(),
        )
    }
}
