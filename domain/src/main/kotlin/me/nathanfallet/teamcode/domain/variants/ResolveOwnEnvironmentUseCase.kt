package me.nathanfallet.teamcode.domain.variants

import me.nathanfallet.teamcode.domain.cloud.CloudVariableSource
import me.nathanfallet.teamcode.domain.cloud.CloudVariables
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.requireInteractive
import me.nathanfallet.teamcode.domain.team.requireReady

/**
 * The caller's own environment variables, with their values, for `teamcode run` to give its agent
 * (`GET /api/v1/environment`). Only an interactive login reads them, and only its own: there is no way to name
 * another member, and service keys are refused, so a CI key never carries a member's secrets. Resolved on every call,
 * so a rotation, an exclusion or a removed assignment applies at the next read.
 */
class ResolveOwnEnvironmentUseCase(private val variables: CloudVariableSource) {
    suspend operator fun invoke(principal: Principal): CloudVariables {
        principal.requireReady()
        principal.requireInteractive()
        return variables.resolve(principal.teamId, principal.member.id)
    }
}
