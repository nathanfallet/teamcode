package me.nathanfallet.teamcode.companion.infrastructure.repositories

import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.api.models.AutomationActionView
import me.nathanfallet.teamcode.api.models.AutomationInput
import me.nathanfallet.teamcode.api.models.AutomationPreviewView
import me.nathanfallet.teamcode.api.models.AutomationRunSummaryView
import me.nathanfallet.teamcode.api.models.AutomationRunView
import me.nathanfallet.teamcode.api.models.AutomationSummaryView
import me.nathanfallet.teamcode.api.models.AutomationTriggerView
import me.nathanfallet.teamcode.api.models.AutomationVariableValueView
import me.nathanfallet.teamcode.api.models.AutomationVariableView
import me.nathanfallet.teamcode.api.models.AutomationView
import me.nathanfallet.teamcode.api.models.CreatedAutomationView
import me.nathanfallet.teamcode.companion.domain.models.ActionKind
import me.nathanfallet.teamcode.companion.domain.models.Automation
import me.nathanfallet.teamcode.companion.domain.models.AutomationAction
import me.nathanfallet.teamcode.companion.domain.models.AutomationDefinition
import me.nathanfallet.teamcode.companion.domain.models.AutomationPage
import me.nathanfallet.teamcode.companion.domain.models.AutomationPreview
import me.nathanfallet.teamcode.companion.domain.models.AutomationProblem
import me.nathanfallet.teamcode.companion.domain.models.AutomationRun
import me.nathanfallet.teamcode.companion.domain.models.AutomationRunPage
import me.nathanfallet.teamcode.companion.domain.models.AutomationScope
import me.nathanfallet.teamcode.companion.domain.models.AutomationSummary
import me.nathanfallet.teamcode.companion.domain.models.AutomationTrigger
import me.nathanfallet.teamcode.companion.domain.models.AutomationVariable
import me.nathanfallet.teamcode.companion.domain.models.AutomationVariableValue
import me.nathanfallet.teamcode.companion.domain.models.AutomationWebhook
import me.nathanfallet.teamcode.companion.domain.models.CreatedAutomation
import me.nathanfallet.teamcode.companion.domain.models.RunSource
import me.nathanfallet.teamcode.companion.domain.models.RunSummary
import me.nathanfallet.teamcode.companion.domain.models.TriggerKind
import me.nathanfallet.teamcode.companion.domain.models.parsePayload
import me.nathanfallet.teamcode.companion.domain.models.prettyPayload
import me.nathanfallet.teamcode.companion.domain.repositories.AutomationRepository

/** The automations over the typed client (`/api/v1/automations`), one request per call. */
class RemoteAutomationRepository(private val calls: AccountCalls) : AutomationRepository {
    override suspend fun automations(accountId: String, scope: AutomationScope, before: String?): AutomationPage = calls.withAccount(accountId) { client ->
        val page = client.automations(owner = scope.wire, before = before, limit = PAGE)
        AutomationPage(page.automations.map { it.domain() }, page.nextBefore)
    }

    override suspend fun automation(accountId: String, id: String): Automation = calls.withAccount(accountId, resource = true) { it.automation(id).domain() }

    override suspend fun create(accountId: String, definition: AutomationDefinition): CreatedAutomation =
        calls.withAccount(accountId) { it.createAutomation(definition.input()).domain() }

    override suspend fun update(accountId: String, id: String, version: Int, definition: AutomationDefinition): Automation =
        calls.withAccount(accountId, resource = true) { it.updateAutomation(id, version, definition.input()).domain() }

    override suspend fun delete(accountId: String, id: String) = calls.withAccount(accountId, resource = true) { it.deleteAutomation(id) }
    override suspend fun enable(accountId: String, id: String): Automation = calls.withAccount(accountId, resource = true) { it.enableAutomation(id).domain() }
    override suspend fun disable(accountId: String, id: String): Automation = calls.withAccount(accountId, resource = true) { it.disableAutomation(id).domain() }
    override suspend fun rotateSecret(accountId: String, id: String): CreatedAutomation = calls.withAccount(accountId, resource = true) { it.rotateAutomationSecret(id).domain() }

    override suspend fun test(accountId: String, id: String, payload: JsonObject?, idempotencyKey: String): AutomationRun =
        calls.withAccount(accountId, resource = true) { it.runAutomation(id, payload, idempotencyKey).domain() }

    override suspend fun preview(accountId: String, id: String, payload: JsonObject?): AutomationPreview =
        calls.withAccount(accountId, resource = true) { it.previewAutomationRun(id, payload).domain() }

