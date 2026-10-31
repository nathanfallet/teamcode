package me.nathanfallet.teamcode.cli.automations

import com.github.ajalt.clikt.core.UsageError
import kotlin.time.Clock
import kotlin.time.Instant
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.CliUserException

private fun time(millis: Long) = Instant.fromEpochMilliseconds(millis).toString()

internal fun AutomationTriggerView.describe(): String = when (kind) {
    "cron" -> "cron \"$cron\" $timezone"
    else -> kind
}

private fun state(enabled: Boolean, reason: String?) = if (enabled) "enabled" else "paused" + (reason?.let { " ($it)" } ?: "")

/** One row of `automations list`: id, name, state, trigger, version, next slot, latest run and failures in a row. */
internal fun AutomationSummaryView.line() = listOf(id, name, state(enabled, disabledReason), trigger.describe() + if (actionKind == "workflow") " -> workflow" else "", "v$version",
    "next " + (nextRunAt?.let(::time) ?: "-"), "last " + (lastRun?.let { "${it.status} (${it.source})" } ?: "-"),
    if (consecutiveFailures > 0) "$consecutiveFailures failed in a row" else "").joinToString("\t").trimEnd()

/** One row of `automations runs`: id, status and reason, trigger, definition version, queued time and session. */
internal fun AutomationRunView.line() = listOf(id, status + (reason?.let { " ($it)" } ?: "") + if (cancelRequested) " (cancel requested)" else "", source,
    "v$automationVersion", "queued ${time(queuedAt)}",
    if (workflowRunId != null) "workflow run $workflowRunId" else "session " + (sessionId ?: "-")).joinToString("\t")

/**
 * What to tell after a real test run. The server records a run it did not start (`skipped_overlap`, `skipped_budget`)
 * with a 202 like any other, and a retry with the same idempotency key returns the earlier run, which may have ended:
 * following either for a new session would wait for nothing.
 */
internal fun AutomationRunView.testHint(automationId: String): String {
    val history = "teamcode automations runs $automationId --source test"
    return when {
        status !in Automations.finalRunStatuses -> "Follow it: $history (the session, or the workflow run, appears once it starts)."
        status.startsWith("skipped") -> "No session was started" + (reason?.let { " ($it)" } ?: "") +
            ": the run is kept in the history ($history); wait for the running one, or raise --max-runs-per-day."
        else -> "This run already ended as $status: $history."
    }
}

/** How the webhook URL is shown: `signed` (the sender signs the request) or `secret` (the secret is part of the URL). */
internal enum class WebhookUrlMode { SIGNED, SECRET }

/**
 * Which version the URL follows: `latest` (the default, no version in the path), `current` (the automation's version
 * now), `vN` or `N` (only while the automation is at that version). Returns the version to pin, or null for latest.
 */
internal fun parseUrlVersion(text: String, current: Int): Int? {
    val value = text.lowercase()
    if (value == "latest") return null
    if (value == "current") return current
    return value.removePrefix("v").toIntOrNull()?.takeIf { it >= 1 } ?: throw UsageError("--url-version is latest, current, or a version such as v3.")
}

private val VERSION_SEGMENT = Regex("/v/\\d+(?=/|$)")

/**
 * The webhook address: [AutomationWebhookView.path] follows the latest version; [pinned] uses the server's versioned path
 * of the automation's [version], with the number replaced. [secret] is appended in secret mode.
 */
internal fun webhookUrl(origin: String, webhook: AutomationWebhookView, version: Int, pinned: Int?, mode: WebhookUrlMode, secret: String?): String {
    val path = if (pinned == null) webhook.path else
        VERSION_SEGMENT.replaceFirst(webhook.versionedPath, "/v/$pinned").takeIf { it != webhook.versionedPath || pinned == version }
            ?: throw CliUserException("The server's webhook path has no /v/<version> segment, so a pinned URL cannot be written: use --url-version latest.")
    return origin.trimEnd('/') + path + if (mode == WebhookUrlMode.SECRET) "/" + (secret ?: "<secret>") else ""
}

