package me.nathanfallet.teamcode.companion.demo

import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.companion.domain.models.ActionKind
import me.nathanfallet.teamcode.companion.domain.models.Automation
import me.nathanfallet.teamcode.companion.domain.models.AutomationAction
import me.nathanfallet.teamcode.companion.domain.models.AutomationDefinition
import me.nathanfallet.teamcode.companion.domain.models.AutomationPage
import me.nathanfallet.teamcode.companion.domain.models.AutomationPreview
import me.nathanfallet.teamcode.companion.domain.models.AutomationRun
import me.nathanfallet.teamcode.companion.domain.models.AutomationRunPage
import me.nathanfallet.teamcode.companion.domain.models.AutomationScope
import me.nathanfallet.teamcode.companion.domain.models.AutomationSummary
import me.nathanfallet.teamcode.companion.domain.models.AutomationTrigger
import me.nathanfallet.teamcode.companion.domain.models.AutomationVariable
import me.nathanfallet.teamcode.companion.domain.models.AutomationVariableValue
import me.nathanfallet.teamcode.companion.domain.models.AutomationWebhook
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.CreatedAutomation
import me.nathanfallet.teamcode.companion.domain.models.RunSource
import me.nathanfallet.teamcode.companion.domain.models.RunSummary
import me.nathanfallet.teamcode.companion.domain.models.TriggerKind
import me.nathanfallet.teamcode.companion.domain.repositories.AutomationRepository
import me.nathanfallet.teamcode.companion.domain.services.Clock

/** The demo's one automation: a webhook that starts the "Fix a ticket" workflow, with runs that open the demo's workflow runs. */
internal object DemoAutomations {
    const val ID = "au1"
    private const val MINUTE = 60_000L

    fun automation(now: Long) = Automation(
        id = ID, ownerId = DemoContent.MEMBER_ID, ownerName = DemoContent.USERNAME, enabled = true, disabledReason = null, disabledBy = null, version = 3,
        definition = AutomationDefinition(
            name = "Triage new tickets",
            trigger = AutomationTrigger(TriggerKind.WEBHOOK),
            action = AutomationAction(ActionKind.WORKFLOW, profileId = null, workflowId = "wf-ticket", inputs = mapOf("ticket" to "{{ticket.number}}", "notes" to "Priority {{ticket.priority}}. Keep the change small.")),
            prompt = "",
            variables = listOf(AutomationVariable("ticket.number", "$.ticket.number", "string"), AutomationVariable("ticket.priority", "$.ticket.priority", "string", required = false)),
            samplePayload = "{\n  \"ticket\": {\n    \"number\": \"ACME-231\",\n    \"priority\": \"high\"\n  }\n}",
        ),
        nextRunAt = null, upcomingRuns = emptyList(), lastRun = RunSummary("ar1", RunSource.WEBHOOK, "running", now - 6 * MINUTE), consecutiveFailures = 0,
        webhook = AutomationWebhook("/api/v1/automations/$ID/trigger", "/api/v1/automations/$ID/v/3/trigger", now - 2 * 24 * 60 * MINUTE),
        createdAt = now - 5 * 24 * 60 * MINUTE, updatedAt = now - 2 * 24 * 60 * MINUTE,
    )

    /** Newest first: one running (the demo's first workflow run), one that ended with its workflow run, one refused by the cap. */
    fun runs(now: Long): List<AutomationRun> {
        fun run(id: String, status: String, agoMinutes: Int, reason: String? = null, workflowRun: String? = null, endedAgo: Int? = null) = AutomationRun(
            id, ID, 3, RunSource.WEBHOOK, status, reason, null, null, now - agoMinutes * MINUTE, now - agoMinutes * MINUTE, endedAgo?.let { now - it * MINUTE }, false,
            listOf(AutomationVariableValue("ticket.number", "string", "ACME-231", sensitive = false, present = true)), workflowRun,
        )
        return listOf(
            run("ar1", "running", 6, workflowRun = "run1"),
            run("ar2", "succeeded", 130, workflowRun = "run3", endedAgo = 80),
            run("ar3", "failed", 400, reason = "run_limit", endedAgo = 400),
        )
    }
}

/** The demo's automations: one workflow automation that can be paused and armed in memory; everything else is out of reach in the demo. */
internal class DemoAutomationRepository(private val demo: DemoMode, private val clock: Clock) : AutomationRepository {
    private var owner: Any? = null
    private var current: Automation = DemoAutomations.automation(0)
    private var history: List<AutomationRun> = emptyList()

    private fun state(): Automation {
        val store = demo.store()
        if (owner !== store) {
            val now = clock.nowMillis()
            owner = store; current = DemoAutomations.automation(now); history = DemoAutomations.runs(now)
        }
        return current
    }

    private fun summary(a: Automation) = AutomationSummary(a.id, a.ownerId, a.ownerName, a.name, a.enabled, a.disabledReason, a.version, a.trigger, a.definition.action.kind, a.nextRunAt, a.lastRun, a.consecutiveFailures, a.updatedAt)
    private fun find(id: String) = state().takeIf { it.id == id } ?: throw CompanionException.NotFound()