    override suspend fun previewDraft(accountId: String, definition: AutomationDefinition, payload: JsonObject?): AutomationPreview =
        calls.withAccount(accountId) { it.previewAutomation(definition.input(), payload).domain() }

    override suspend fun runs(accountId: String, id: String, before: String?, sources: Set<RunSource>, statuses: Set<String>): AutomationRunPage =
        calls.withAccount(accountId, resource = true) { client ->
            val page = client.automationRuns(id, before = before, limit = PAGE, sources = sources.map { it.wire }, statuses = statuses.toList())
            AutomationRunPage(page.runs.map { it.domain() }, page.nextBefore)
        }

    override suspend fun run(accountId: String, id: String, runId: String): AutomationRun = calls.withAccount(accountId, resource = true) { it.automationRun(id, runId).domain() }
    override suspend fun cancelRun(accountId: String, id: String, runId: String): AutomationRun = calls.withAccount(accountId, resource = true) { it.cancelAutomationRun(id, runId).domain() }

    private companion object { const val PAGE = 50 }
}

internal fun AutomationTriggerView.domain() = AutomationTrigger(TriggerKind.of(kind), cron, timezone, catchup, catchupGraceMinutes, rawKind = kind)
internal fun AutomationActionView.domain() = AutomationAction(ActionKind.of(kind), profileId, model, effort, maxRuntimeMinutes, rawKind = kind)
internal fun AutomationVariableView.domain() = AutomationVariable(name, path, type, values, maxLength, required, sensitive)
private fun AutomationRunSummaryView.domain() = RunSummary(id, RunSource.of(source), status, queuedAt)

internal fun AutomationView.domain() = Automation(
    id = id, ownerId = ownerId, ownerName = ownerName, enabled = enabled, disabledReason = disabledReason, disabledBy = disabledBy, version = version,
    definition = AutomationDefinition(name, trigger.domain(), action.domain(), prompt, variables.map { it.domain() }, samplePayload?.let(::prettyPayload), overlap, maxRunsPerDay),
    nextRunAt = nextRunAt, upcomingRuns = upcomingRuns, lastRun = lastRun?.domain(), consecutiveFailures = consecutiveFailures,
    webhook = webhook?.let { AutomationWebhook(it.path, it.versionedPath, it.secretRotatedAt, it.previousSecretValidUntil) },
    createdAt = createdAt, updatedAt = updatedAt,
)

internal fun AutomationSummaryView.domain() = AutomationSummary(
    id, ownerId, ownerName, name, enabled, disabledReason, version, trigger.domain(), ActionKind.of(actionKind), nextRunAt, lastRun?.domain(), consecutiveFailures, updatedAt,
)

internal fun CreatedAutomationView.domain() = CreatedAutomation(automation.domain(), secret)

private fun AutomationVariableValueView.domain() = AutomationVariableValue(name, type, value, sensitive, present)

internal fun AutomationRunView.domain() = AutomationRun(
    id, automationId, automationVersion, RunSource.of(source), status, reason, sessionId, scheduledFor, queuedAt, startedAt, endedAt, cancelRequested, variables.map { it.domain() },
)

internal fun AutomationPreviewView.domain() = AutomationPreview(renderedPrompt, variables.map { it.domain() }, problems.map { AutomationProblem(it.path, it.rule) }, wouldSkip, nextRuns)

/** The wire form of a definition; a sample that is not a JSON object is dropped (the use case checks it before). */
internal fun AutomationDefinition.input() = AutomationInput(
    name = name.trim(),
    trigger = AutomationTriggerView(trigger.rawKind, trigger.cron?.trim(), trigger.timezone?.trim(), trigger.catchup, trigger.catchupGraceMinutes),
    action = AutomationActionView(action.rawKind, action.profileId, action.model?.takeIf { it.isNotBlank() }, action.effort?.takeIf { it.isNotBlank() }, action.maxRuntimeMinutes),
    prompt = prompt,
    variables = if (trigger.kind == TriggerKind.WEBHOOK) variables.map { AutomationVariableView(it.name.trim(), it.path.trim(), it.type, it.values, it.maxLength, it.required, it.sensitive) } else emptyList(),
    samplePayload = if (trigger.kind == TriggerKind.WEBHOOK) samplePayload?.takeIf { it.isNotBlank() }?.let(::parsePayload) else null,
    overlap = overlap,
    maxRunsPerDay = maxRunsPerDay,
)