/** The lines that tell a person how to call the webhook, for `show`, `create` and `rotate-secret`. */
internal fun webhookInstructions(origin: String, view: AutomationView, pinned: Int?, mode: WebhookUrlMode, secret: String?, now: Long = Clock.System.now().toEpochMilliseconds()): List<String> {
    val webhook = view.webhook ?: return emptyList()
    val url = webhookUrl(origin, webhook, view.version, pinned, mode, secret)
    return buildList {
        add("Webhook:      POST $url")
        if (mode == WebhookUrlMode.SIGNED) {
            add("              Sign the body: ${AutomationHeaders.TIMESTAMP}: <unix seconds>, ${AutomationHeaders.SIGNATURE}: v1=<hex HMAC-SHA256 of \"<timestamp>.<body>\" keyed with the secret>.")
        } else {
            add("              Anyone who reads this URL can trigger the automation: treat all of it as the secret.")
            if (secret == null) add("              The secret is shown at creation and rotation only: replace <secret> with it.")
        }
        add("              " + if (pinned == null) "Follows the current version of the automation (v${view.version} now)."
            else "Runs only while the automation is at v$pinned" + if (pinned != view.version) " (it is at v${view.version} now: this URL answers 409 automation_version_changed)." else ".")
        webhook.previousSecretValidUntil?.let { add("              The former secret " + (if (it > now) "is" else "was") + " accepted until ${time(it)}.") }
    }
}

/** The row of a list, for an automation read whole. */
internal fun AutomationView.toSummary() = AutomationSummaryView(id, ownerId, ownerName, name, enabled, disabledReason, version, trigger, action.kind, nextRunAt, lastRun, consecutiveFailures, updatedAt)

internal fun AutomationView.details(origin: String, pinned: Int? = null, mode: WebhookUrlMode = WebhookUrlMode.SIGNED, secret: String? = null) = buildList {
    add("Automation:   $id")
    add("Name:         $name")
    add("State:        ${state(enabled, disabledReason)}" + (disabledBy?.let { ", paused by $it" } ?: ""))
    add("Owner:        $ownerName ($ownerId)")
    add("Version:      $version")
    add("Trigger:      ${trigger.describe()}" + if (trigger.kind == "cron") ", catch-up ${trigger.catchup ?: "once"}" +
        (trigger.catchupGraceMinutes?.let { " (grace $it min)" } ?: "") else "")
    if (action.kind == "workflow") add("Action:       workflow ${action.workflowId}" + (action.profileId?.let { " with profile $it for its agent steps" } ?: ""))
    else add("Action:       ${action.kind}" + (action.profileId?.let { " on profile $it" } ?: "") +
        listOfNotNull(action.model, action.effort).joinToString(" ", prefix = " ").trimEnd() + ", at most ${action.maxRuntimeMinutes} min")
    if (action.kind == "workflow") action.inputs.forEach { (key, text) -> add("Input:        $key = $text") }
    add("Overlap:      $overlap, at most $maxRunsPerDay runs per 24 h")
    nextRunAt?.let { add("Next run:     ${time(it)}") }
    if (upcomingRuns.isNotEmpty()) add("Upcoming:     " + upcomingRuns.joinToString(", ", transform = ::time))
    lastRun?.let { add("Last run:     ${it.id} ${it.status} (${it.source}) ${time(it.queuedAt)}") }
    if (consecutiveFailures > 0) add("Failures:     $consecutiveFailures in a row")
    variables.forEach { variable ->
        add("Variable:     {{${variable.name}}} <- ${variable.path} (${variable.type}" + (if (variable.type == "enum") " ${variable.values.joinToString("|")}" else "") +
            ", max ${variable.maxLength}, ${if (variable.required) "required" else "optional"}${if (variable.sensitive) ", sensitive" else ""})")
    }
    addAll(webhookInstructions(origin, this@details, pinned, mode, secret))
    if (action.kind != "workflow") { add("Prompt:"); prompt.lines().forEach { add("  $it") } }
}.joinToString("\n")

/** A dry run or draft check: the problems, or the prompt the agent would get, with each variable (sensitive ones masked). */
internal fun AutomationPreviewView.text() = buildList {
    if (problems.isNotEmpty()) {
        add("The payload does not validate; nothing would start:")
        problems.forEach { add("  ${it.path}\t${it.rule}") }
    }
    variables.forEach { add("Variable:     {{${it.name}}} (${it.type}) = " + when {
        it.sensitive -> "(masked)"
        !it.present -> "(missing, renders empty)"
        else -> it.value ?: "(none)"
    }) }
    profileVersion?.let { add("Profile:      version $it") }
    wouldSkip?.let { add("A real trigger would be skipped now: $it.") }
    if (nextRuns.isNotEmpty()) add("Next runs:    " + nextRuns.joinToString(", ", transform = ::time))
    if (renderedPrompt != null && variables.any { it.sensitive }) add("Note:         the prompt below holds the values of the sensitive variables, as the agent would get them.")
    renderedPrompt?.let { add("Rendered prompt:"); it.lines().forEach { line -> add("  $line") } }
    if (renderedInputs.isNotEmpty()) {
        if (variables.any { it.sensitive }) add("Note:         the inputs below hold the values of the sensitive variables, as the workflow run would get them.")
        add("Rendered inputs:"); renderedInputs.forEach { (key, value) -> add("  $key = ${value.lines().joinToString("\n    ")}") }
    }
}.joinToString("\n")
