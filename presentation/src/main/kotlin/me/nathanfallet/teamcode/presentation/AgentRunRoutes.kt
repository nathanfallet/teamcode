package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.api.resources.RunsResource
import me.nathanfallet.teamcode.domain.team.*

internal fun RunPolicy.view() = RunPolicyView(budgetUnits, maxConcurrent, paused)
internal fun AgentRun.view() = AgentRunView(id, memberId, username, agent, workload, deviceId, startedAtMillis, heartbeatAtMillis, lastRequestAtMillis,
    endedAtMillis, exitCode, state.name, policy.view(), chargedAndReservedUnits, attempts, completed, activeRequests, contextsTruncated, queuedRequests, labels)
internal fun RunLease.view() = RunLeaseView(id, startedAtMillis, heartbeatAtMillis, endedAtMillis, state.name)
internal fun RunDetail.view() = RunDetailView(run.view(), conversations.map {
    RunConversationView(it.sessionKey, it.conversationKey, it.requestedModel, it.provider, it.model, it.lastSeenAtMillis, it.pace, it.directiveId, it.receivedByCliAtMillis, it.emittedToHookAtMillis)
}, observationOnly)

internal fun Route.agentRunRoutes(runs: AgentRunService) {
    post<RunsResource> {
        val input = call.jsonBody<StartRunRequest>()
        call.respond(HttpStatusCode.Created, runs.start(call.principal, input.id, input.agent, input.workload, input.labels).view())
    }
    get<RunsResource> {
        val page = runs.list(call.principal, it.member, it.all, it.before, LabelRules.parseFilter(it.label))
        call.respond(RunPageView(page.runs.map { it.view() }, page.nextBeforeId))
    }
    get<RunsResource.Id> { call.respond(runs.get(call.principal, it.id).view()) }
    post<RunsResource.Id.Heartbeat> { call.respond(runs.heartbeat(call.principal, it.parent.id).view()) }
    post<RunsResource.Id.End> { call.respond(runs.end(call.principal, it.parent.id, call.jsonBody<EndRunRequest>().exitCode).view()) }
    put<RunsResource.Id.Policy> {
        val input = call.jsonBody<RunPolicyView>()
        call.respond(runs.configure(call.principal, it.parent.id, RunPolicy(input.budgetUnits, input.maxConcurrent, input.paused)).view())
    }
}
