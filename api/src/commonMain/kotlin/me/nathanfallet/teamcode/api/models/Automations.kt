package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire names of the closed enums of automations (`automations.v1`, docs/automations.md). Clients must still tolerate
 * an unknown value from a newer server: show it as text.
 */
object Automations {
    const val CAPABILITY = "automations.v1"
    val triggerKinds = listOf("cron", "webhook")
    /** `remote` is part of the contract but refused (`422 not_available_yet`) until the server can start one unattended. */
    val actionKinds = listOf("cloud", "remote", "workflow")
    val catchupPolicies = listOf("skip", "once")
    val overlapPolicies = listOf("skip", "queue", "allow")
    val variableTypes = listOf("string", "int", "number", "boolean", "enum")
    val runSources = listOf("cron", "webhook", "test")
    val runStatuses = listOf("queued", "starting", "running", "succeeded", "failed", "timed_out", "cancelled", "expired", "skipped_overlap", "skipped_budget", "invalid_payload")
    /** Statuses after which a run never changes again. */
    val finalRunStatuses = runStatuses - setOf("queued", "starting", "running")
    /** Path segment of a webhook URL that follows the automation's current version. */
    const val LATEST_VERSION = "latest"
}

/**
 * When an automation fires. [kind] `cron`: a five-field [cron] expression (minute, hour, day of month, month, day of
 * week) read in the IANA [timezone], at least five minutes between two runs; after an outage, [catchup] `once` (the
 * default) runs the latest missed slot once if it is at most [catchupGraceMinutes] old, `skip` runs nothing.
 * [kind] `webhook`: a signed or secret-URL HTTP request (docs/automations.md); the other fields are null.
 */
@Serializable
data class AutomationTriggerView(
    val kind: String,
    val cron: String? = null,
    val timezone: String? = null,
    val catchup: String? = null,
    val catchupGraceMinutes: Int? = null,
)

/**
 * What a run starts. [kind] `cloud`: a cloud session on [profileId] (a profile assigned to the owner, at its latest
 * version), with the optional agent [model] and [effort]. [maxRuntimeMinutes] (1 to 1440, 60 by default) ends a run
 * still working after that long (`timed_out`, its session paused). [kind] `remote` is refused for now.
 * [kind] `workflow`: a run of the saved workflow [workflowId] started as the owner, instead of a session. [inputs] maps a
 * workflow input id to its value, a template over the same `{{…}}` variables as a prompt; an empty result leaves the
 * workflow's default. [profileId] (optional) is the profile the workflow's agent nodes run with when they name none;
 * [model], [effort] and [maxRuntimeMinutes] do not apply.
 */
@Serializable
data class AutomationActionView(
    val kind: String,
    val profileId: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val maxRuntimeMinutes: Int = 60,
    val workflowId: String? = null,
    val inputs: Map<String, String> = emptyMap(),
)

/**
 * A payload variable, written `{{name}}` in the prompt. [path] is a JSON path into the webhook body (`$.ticket.number`,
 * `$.items[0].id`). [type] `string`, `int`, `number`, `boolean` or `enum` (one of [values]); a string longer than
 * [maxLength] (1 to 4096) is refused. A missing [required] variable refuses the run; a missing optional one renders
 * empty. A [sensitive] value is masked in previews.
 */
@Serializable
data class AutomationVariableView(
    val name: String,
    val path: String,
    val type: String = "string",
    val values: List<String> = emptyList(),
    val maxLength: Int = 256,
    val required: Boolean = true,
    val sensitive: Boolean = false,
)

/**
 * The editable definition. [prompt] (1 to 32,000 characters) may use the [variables] and the run variables `run.id`,
 * `run.date`, `run.time`, `run.timezone`, `run.source`, `automation.id` and `automation.name`. [samplePayload] (webhook
 * only) pre-fills a test and is never used by a real run. [overlap] `skip` (default), `queue` (one waiting run at most)
 * or `allow`. [maxRunsPerDay] (1 to 1000) caps cron and webhook runs over any 24 hours; tests are not counted.
 */
@Serializable
data class AutomationInput(
    val name: String,
    val trigger: AutomationTriggerView,
    val action: AutomationActionView,
    /** Empty for a `workflow` action (its inputs carry the text). */
    val prompt: String = "",
    val variables: List<AutomationVariableView> = emptyList(),
    val samplePayload: JsonObject? = null,
    val overlap: String = "skip",
    val maxRunsPerDay: Int = 24,
)

/** An automation is created disabled: test it with `POST …/run`, then enable it. */
@Serializable
data class CreateAutomationRequest(val automation: AutomationInput)

/** [version] is the one being replaced (`409 stale_version` otherwise). Enabling and pausing have their own routes. */
@Serializable
data class UpdateAutomationRequest(val version: Int, val automation: AutomationInput)

/**
 * A webhook automation's endpoints, relative to the server origin. [path] follows the latest version; [versionedPath]
 * runs only while the automation is at [AutomationView.version]. Append `/<secret>` to either for the secret-URL mode,
 * or sign the request (docs/automations.md). [previousSecretValidUntil]: after a rotation, the former secret is still
 * accepted until then.
 */
@Serializable
data class AutomationWebhookView(
    val path: String,
    val versionedPath: String,
    val secretRotatedAt: Long,
    val previousSecretValidUntil: Long? = null,
)

/** The latest run, for a list: its status, source and time. */
@Serializable
data class AutomationRunSummaryView(val id: String, val source: String, val status: String, val queuedAt: Long)

