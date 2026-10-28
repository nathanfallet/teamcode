package me.nathanfallet.teamcode.companion.domain.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** What starts an automation. [UNKNOWN] is a kind a newer server added: shown as text. */
enum class TriggerKind(val wire: String) {
    CRON("cron"), WEBHOOK("webhook"), UNKNOWN("");
    companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN }
}

/** What a run starts. `remote` is part of the contract but refused by the server until it can start one unattended. */
enum class ActionKind(val wire: String) {
    CLOUD("cloud"), REMOTE("remote"), UNKNOWN("");
    companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire && it != ActionKind.UNKNOWN } ?: UNKNOWN }
}

/** Where a run came from. */
enum class RunSource(val wire: String) {
    CRON("cron"), WEBHOOK("webhook"), TEST("test"), UNKNOWN("");
    companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN }
}

/** The automations the list shows: the member's own, or (administrators) the team's. */
enum class AutomationScope(val wire: String?) { MINE(null), TEAM("all") }

/**
 * The trigger: five-field [cron] read in the IANA [timezone] (at least five minutes between two runs), or a webhook.
 * [catchup] is `once` (default) or `skip`.
 */
data class AutomationTrigger(
    val kind: TriggerKind,
    val cron: String? = null,
    val timezone: String? = null,
    val catchup: String? = null,
    val catchupGraceMinutes: Int? = null,
    /** The wire name when [kind] is [TriggerKind.UNKNOWN]. */
    val rawKind: String = kind.wire,
)

data class AutomationAction(
    val kind: ActionKind,
    val profileId: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val maxRuntimeMinutes: Int = 60,
    val rawKind: String = kind.wire,
)

/** A payload variable, written `{{name}}` in the prompt and read at [path] (`$.ticket.number`) of a webhook body. */
data class AutomationVariable(
    val name: String,
    val path: String,
    val type: String = "string",
    val values: List<String> = emptyList(),
    val maxLength: Int = 256,
    val required: Boolean = true,
    val sensitive: Boolean = false,
)

/** The editable definition, as the form edits it and the server saves it. [samplePayload] is JSON text (webhook only). */
data class AutomationDefinition(
    val name: String,
    val trigger: AutomationTrigger,
    val action: AutomationAction,
    val prompt: String,
    val variables: List<AutomationVariable> = emptyList(),
    val samplePayload: String? = null,
    val overlap: String = "skip",
    val maxRunsPerDay: Int = 24,
)

/** The webhook's endpoints, relative to the server origin; times in epoch milliseconds. */
data class AutomationWebhook(val path: String, val versionedPath: String, val secretRotatedAt: Long, val previousSecretValidUntil: Long? = null)

/** The latest run of an automation, for a list. */
data class RunSummary(val id: String, val source: RunSource, val status: String, val queuedAt: Long)

data class Automation(
    val id: String,
    val ownerId: String,
    val ownerName: String,
    val enabled: Boolean,
    val disabledReason: String?,
    val disabledBy: String?,
    val version: Int,
    val definition: AutomationDefinition,
    val nextRunAt: Long?,
    val upcomingRuns: List<Long>,
    val lastRun: RunSummary?,
    val consecutiveFailures: Int,
    val webhook: AutomationWebhook?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val name: String get() = definition.name
    val trigger: AutomationTrigger get() = definition.trigger
    fun ownedBy(memberId: String) = ownerId == memberId
}

/** An automation in a list. */
data class AutomationSummary(
    val id: String,
    val ownerId: String,
    val ownerName: String,
    val name: String,
    val enabled: Boolean,
    val disabledReason: String?,
    val version: Int,
    val trigger: AutomationTrigger,
    val actionKind: ActionKind,
    val nextRunAt: Long?,
    val lastRun: RunSummary?,
    val consecutiveFailures: Int,
    val updatedAt: Long,
) {
    fun ownedBy(memberId: String) = ownerId == memberId
}

data class AutomationPage(val automations: List<AutomationSummary>, val nextBefore: String?)

/** [secret] (`whsec_…`) comes with a creation and a rotation only: it is shown once and kept nowhere. */
data class CreatedAutomation(val automation: Automation, val secret: String?)

data class AutomationVariableValue(val name: String, val type: String, val value: String?, val sensitive: Boolean, val present: Boolean)

data class AutomationRun(
    val id: String,
    val automationId: String,
    val automationVersion: Int,
    val source: RunSource,
    val status: String,
    val reason: String?,
    val sessionId: String?,
    val scheduledFor: Long?,
    val queuedAt: Long,
    val startedAt: Long?,
    val endedAt: Long?,
    val cancelRequested: Boolean,
    val variables: List<AutomationVariableValue>,
) {
    val final: Boolean get() = status in FINAL_STATUSES
    /** A waiting or working run can be withdrawn. */
    val cancellable: Boolean get() = !final && !cancelRequested

    companion object {
        val FINAL_STATUSES = setOf("succeeded", "failed", "timed_out", "cancelled", "expired", "skipped_overlap", "skipped_budget", "invalid_payload")
        /** The statuses that count as a failure for the list's "N consecutive failures". */
        val FAILED_STATUSES = setOf("failed", "timed_out", "expired", "invalid_payload")
    }
}

data class AutomationRunPage(val runs: List<AutomationRun>, val nextBefore: String?)

/** A validation problem of a payload or a draft: the JSON [path] and the [rule] it breaks. */
data class AutomationProblem(val path: String, val rule: String)

/** A dry run or a draft preview: the prompt the agent would receive, or the problems that stop it. */
data class AutomationPreview(
    val renderedPrompt: String?,
    val variables: List<AutomationVariableValue>,
    val problems: List<AutomationProblem>,
    val wouldSkip: String?,
    val nextRuns: List<Long>,
)

/** A payload typed by the member, as the object it must be: null when it is not a JSON object. */
fun parsePayload(text: String): JsonObject? = try {
    Json.parseToJsonElement(text) as? JsonObject
} catch (_: kotlinx.serialization.SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** JSON text of an object, indented, for the payload editor. */
fun prettyPayload(payload: JsonObject): String = PRETTY.encodeToString(JsonObject.serializer(), payload)
private val PRETTY = Json { prettyPrint = true }
