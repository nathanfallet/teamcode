package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import me.nathanfallet.teamcode.companion.domain.models.AutomationRun
import me.nathanfallet.teamcode.companion.domain.models.AutomationSummary
import me.nathanfallet.teamcode.companion.domain.models.AutomationTrigger
import me.nathanfallet.teamcode.companion.domain.models.RunSource
import me.nathanfallet.teamcode.companion.domain.models.TriggerKind
import me.nathanfallet.teamcode.companion.domain.models.visibleCode
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.stringResource

/** "in 5 min", "in 3 h", "in 2 d" for a time ahead of [now]; "any moment" for one already due. */
@Composable
fun untilTime(at: Long, now: Long): String {
    val minutes = (at - now) / 60_000
    return when {
        minutes < 1 -> stringResource(Res.string.automation_time_soon)
        minutes < 60 -> stringResource(Res.string.automation_time_in_minutes, minutes.toInt())
        minutes < 48 * 60 -> stringResource(Res.string.automation_time_in_hours, (minutes / 60).toInt())
        else -> stringResource(Res.string.automation_time_in_days, (minutes / 1440).toInt())
    }
}

/** What fires the automation, in one line: the schedule with its zone, or "Webhook". */
@Composable
fun triggerLine(trigger: AutomationTrigger): String = when (trigger.kind) {
    TriggerKind.CRON -> stringResource(Res.string.automation_trigger_cron_line, trigger.cron.orEmpty(), trigger.timezone.orEmpty())
    TriggerKind.WEBHOOK -> stringResource(Res.string.automation_trigger_webhook)
    TriggerKind.UNKNOWN -> trigger.rawKind
}

/** A run's status in words; a status a newer server adds is shown as it is. */
@Composable
fun runStatusWords(status: String): String = when (status) {
    "queued" -> stringResource(Res.string.automation_status_queued)
    "starting" -> stringResource(Res.string.automation_status_starting)
    "running" -> stringResource(Res.string.automation_status_running)
    "succeeded" -> stringResource(Res.string.automation_status_succeeded)
    "failed" -> stringResource(Res.string.automation_status_failed)
    "timed_out" -> stringResource(Res.string.automation_status_timed_out)
    "cancelled" -> stringResource(Res.string.automation_status_cancelled)
    "expired" -> stringResource(Res.string.automation_status_expired)
    "skipped_overlap" -> stringResource(Res.string.automation_status_skipped_overlap)
    "skipped_budget" -> stringResource(Res.string.automation_status_skipped_budget)
    "invalid_payload" -> stringResource(Res.string.automation_status_invalid_payload)
    else -> status
}

fun runStatusTone(status: String): Tone = when (status) {
    "succeeded" -> Tone.ACCENT
    "running", "starting", "queued" -> Tone.WARN
    in AutomationRun.FAILED_STATUSES -> Tone.DANGER
    else -> Tone.MUTED
}

@Composable
fun runSourceWords(source: RunSource): String = when (source) {
    RunSource.CRON -> stringResource(Res.string.automation_source_cron)
    RunSource.WEBHOOK -> stringResource(Res.string.automation_source_webhook)
    RunSource.TEST -> stringResource(Res.string.automation_source_test)
    RunSource.UNKNOWN -> ""
}

/** Why a run failed, was skipped or expired: the codes the app words, else the code itself when it is safe to show. */
@Composable
fun runReasonWords(reason: String?): String? = when (reason) {
    null -> null
    "owner_unavailable" -> stringResource(Res.string.automation_reason_owner_unavailable)
    "profile_unassigned" -> stringResource(Res.string.automation_reason_profile_unassigned)
    "profile_not_found" -> stringResource(Res.string.automation_reason_profile_not_found)
    "capacity_timeout" -> stringResource(Res.string.automation_reason_capacity_timeout)
    "cloud_session_limit" -> stringResource(Res.string.automation_reason_cloud_session_limit)
    "session_failed" -> stringResource(Res.string.automation_reason_session_failed)
    "message_rejected" -> stringResource(Res.string.automation_reason_message_rejected)
    "workflow_missing" -> stringResource(Res.string.automation_reason_workflow_missing)
    "run_limit" -> stringResource(Res.string.automation_reason_run_limit)
    "invalid_inputs" -> stringResource(Res.string.automation_reason_invalid_inputs)
    "workflow_failed" -> stringResource(Res.string.automation_reason_workflow_failed)
    "workflow_cancelled" -> stringResource(Res.string.automation_reason_workflow_cancelled)
    else -> visibleCode(reason)
}

/** "Active", "Paused", or "Paused: its owner is unavailable". */
@Composable
fun AutomationStateChip(enabled: Boolean, disabledReason: String?, modifier: Modifier = Modifier) {
    when {
        enabled -> Chip(stringResource(Res.string.automation_state_active), tone = Tone.ACCENT, dot = true, modifier = modifier)
        disabledReason == "owner_unavailable" -> Chip(stringResource(Res.string.automation_state_owner_unavailable), tone = Tone.WARN, dot = true, modifier = modifier)
        else -> Chip(stringResource(Res.string.automation_state_paused), tone = Tone.MUTED, dot = true, modifier = modifier)
    }
}

/** A confirmation before something that cannot be undone or that spends quota. */
@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, kind: ButtonKind = ButtonKind.PRIMARY, tag: String = "confirm") {
    val colors = TeamCode.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(Radii.card).background(colors.panel).border(1.dp, colors.line, Radii.card).padding(18.dp).testTag(tag),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = colors.muted, fontSize = 13.sp, lineHeight = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TcButton(stringResource(Res.string.action_cancel), onClick = onDismiss, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f))
                TcButton(confirm, onClick = onConfirm, kind = kind, modifier = Modifier.weight(1f).testTag("$tag-yes"))
            }
        }
    }
}

/** The "N consecutive failures" line of a list row and of the page. */
@Composable
fun failuresLine(summary: AutomationSummary): String? = summary.consecutiveFailures.takeIf { it > 0 }?.let { stringResource(Res.string.automation_failures, it) }
