package me.nathanfallet.teamcode.presentation

import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.api.resources.UsageResource
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

internal fun PendingUsage.view() = PendingUsageView(id, memberId, username, accountId, accountName, provider, providerRequestId,
    poolId, runId, dayNumber, units, state, createdAtMillis, leaseExpiresAtMillis, sourcePoolId, accountEmail)
internal fun ReconciledUsage.view() = ReconciledUsageView(id, memberId, accountId, poolId, runId, dayNumber, providerRequestId,
    previousState, previousUnits, actualUnits, source.name, reference, reason, actorId, reconciledAtMillis, groupIds, sourcePoolId)
internal fun usageEvidence(value: String) = UsageEvidence.entries.find { it.name == value }
    ?: throw TeamFailure("invalid_reconciliation", 400, "Choose request-specific provider evidence.")

internal fun Route.usageReconciliationRoutes(service: UsageReconciliationService) {
    get<UsageResource.Pending> {
        val result = service.pending(call.principal, it.member, it.before)
        call.respond(PendingUsagePageView(result.requests.map { row -> row.view() }, result.nextBeforeId))
    }
    get<UsageResource.History> {
        val result = service.history(call.principal, it.member, it.before)
        call.respond(ReconciledUsagePageView(result.reconciliations.map { row -> row.view() }, result.nextBeforeId))
    }
    get<UsageResource.Reservation> {
        val result = service.get(call.principal, it.id)
        call.respond(UsageReviewView(result.pending?.view(), result.reconciliation?.view()))
    }
    post<UsageResource.Reservation.Reconcile> {
        val input = call.jsonBody<UsageReconciliationRequest>()
        call.respond(service.reconcile(call.principal, it.parent.id, UsageReconciliationChange(input.expectedUnits, input.actualUnits,
            usageEvidence(input.source), input.reference, input.reason, input.expectedProviderRequestId)).view())
    }
}
