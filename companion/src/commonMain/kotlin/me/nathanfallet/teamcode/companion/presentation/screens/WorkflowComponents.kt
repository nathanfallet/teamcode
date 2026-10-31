package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.nathanfallet.teamcode.companion.domain.models.RunStatus
import me.nathanfallet.teamcode.companion.domain.models.StepKind
import me.nathanfallet.teamcode.companion.domain.models.VisitStatus
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.components.handCursor
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.stringResource

/** A run's status in words; a status a newer server adds is shown as the server sent it. */
@Composable
fun workflowStatusWords(status: RunStatus, raw: String): String = when (status) {
    RunStatus.RUNNING -> stringResource(Res.string.workflow_status_running)
    RunStatus.WAITING -> stringResource(Res.string.workflow_status_waiting)
    RunStatus.SUCCEEDED -> stringResource(Res.string.workflow_status_succeeded)
    RunStatus.FAILED -> stringResource(Res.string.workflow_status_failed)
    RunStatus.CANCELLED -> stringResource(Res.string.workflow_status_cancelled)
    RunStatus.UNKNOWN -> raw
}

fun workflowStatusTone(status: RunStatus): Tone = when (status) {
    RunStatus.SUCCEEDED -> Tone.ACCENT
    RunStatus.RUNNING, RunStatus.WAITING -> Tone.WARN
    RunStatus.FAILED -> Tone.DANGER
    RunStatus.CANCELLED, RunStatus.UNKNOWN -> Tone.MUTED
}

@Composable
fun visitStatusWords(status: VisitStatus): String = when (status) {
    VisitStatus.ACTIVE -> stringResource(Res.string.workflow_visit_status_active)
    VisitStatus.DONE -> stringResource(Res.string.workflow_visit_status_done)
    VisitStatus.FAILED -> stringResource(Res.string.workflow_visit_status_failed)
    VisitStatus.CANCELLED -> stringResource(Res.string.workflow_visit_status_cancelled)
    VisitStatus.UNKNOWN -> ""
}

fun visitStatusTone(status: VisitStatus): Tone = when (status) {
    VisitStatus.DONE -> Tone.ACCENT
    VisitStatus.ACTIVE -> Tone.WARN
    VisitStatus.FAILED -> Tone.DANGER
    VisitStatus.CANCELLED, VisitStatus.UNKNOWN -> Tone.MUTED
}

/** What a step is, in words; a kind a newer server adds is shown as the server sent it ([raw]). */
@Composable
fun stepKindWords(kind: StepKind, raw: String = ""): String = when (kind) {
    StepKind.AGENT -> stringResource(Res.string.workflow_kind_agent)
    StepKind.HUMAN -> stringResource(Res.string.workflow_kind_human)
    StepKind.WORKFLOW -> stringResource(Res.string.workflow_kind_workflow)
    StepKind.UNKNOWN -> raw
}

/** How long, in the two largest units that matter: `42 s`, `5 min`, `3 h 10 min`, `2 d 4 h`. */
@Composable
fun durationWords(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> stringResource(Res.string.workflow_duration_seconds, seconds.toInt())
        seconds < 3600 -> stringResource(Res.string.workflow_duration_minutes, (seconds / 60).toInt())
        seconds < 86_400 -> stringResource(Res.string.workflow_duration_hours, (seconds / 3600).toInt(), (seconds % 3600 / 60).toInt())
        else -> stringResource(Res.string.workflow_duration_days, (seconds / 86_400).toInt(), (seconds % 86_400 / 3600).toInt())
    }
}

/** A failure code (`session_failed`, `max_visits:review`...) in words; a code a newer server adds is shown as it is. */
@Composable
fun failureWords(code: String?): String {
    if (code.isNullOrBlank()) return stringResource(Res.string.workflow_failure_unknown)
    val base = code.substringBefore(':')
    val detail = code.substringAfter(':', "")
    return when (base) {
        "session_failed" -> stringResource(Res.string.workflow_failure_session_failed)
        "session_deleted" -> stringResource(Res.string.workflow_failure_session_deleted)
        "session_missing" -> stringResource(Res.string.workflow_failure_session_missing)
        "ended_without_delivery" -> stringResource(Res.string.workflow_failure_ended_without_delivery)
        "profile_missing" -> stringResource(Res.string.workflow_failure_profile_missing)
        "starter_unavailable" -> stringResource(Res.string.workflow_failure_starter_unavailable)
        "wait_cancelled" -> stringResource(Res.string.workflow_failure_wait_cancelled)
        "wait_missing" -> stringResource(Res.string.workflow_failure_wait_missing)
        "max_visits" -> stringResource(Res.string.workflow_failure_max_visits, detail)
        "max_total_visits" -> stringResource(Res.string.workflow_failure_max_total_visits)
        "max_depth" -> stringResource(Res.string.workflow_failure_max_depth)
        "workflow_missing", "sub_workflow_missing" -> stringResource(Res.string.workflow_failure_workflow_missing)
        "invalid_arguments" -> stringResource(Res.string.workflow_failure_invalid_arguments)
        "sub_workflow_failed" -> stringResource(Res.string.workflow_failure_sub_workflow_failed)
        "sub_workflow_cancelled" -> stringResource(Res.string.workflow_failure_sub_workflow_cancelled)
        "expired" -> stringResource(Res.string.workflow_failure_expired)
        "no_transition" -> stringResource(Res.string.workflow_failure_no_transition)
        else -> stringResource(Res.string.workflow_failure, code)
    }
}

/** A row of filter pills (one selected), scrolling sideways when the screen is narrow. */
@Composable
fun <T> FilterChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, tag: (T) -> String? = { null }) {
    val colors = TeamCode.colors
    Row(modifier.horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) {
            val on = value == selected
            Box(
                Modifier
                    .heightIn(min = 34.dp)
                    .clip(Radii.pill)
                    .background(if (on) colors.panel else colors.panelAlt)
                    .border(1.dp, if (on) colors.accentText else colors.line, Radii.pill)
                    .handCursor()
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(value) })
                    .then(tag(value)?.let { Modifier.testTag(it) } ?: Modifier)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) colors.text else colors.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}