    override suspend fun automations(accountId: String, scope: AutomationScope, before: String?) = AutomationPage(if (before == null) listOf(summary(state())) else emptyList(), null)
    override suspend fun automation(accountId: String, id: String): Automation = find(id)
    override suspend fun create(accountId: String, definition: AutomationDefinition): CreatedAutomation = throw CompanionException.Unsupported("automations.demo")
    override suspend fun update(accountId: String, id: String, version: Int, definition: AutomationDefinition): Automation = throw CompanionException.Unsupported("automations.demo")
    override suspend fun delete(accountId: String, id: String) = throw CompanionException.Unsupported("automations.demo")
    override suspend fun enable(accountId: String, id: String): Automation = find(id).copy(enabled = true, disabledReason = null).also { current = it }
    override suspend fun disable(accountId: String, id: String): Automation = find(id).copy(enabled = false, disabledReason = "disabled", disabledBy = DemoContent.MEMBER_ID).also { current = it }
    override suspend fun rotateSecret(accountId: String, id: String): CreatedAutomation = throw CompanionException.Unsupported("automations.demo")
    override suspend fun test(accountId: String, id: String, payload: JsonObject?, idempotencyKey: String): AutomationRun = throw CompanionException.Unsupported("automations.demo")
    override suspend fun preview(accountId: String, id: String, payload: JsonObject?): AutomationPreview = render(find(id).definition)
    override suspend fun previewDraft(accountId: String, definition: AutomationDefinition, payload: JsonObject?): AutomationPreview = render(definition).also { state() }

    /** The inputs with the sample payload's values in place of the `{{…}}`. */
    private fun render(definition: AutomationDefinition): AutomationPreview {
        val values = mapOf("ticket.number" to "ACME-231", "ticket.priority" to "high", "run.date" to "today")
        val inputs = definition.action.inputs.mapValues { (_, text) -> Regex("\\{\\{\\s*([^{}]*?)\\s*\\}\\}").replace(text) { values[it.groupValues[1]].orEmpty() } }
        return AutomationPreview(null, values.filterKeys { it.startsWith("ticket") }.map { (name, value) -> AutomationVariableValue(name, "string", value, sensitive = false, present = true) }, emptyList(), null, emptyList(), inputs)
    }

    override suspend fun runs(accountId: String, id: String, before: String?, sources: Set<RunSource>, statuses: Set<String>): AutomationRunPage {
        find(id)
        val all = history.filter { (sources.isEmpty() || it.source in sources) && (statuses.isEmpty() || it.status in statuses) }
        return AutomationRunPage(if (before == null) all else emptyList(), null)
    }
    override suspend fun run(accountId: String, id: String, runId: String): AutomationRun = find(id).let { history.firstOrNull { it.id == runId } ?: throw CompanionException.NotFound() }
    override suspend fun cancelRun(accountId: String, id: String, runId: String): AutomationRun = throw CompanionException.Unsupported("automations.demo")
}

internal class DemoAwareAutomationRepository(private val real: AutomationRepository, private val demo: AutomationRepository) : AutomationRepository {
    private fun of(accountId: String) = if (accountId == DemoContent.ACCOUNT_ID) demo else real
    override suspend fun automations(accountId: String, scope: AutomationScope, before: String?) = of(accountId).automations(accountId, scope, before)
    override suspend fun automation(accountId: String, id: String) = of(accountId).automation(accountId, id)
    override suspend fun create(accountId: String, definition: AutomationDefinition) = of(accountId).create(accountId, definition)
    override suspend fun update(accountId: String, id: String, version: Int, definition: AutomationDefinition) = of(accountId).update(accountId, id, version, definition)
    override suspend fun delete(accountId: String, id: String) = of(accountId).delete(accountId, id)
    override suspend fun enable(accountId: String, id: String) = of(accountId).enable(accountId, id)
    override suspend fun disable(accountId: String, id: String) = of(accountId).disable(accountId, id)
    override suspend fun rotateSecret(accountId: String, id: String) = of(accountId).rotateSecret(accountId, id)
    override suspend fun test(accountId: String, id: String, payload: JsonObject?, idempotencyKey: String) = of(accountId).test(accountId, id, payload, idempotencyKey)
    override suspend fun preview(accountId: String, id: String, payload: JsonObject?) = of(accountId).preview(accountId, id, payload)
    override suspend fun previewDraft(accountId: String, definition: AutomationDefinition, payload: JsonObject?) = of(accountId).previewDraft(accountId, definition, payload)
    override suspend fun runs(accountId: String, id: String, before: String?, sources: Set<RunSource>, statuses: Set<String>) = of(accountId).runs(accountId, id, before, sources, statuses)
    override suspend fun run(accountId: String, id: String, runId: String) = of(accountId).run(accountId, id, runId)
    override suspend fun cancelRun(accountId: String, id: String, runId: String) = of(accountId).cancelRun(accountId, id, runId)
}
