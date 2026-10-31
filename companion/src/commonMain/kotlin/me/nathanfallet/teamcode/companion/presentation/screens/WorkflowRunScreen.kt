package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import me.nathanfallet.teamcode.companion.domain.models.RunStatus
import me.nathanfallet.teamcode.companion.domain.models.RunVisit
import me.nathanfallet.teamcode.companion.domain.models.VisitStatus
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.NavBar
import me.nathanfallet.teamcode.companion.presentation.components.SectionTitle
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowRunUiState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowRunViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * One run: its status and failure, the visits as a timeline (step, kind, status, outcome, output, times, the session it
 * ran in, the wait it is blocked on with "Waiting for you" when it is the member's, the sub-workflow run), and Cancel
 * for the starter or an administrator. It reads again at each change of the run the feed announces.
 */
@Composable
fun WorkflowRunScreen(
    viewModel: WorkflowRunViewModel,
    onBack: (() -> Unit)?,
    onOpenSession: (String) -> Unit,
    onOpenRun: (String) -> Unit,
    onOpenWaits: () -> Unit,
    backLabel: String = stringResource(Res.string.workflows_title),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val now = rememberNow()
    val colors = TeamCode.colors
    var confirmCancel by remember { mutableStateOf(false) }
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    val run = state.run
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, run?.workflowName ?: stringResource(Res.string.workflow_run_title)) {
            TcIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.workflow_run_refresh), onClick = viewModel::reload, enabled = !state.loading, modifier = Modifier.testTag("workflow-run-refresh"))
        }
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp).testTag("workflow-run-page"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("workflow-run-error"), action = {
                    TcButton(stringResource(Res.string.action_retry), onClick = viewModel::reload, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("workflow-run-retry"))
                })
            }
            state.actionError?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("workflow-run-action-error")) }
            if (state.missing) Banner(stringResource(Res.string.workflow_run_missing), Tone.WARN, Modifier.testTag("workflow-run-missing"))
            BusyRow(state.loading && run == null)
            if (run != null) {
                val today = utcEpochDay(now)
                TcCard(Modifier.testTag("workflow-run-overview")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Chip(workflowStatusWords(run.status, run.rawStatus), tone = workflowStatusTone(run.status), dot = run.status.active, modifier = Modifier.testTag("workflow-run-status"))
                        Text(stringResource(Res.string.workflow_run_of_version, run.workflowName, run.workflowVersion), style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(stringResource(Res.string.workflow_run_started, formatEventTime(run.createdAt, today)), style = MetaStyle, color = colors.muted)
                    Text(
                        run.finishedAt?.let { stringResource(Res.string.workflow_run_ended, formatEventTime(it, today), durationWords(run.durationMillis(now))) }
                            ?: stringResource(Res.string.workflow_run_running_for, durationWords(run.durationMillis(now))),
                        style = MetaStyle, color = colors.muted, modifier = Modifier.testTag("workflow-run-duration"),
                    )
                    if (state.canCancel) TcButton(stringResource(Res.string.workflow_cancel), onClick = { confirmCancel = true }, kind = ButtonKind.DANGER, enabled = !state.cancelling, modifier = Modifier.testTag("workflow-run-cancel"))
                }
                if (run.status == RunStatus.FAILED) Banner(failureWords(run.failure), Tone.DANGER, Modifier.testTag("workflow-run-failure"))
                if (run.inputs.isNotEmpty()) {
                    SectionTitle(stringResource(Res.string.workflow_run_inputs))
                    TcCard { run.inputs.forEach { (id, value) -> Text("$id: $value", style = MonospaceStyle, color = colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis) } }
                }
                SectionTitle(stringResource(Res.string.workflow_run_timeline))
                if (run.visits.isEmpty()) Text(stringResource(Res.string.workflow_run_no_visits), color = colors.muted, fontSize = 14.sp)
                run.visits.forEach { visit -> VisitCard(visit, state, today, onOpenSession, onOpenRun, onOpenWaits) }
                run.output?.takeIf { run.status == RunStatus.SUCCEEDED }?.let {
                    SectionTitle(stringResource(Res.string.workflow_run_result))
                    TcCard(Modifier.testTag("workflow-run-result")) { SelectionContainer { Text(it, color = colors.text, fontSize = 14.sp) } }
                }
            }
        }
    }
    if (confirmCancel) ConfirmDialog(stringResource(Res.string.workflow_cancel_title), stringResource(Res.string.workflow_cancel_body), stringResource(Res.string.workflow_cancel),
        onConfirm = { confirmCancel = false; viewModel.cancel() }, onDismiss = { confirmCancel = false }, kind = ButtonKind.DANGER, tag = "workflow-cancel-confirm")
}

@Composable
private fun VisitCard(visit: RunVisit, state: WorkflowRunUiState, today: Long, onOpenSession: (String) -> Unit, onOpenRun: (String) -> Unit, onOpenWaits: () -> Unit) {
    val colors = TeamCode.colors
    TcCard(Modifier.testTag("workflow-visit-${visit.seq}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(visit.title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Chip(visitStatusWords(visit.status), tone = visitStatusTone(visit.status), dot = visit.status == VisitStatus.ACTIVE, modifier = Modifier.testTag("workflow-visit-status-${visit.seq}"))
        }
        Text(stepKindWords(visit.kind), style = MetaStyle, color = colors.muted)
        visit.outcome?.let { Text(stringResource(Res.string.workflow_visit_outcome, it), color = colors.text, fontSize = 13.sp, modifier = Modifier.testTag("workflow-visit-outcome-${visit.seq}")) }
        visit.output?.takeIf { it.isNotBlank() }?.let {
            Text(stringResource(Res.string.workflow_visit_output), style = MetaStyle, color = colors.muted)
            SelectionContainer { Text(it, color = colors.text, fontSize = 13.sp, maxLines = 20, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("workflow-visit-output-${visit.seq}")) }
        }
        Text(stringResource(Res.string.workflow_visit_started, formatEventTime(visit.startedAt, today)), style = MetaStyle, color = colors.muted)
        visit.finishedAt?.let { Text(stringResource(Res.string.workflow_visit_finished, formatEventTime(it, today)), style = MetaStyle, color = colors.muted) }
        if (visit.status == VisitStatus.ACTIVE && visit.waitId != null) {
            if (state.waitsForMe(visit.waitId)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(Res.string.workflow_waiting_for_you), tone = Tone.ACCENT, dot = true, modifier = Modifier.testTag("workflow-visit-mine-${visit.seq}"))
                TcButton(stringResource(Res.string.workflow_open_wait), onClick = onOpenWaits, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("workflow-visit-open-wait-${visit.seq}"))
            } else Text(stringResource(Res.string.workflow_waiting_for_other), style = MetaStyle, color = colors.muted, modifier = Modifier.testTag("workflow-visit-other-${visit.seq}"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            visit.sessionId?.let { TcButton(stringResource(Res.string.workflow_open_session), onClick = { onOpenSession(it) }, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("workflow-visit-session-${visit.seq}")) }
            visit.childRunId?.let { TcButton(stringResource(Res.string.workflow_open_run), onClick = { onOpenRun(it) }, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("workflow-visit-child-${visit.seq}")) }
        }
    }
}
