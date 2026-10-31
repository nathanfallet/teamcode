package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.automations.*
import me.nathanfallet.teamcode.domain.team.*

private const val AUTOMATION_ID = "[a-zA-Z0-9-]{1,36}"
private val automationId = Regex(AUTOMATION_ID)

/**
 * The webhook of an automation: its id, an optional `v/<n>` or `v/latest`, `trigger`, and the secret for a sender that
 * cannot sign. Plain segments only, so this unauthenticated exemption never reaches another route.
 */
internal val automationTriggerPath = Regex("/api/v1/automations/$AUTOMATION_ID(/v/([1-9][0-9]{0,8}|latest))?/trigger(/whsec_[A-Za-z0-9_-]{16,128})?")

private fun ApplicationCall.automationId() = parameters["id"]!!.also { if (!automationId.matches(it)) throw TeamFailure("not_found", 404, "Automation not found.") }
private fun ApplicationCall.runId() = parameters["runId"]!!.also { if (!automationId.matches(it)) throw TeamFailure("not_found", 404, "Run not found.") }

private fun invalid(message: String) = TeamFailure("invalid_automation", 400, message)
private fun <T> wire(value: String?, parse: (String) -> T?, field: String, allowed: List<String>): T? =
    value?.let { parse(it) ?: throw invalid("Use $field ${allowed.joinToString(", ")}.") }

internal fun AutomationInput.domain() = AutomationDefinition(
    name,
    AutomationTrigger(
        wire(trigger.kind, TriggerKind::parse, "trigger.kind", Automations.triggerKinds)!!, trigger.cron, trigger.timezone,
        wire(trigger.catchup, CatchupPolicy::parse, "trigger.catchup", Automations.catchupPolicies) ?: CatchupPolicy.ONCE,
        trigger.catchupGraceMinutes ?: AutomationRules.DEFAULT_GRACE_MINUTES,
    ),
    AutomationAction(wire(action.kind, ActionKind::parse, "action.kind", Automations.actionKinds)!!, action.profileId, action.model, action.effort, action.maxRuntimeMinutes, action.workflowId, action.inputs),
    prompt,
    variables.map { AutomationVariable(it.name, it.path, wire(it.type, VariableType::parse, "type", Automations.variableTypes)!!, it.values, it.maxLength, it.required, it.sensitive) },
    samplePayload,
    wire(overlap, OverlapPolicy::parse, "overlap", Automations.overlapPolicies)!!,
    maxRunsPerDay,
)

private fun AutomationTrigger.view() = AutomationTriggerView(kind.wire, cron, timezone, catchup.wire.takeIf { kind == TriggerKind.CRON }, catchupGraceMinutes.takeIf { kind == TriggerKind.CRON })
private fun AutomationAction.view() = AutomationActionView(kind.wire, profileId, model, effort, maxRuntimeMinutes, workflowId, inputs)
private fun AutomationVariable.view() = AutomationVariableView(name, path, type.wire, values, maxLength, required, sensitive)
private fun RunSummary.view() = AutomationRunSummaryView(id, source.wire, status.wire, queuedAt)
private fun ExtractedVariable.view() = AutomationVariableValueView(name, type.wire, value.takeUnless { sensitive }, sensitive, present)

internal fun Automation.view() = AutomationView(
    id, ownerId, ownerName, definition.name, enabled, disabledReason, disabledBy, version, definition.trigger.view(), definition.action.view(), definition.prompt,
    definition.variables.map { it.view() }, definition.samplePayload, definition.overlap.wire, definition.maxRunsPerDay, nextRunAt, AutomationRules.upcoming(this),
    lastRun?.view(), consecutiveFailures,
    if (definition.trigger.kind == TriggerKind.WEBHOOK) AutomationWebhookView("/api/v1/automations/$id/trigger", "/api/v1/automations/$id/v/$version/trigger",
        secretRotatedAt, previousSecretValidUntil) else null,
    createdAt, updatedAt,
)
private fun Automation.summary() = AutomationSummaryView(id, ownerId, ownerName, definition.name, enabled, disabledReason, version, definition.trigger.view(),
    definition.action.kind.wire, nextRunAt, lastRun?.view(), consecutiveFailures, updatedAt)
private fun CreatedAutomation.view() = CreatedAutomationView(automation.view(), secret)
internal fun AutomationRun.view() = AutomationRunView(id, automationId, automationVersion, source.wire, status.wire, reason, sessionId, profileVersion, scheduledFor,
    queuedAt, startedAt, endedAt, cancelRequested, initiatorId, variables.map { it.view() }, workflowRunId)
private fun AutomationPreview.view() = AutomationPreviewView(renderedPrompt, variables.map { it.view() }, problems.map { AutomationProblemView(it.path, it.rule) },
    profileVersion, wouldSkip, nextRuns, renderedInputs)

private suspend fun ApplicationCall.respondRun(outcome: RunOutcome) {
    if (outcome is RunOutcome.Replayed) response.header(CloudHeaders.IDEMPOTENCY_REPLAYED, "true")
    respond(HttpStatusCode.Accepted, outcome.run.view())
}