/**
 * An automation. [version] changes with every saved definition (not with enabling, pausing or a secret rotation).
 * [disabledReason]: `disabled` (paused by someone, see [disabledBy]) or `owner_unavailable` (the owner was disabled,
 * changed role or password: the owner enables it again). [consecutiveFailures] counts cron and webhook runs only.
 * [nextRunAt] and [upcomingRuns] (the next three slots) are set for an enabled cron automation.
 */
@Serializable
data class AutomationView(
    val id: String,
    val ownerId: String,
    val ownerName: String,
    val name: String,
    val enabled: Boolean,
    val disabledReason: String? = null,
    val disabledBy: String? = null,
    val version: Int,
    val trigger: AutomationTriggerView,
    val action: AutomationActionView,
    val prompt: String,
    val variables: List<AutomationVariableView>,
    val samplePayload: JsonObject? = null,
    val overlap: String,
    val maxRunsPerDay: Int,
    val nextRunAt: Long? = null,
    val upcomingRuns: List<Long> = emptyList(),
    val lastRun: AutomationRunSummaryView? = null,
    val consecutiveFailures: Int = 0,
    val webhook: AutomationWebhookView? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** An automation in a list: no prompt, variables or sample. */
@Serializable
data class AutomationSummaryView(
    val id: String,
    val ownerId: String,
    val ownerName: String,
    val name: String,
    val enabled: Boolean,
    val disabledReason: String? = null,
    val version: Int,
    val trigger: AutomationTriggerView,
    val actionKind: String,
    val nextRunAt: Long? = null,
    val lastRun: AutomationRunSummaryView? = null,
    val consecutiveFailures: Int = 0,
    val updatedAt: Long,
)

/** [nextBefore]: pass it as `before` for the next page; null on the last one. */
@Serializable
data class AutomationPageView(val automations: List<AutomationSummaryView>, val nextBefore: String? = null)

/** [secret] (`whsec_…`) is returned by creation of a webhook automation and by rotation only: store it, it cannot be read again. */
@Serializable
data class CreatedAutomationView(val automation: AutomationView, val secret: String? = null)

/**
 * One trigger of an automation. [status] is one of [Automations.runStatuses]; [reason] a short code for a failed, skipped
 * or expired run (`owner_unavailable`, `profile_unassigned`, `capacity_timeout`, …), never a message. [sessionId] is
 * the cloud session it created (labelled `automation=<id>`, and `automation-test=1` for a test). [scheduledFor] is the
 * cron slot. [variables] are the extracted values with sensitive ones masked (detail only).
 */
@Serializable
data class AutomationRunView(
    val id: String,
    val automationId: String,
    val automationVersion: Int,
    val source: String,
    val status: String,
    val reason: String? = null,
    val sessionId: String? = null,
    val profileVersion: Int? = null,
    val scheduledFor: Long? = null,
    val queuedAt: Long,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val cancelRequested: Boolean = false,
    val initiatorId: String? = null,
    val variables: List<AutomationVariableValueView> = emptyList(),
    /** The workflow run a `workflow` action started (`GET /api/v1/workflows/runs/{id}`); null for a session. */
    val workflowRunId: String? = null,
)

/** Newest first; [nextBefore] is the cursor of the next page. */
@Serializable
data class AutomationRunPageView(val runs: List<AutomationRunView>, val nextBefore: String? = null)

/** What `/trigger` answers (`202`): never the automation or the prompt. */
@Serializable
data class AutomationTriggerAcceptedView(val runId: String, val status: String)

/** A test (`POST …/run`): [payload] for a webhook automation only (required there); [dryRun] previews without a run. */
@Serializable
data class RunAutomationRequest(val payload: JsonObject? = null, val dryRun: Boolean = false)

/** A draft checked and rendered without saving it (`POST /api/v1/automations/preview`). */
@Serializable
data class PreviewAutomationRequest(val automation: AutomationInput, val payload: JsonObject? = null)

/** An extracted variable; [value] is null when [sensitive] or missing. */
@Serializable
data class AutomationVariableValueView(val name: String, val type: String, val value: String? = null, val sensitive: Boolean = false, val present: Boolean = true)

/** A validation problem: the JSON [path] (or field) and the [rule] it breaks (`required`, `type:int`, `max_length`, `enum`, …), never the value. */
@Serializable
data class AutomationProblemView(val path: String, val rule: String)

/**
 * A dry run or a draft preview. [renderedPrompt] is the prompt the agent would receive (null when [problems] is not
 * empty); it is never stored or logged. [wouldSkip]: `overlap`, `budget` or `disabled` when a real trigger would be
 * skipped now. [nextRuns]: the next cron slots.
 */
@Serializable
data class AutomationPreviewView(
    val renderedPrompt: String? = null,
    val variables: List<AutomationVariableValueView> = emptyList(),
    val problems: List<AutomationProblemView> = emptyList(),
    val profileVersion: Int? = null,
    val wouldSkip: String? = null,
    val nextRuns: List<Long> = emptyList(),
    /** A `workflow` action's inputs as the run would receive them; like [renderedPrompt], never stored or logged. */
    val renderedInputs: Map<String, String> = emptyMap(),
)

/** The webhook request headers. A signed request carries [TIMESTAMP] and [SIGNATURE]; [DELIVERY] or `Idempotency-Key` deduplicates. */
object AutomationHeaders {
    const val TIMESTAMP = "TeamCode-Timestamp"
    const val SIGNATURE = "TeamCode-Signature"
    const val DELIVERY = "TeamCode-Delivery"
}
