package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.api.resources.BudgetOverridesResource
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*

internal fun BudgetOverride.view() = BudgetOverrideView(id, memberId, poolId, dailyUnits, createdBy, reason, createdAtMillis, expiresAtMillis,
    replacesId, revokedAtMillis, revokedBy, revocationReason)

internal fun Route.budgetOverrideRoutes(overrides: BudgetOverrideService) {
    get<BudgetOverridesResource> {
        val history = overrides.history(call.principal, it.memberId, it.before)
        call.respond(BudgetOverridePageView(history.overrides.map { row -> row.view() }, history.activeOverrideId, history.nextBeforeId))
    }
    post<BudgetOverridesResource> {
        val input = call.jsonBody<BudgetOverrideRequest>()
        call.respond(HttpStatusCode.Created, overrides.grant(call.principal, it.memberId,
            BudgetOverrideChange(input.dailyUnits, input.durationMinutes, input.reason, input.replacesId)).view())
    }
    post<BudgetOverridesResource.Revoke> {
        call.respond(overrides.revoke(call.principal, it.parent.memberId, it.id, call.jsonBody<RevokeBudgetOverrideRequest>().reason).view())
    }
}
