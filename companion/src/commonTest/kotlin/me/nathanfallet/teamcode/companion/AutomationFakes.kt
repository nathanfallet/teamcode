package me.nathanfallet.teamcode.companion

import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.companion.domain.models.*
import me.nathanfallet.teamcode.companion.domain.repositories.AutomationRepository

fun cronDefinition(name: String = "Evening PR summary") = AutomationDefinition(
    name, AutomationTrigger(TriggerKind.CRON, "0 21 * * *", "Europe/Paris", "once", 360), AutomationAction(ActionKind.CLOUD, "p1", "sonnet", "medium", 20), "Summarize {{run.date}}.",
)

fun webhookDefinition(name: String = "Ticket fix") = AutomationDefinition(
    name, AutomationTrigger(TriggerKind.WEBHOOK), AutomationAction(ActionKind.CLOUD, "p1"), "Fix ticket {{ticket.number}}.",
    variables = listOf(AutomationVariable("ticket.number", "$.ticket.number", "int")), samplePayload = "{\n  \"ticket\": {\n    \"number\": 735\n  }\n}",
)

fun automation(id: String = "au1", definition: AutomationDefinition = cronDefinition(), ownerId: String = "m1", enabled: Boolean = false, version: Int = 1, failures: Int = 0) = Automation(
    id, ownerId, "Ada", enabled, if (enabled) null else "disabled", null, version, definition, if (enabled) 5_000_000L else null, emptyList(), null, failures,
    if (definition.trigger.kind == TriggerKind.WEBHOOK) AutomationWebhook("/api/v1/automations/$id/trigger", "/api/v1/automations/$id/v/$version/trigger", 1L) else null, 1L, 1L,
)

fun workflowAutomationDefinition(name: String = "Triage") = AutomationDefinition(
    name, AutomationTrigger(TriggerKind.WEBHOOK), AutomationAction(ActionKind.WORKFLOW, null, workflowId = "wf1", inputs = mapOf("ticket" to "T-{{ticket.number}}")), "",
    variables = listOf(AutomationVariable("ticket.number", "$.ticket.number", "int")),
)

fun summary(id: String = "au1", name: String = "Evening PR summary", ownerId: String = "m1", enabled: Boolean = true, failures: Int = 0, trigger: AutomationTrigger = cronDefinition().trigger) = AutomationSummary(
    id, ownerId, "Ada", name, enabled, if (enabled) null else "disabled", 1, trigger, ActionKind.CLOUD, if (enabled) 5_000_000L else null,
    RunSummary("r1", RunSource.CRON, "succeeded", 900_000L), failures, 1L,
)

fun run(id: String = "r1", source: RunSource = RunSource.CRON, status: String = "succeeded", sessionId: String? = "s1", reason: String? = null, workflowRunId: String? = null) = AutomationRun(
    id, "au1", 1, source, status, reason, sessionId, null, 1_000_000L, null, null, false, emptyList(), workflowRunId,
)

/** Automations answered from memory; every call is recorded in [calls]. */
class FakeAutomationRepository : AutomationRepository {
    val calls = mutableListOf<String>()
    var summaries: List<AutomationSummary> = listOf(summary("au1"), summary("au2", "Ticket fix", enabled = false, failures = 3, trigger = AutomationTrigger(TriggerKind.WEBHOOK)))
    var current: Automation = automation()
    var runs: List<AutomationRun> = listOf(run("r1"), run("r2", RunSource.TEST, "running"))
    var failure: Exception? = null
    var preview = AutomationPreview("Fix ticket 735.", listOf(AutomationVariableValue("ticket.number", "int", "735", false, true)), emptyList(), null, emptyList())
    var secret: String? = "whsec_new"
    val tested = mutableListOf<Triple<String, JsonObject?, String>>()
    val saved = mutableListOf<AutomationDefinition>()

    private fun record(call: String) { calls += call; failure?.let { throw it } }

    override suspend fun automations(accountId: String, scope: AutomationScope, before: String?): AutomationPage { record("list:${scope.name}:$before"); return AutomationPage(summaries, null) }
    override suspend fun automation(accountId: String, id: String): Automation { record("get:$id"); return current }
    override suspend fun create(accountId: String, definition: AutomationDefinition): CreatedAutomation { record("create"); saved += definition; return CreatedAutomation(automation("au9", definition), secret) }
    override suspend fun update(accountId: String, id: String, version: Int, definition: AutomationDefinition): Automation { record("update:$id:$version"); saved += definition; return current }
    override suspend fun delete(accountId: String, id: String) { record("delete:$id") }
    override suspend fun enable(accountId: String, id: String): Automation { record("enable:$id"); current = current.copy(enabled = true, disabledReason = null); return current }
    override suspend fun disable(accountId: String, id: String): Automation { record("disable:$id"); current = current.copy(enabled = false, disabledReason = "disabled"); return current }
    override suspend fun rotateSecret(accountId: String, id: String): CreatedAutomation { record("rotate:$id"); return CreatedAutomation(current, secret) }
    override suspend fun test(accountId: String, id: String, payload: JsonObject?, idempotencyKey: String): AutomationRun { record("test:$id"); tested += Triple(id, payload, idempotencyKey); return run("r9", RunSource.TEST, "queued", null) }
    override suspend fun preview(accountId: String, id: String, payload: JsonObject?): AutomationPreview { record("preview:$id"); return preview }
    override suspend fun previewDraft(accountId: String, definition: AutomationDefinition, payload: JsonObject?): AutomationPreview { record("draft"); return preview }
    override suspend fun runs(accountId: String, id: String, before: String?, sources: Set<RunSource>, statuses: Set<String>): AutomationRunPage {
        record("runs:$id:${sources.map { it.wire }.sorted()}")
        return AutomationRunPage(runs.filter { sources.isEmpty() || it.source in sources }, null)
    }
    override suspend fun run(accountId: String, id: String, runId: String): AutomationRun { record("run:$runId"); return run(runId, RunSource.TEST, "succeeded") }
    override suspend fun cancelRun(accountId: String, id: String, runId: String): AutomationRun { record("cancel:$runId"); return run(runId, status = "cancelled") }
}