/** Definitions up to the 32,000-character prompt and the 64 KiB sample, encoded. */
private const val DEFINITION_BYTES = 512 * 1024

/** Members manage their own automations; the service decides who may do what (docs/automations.md). */
internal fun Route.automationRoutes(automations: AutomationService) {
    route("/api/v1/automations") {
        get {
            val query = call.request.queryParameters
            val owner = query["owner"]?.let { if (it == "all") AutomationService.ALL else it }
            val page = automations.list(call.principal, owner, query["before"], query["limit"]?.toIntOrNull() ?: 50)
            call.respond(AutomationPageView(page.automations.map { it.summary() }, page.nextBefore))
        }
        post {
            val input = call.jsonBody<CreateAutomationRequest>(DEFINITION_BYTES)
            call.respond(HttpStatusCode.Created, automations.create(call.principal, input.automation.domain()).view())
        }
        post("/preview") {
            val input = call.jsonBody<PreviewAutomationRequest>(DEFINITION_BYTES)
            call.respond(automations.preview(call.principal, input.automation.domain(), input.payload).view())
        }
        get("/{id}") { call.respond(automations.get(call.principal, call.automationId()).view()) }
        put("/{id}") {
            val input = call.jsonBody<UpdateAutomationRequest>(DEFINITION_BYTES)
            call.respond(automations.update(call.principal, call.automationId(), input.version, input.automation.domain()).view())
        }
        delete("/{id}") { automations.delete(call.principal, call.automationId()); call.respond(HttpStatusCode.NoContent) }
        post("/{id}/enable") { call.respond(automations.enable(call.principal, call.automationId()).view()) }
        post("/{id}/disable") { call.respond(automations.disable(call.principal, call.automationId()).view()) }
        post("/{id}/webhook/rotate-secret") { call.respond(automations.rotateSecret(call.principal, call.automationId()).view()) }
        post("/{id}/run") {
            val input = call.jsonBody<RunAutomationRequest>(DEFINITION_BYTES)
            when (val outcome = automations.test(call.principal, call.automationId(), input.payload, input.dryRun, call.idempotencyKey(required = false))) {
                is TestOutcome.Preview -> call.respond(outcome.preview.view())
                is TestOutcome.Run -> call.respondRun(outcome.outcome)
            }
        }
        get("/{id}/runs") {
            val query = call.request.queryParameters
            val sources = query.getAll("source").orEmpty().map { RunSource.parse(it) ?: throw TeamFailure("invalid_filter", 400, "Use source ${Automations.runSources.joinToString(", ")}.") }
            val statuses = query.getAll("status").orEmpty().map { RunStatus.parse(it) ?: throw TeamFailure("invalid_filter", 400, "Use status ${Automations.runStatuses.joinToString(", ")}.") }
            val page = automations.runs(call.principal, call.automationId(), RunQuery(query["before"], query["limit"]?.toIntOrNull() ?: 50, sources.toSet(), statuses.toSet()))
            call.respond(AutomationRunPageView(page.runs.map { it.view() }, page.nextBefore))
        }
        get("/{id}/runs/{runId}") { call.respond(automations.run(call.principal, call.automationId(), call.runId()).view()) }
        post("/{id}/runs/{runId}/cancel") { call.respond(automations.cancel(call.principal, call.automationId(), call.runId()).view()) }
    }
}

/** The webhook receiver: no login, the signature or the secret in the path is the proof (checked by the service). */
internal fun Route.automationTriggerRoutes(automations: AutomationService) {
    suspend fun ApplicationCall.trigger(version: String?, token: String?) {
        // A larger body is `413 payload_too_large`, answered by the gateway.
        val body = receiveChannel().readBounded(AutomationRules.MAX_PAYLOAD_BYTES)
        val key = request.headers[CloudHeaders.IDEMPOTENCY_KEY] ?: request.headers[AutomationHeaders.DELIVERY]
        val outcome = automations.trigger(TriggerRequest(automationId(), version?.takeUnless { it == Automations.LATEST_VERSION }?.toInt(), token,
            request.headers[AutomationHeaders.TIMESTAMP], request.headers[AutomationHeaders.SIGNATURE], key, body))
        if (outcome is RunOutcome.Replayed) response.header(CloudHeaders.IDEMPOTENCY_REPLAYED, "true")
        respond(HttpStatusCode.Accepted, AutomationTriggerAcceptedView(outcome.run.id, outcome.run.status.wire))
    }
    route("/api/v1/automations/{id}") {
        post("/trigger") { call.trigger(null, null) }
        post("/trigger/{token}") { call.trigger(null, call.parameters["token"]) }
        post("/v/{version}/trigger") { call.trigger(call.parameters["version"], null) }
        post("/v/{version}/trigger/{token}") { call.trigger(call.parameters["version"], call.parameters["token"]) }
    }
}
