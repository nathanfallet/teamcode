package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.FlowArrow
import me.nathanfallet.teamcode.companion.domain.models.RunFilter
import me.nathanfallet.teamcode.companion.domain.models.RunScope
import me.nathanfallet.teamcode.companion.domain.models.RunStatus
import me.nathanfallet.teamcode.companion.domain.models.Workflow
import me.nathanfallet.teamcode.companion.domain.models.WorkflowRun
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.EmptyState
import me.nathanfallet.teamcode.companion.presentation.components.LargeTitle
import me.nathanfallet.teamcode.companion.presentation.components.Segmented
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowRunsUiState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowEditorsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowRunsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.stringResource

private enum class WorkflowsPart { RUNS, DEFINITIONS }

/**
 * The Workflows destination: the member's runs (the team's for an administrator), newest first, with the status filter,
 * kept live by the app's one feed; and the saved workflows, read-only, each with a Start button. Editing definitions is
 * still done over the API.
 */
@Composable
fun WorkflowsScreen(
    runs: WorkflowRunsViewModel,
    definitions: WorkflowsViewModel,
    onOpenRun: (WorkflowRun) -> Unit,
    onOpenWorkflow: (Workflow) -> Unit,
    onStartWorkflow: (Workflow) -> Unit,
    onOpenWaits: () -> Unit,
    onCreateWorkflow: () -> Unit = {},
    onDuplicateWorkflow: (Workflow) -> Unit = {},
    editors: WorkflowEditorsViewModel? = null,
) {
    val colors = TeamCode.colors
    var part by rememberSaveable { mutableStateOf(WorkflowsPart.RUNS) }
    val runsState by runs.uiState.collectAsStateWithLifecycle()
    val definitionsState by definitions.uiState.collectAsStateWithLifecycle()
    // The runs follow the feed while the screen is on; it loads them when the feed opens (or at once without a feed).
    LifecycleStartEffect(runs) {
        runs.start()
        onStopOrDispose { runs.stop() }
    }
    // Definitions have no feed event: they are read again when the screen comes back (the member's own saves reload them at once).
    var firstStart by remember { mutableStateOf(true) }
    LifecycleStartEffect(definitions) {
        if (firstStart) firstStart = false else definitions.reload()
        onStopOrDispose { }
    }
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LargeTitle(stringResource(Res.string.workflows_title)) {
                TcIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.workflows_refresh),
                    onClick = { if (part == WorkflowsPart.RUNS) runs.reload() else definitions.reload() }, modifier = Modifier.testTag("workflows-refresh"))
            }
            Segmented(
                listOf(WorkflowsPart.RUNS to stringResource(Res.string.workflows_part_runs), WorkflowsPart.DEFINITIONS to stringResource(Res.string.workflows_part_definitions)),
                part, { part = it }, Modifier.fillMaxWidth(), tag = { "workflows-part-${it.name.lowercase()}" },
            )
            if (part == WorkflowsPart.RUNS) RunsHeader(runsState, runs)
            else if (definitionsState.canEdit) TcButton(stringResource(Res.string.wfe_new), onClick = onCreateWorkflow, kind = ButtonKind.SECONDARY, small = true, fill = false, icon = PhosphorIcons.Regular.Plus, modifier = Modifier.testTag("workflows-new"))
            else Text(stringResource(Res.string.workflows_edit_note), style = MetaStyle, color = colors.muted, modifier = Modifier.testTag("workflows-edit-note"))
        }
        if (part == WorkflowsPart.RUNS) RunsList(runsState, runs, onOpenRun, onOpenWaits) else DefinitionsList(definitionsState, definitions, onOpenWorkflow, onStartWorkflow, onDuplicateWorkflow, editors)
    }
}

@Composable
private fun RunsHeader(state: WorkflowRunsUiState, viewModel: WorkflowRunsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.administrator) Segmented(
            listOf(RunScope.MINE to stringResource(Res.string.workflows_mine), RunScope.TEAM to stringResource(Res.string.workflows_team)),
            state.scope, viewModel::setScope, Modifier.fillMaxWidth(), tag = { "workflows-scope-${it.name.lowercase()}" },
        )
        FilterChips(
            listOf(
                RunFilter.ALL to stringResource(Res.string.workflows_filter_all), RunFilter.RUNNING to stringResource(Res.string.workflows_filter_running),
                RunFilter.WAITING to stringResource(Res.string.workflows_filter_waiting), RunFilter.DONE to stringResource(Res.string.workflows_filter_done),
                RunFilter.FAILED to stringResource(Res.string.workflows_filter_failed),
            ),
            state.filter, viewModel::setFilter, tag = { "workflows-filter-${it.name.lowercase()}" },
        )
        if (state.reconnecting) Banner(stringResource(Res.string.workflows_reconnecting), Tone.WARN, Modifier.testTag("workflows-reconnecting"))
        state.error?.let { error ->
            Banner(error.words(), Tone.DANGER, Modifier.testTag("workflows-error"), action = {
                TcButton(stringResource(Res.string.action_retry), onClick = viewModel::reload, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("workflows-retry"))
            })
        }
    }
}

@Composable
private fun RunsList(state: WorkflowRunsUiState, viewModel: WorkflowRunsViewModel, onOpen: (WorkflowRun) -> Unit, onOpenWaits: () -> Unit) {
    val now = rememberNow()
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
    LaunchedEffect(nearEnd, state.nextBefore) { if (nearEnd) viewModel.loadMore() }
    LazyColumn(
        Modifier.widthIn(max = ContentWidth).fillMaxWidth().testTag("workflow-runs"),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.runs.orEmpty(), key = { it.id }) { run ->
            RunCard(run, state, now, team = state.scope == RunScope.TEAM, onOpen = { onOpen(run) }, onOpenWaits = onOpenWaits)
        }
        item(key = "end") {
            BusyRow(state.loading || state.loadingMore)
            if (state.empty && state.error == null) EmptyState(
                PhosphorIcons.Regular.FlowArrow, stringResource(Res.string.workflows_runs_empty_title),
                stringResource(if (state.scope == RunScope.TEAM) Res.string.workflows_runs_empty_team_body else Res.string.workflows_runs_empty_body), Modifier.testTag("workflow-runs-empty"),
            )
        }
    }
}

@Composable
private fun RunCard(run: WorkflowRun, state: WorkflowRunsUiState, now: Long, team: Boolean, onOpen: () -> Unit, onOpenWaits: () -> Unit) {
    val colors = TeamCode.colors
    val today = utcEpochDay(now)
    TcCard(Modifier.testTag("workflow-run-${run.id}"), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(run.workflowName, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Chip(workflowStatusWords(run.status, run.rawStatus), tone = workflowStatusTone(run.status), dot = run.status.active, modifier = Modifier.testTag("workflow-run-status-${run.id}"))
        }
        state.stepOf(run)?.let { step ->
            Text(stringResource(Res.string.workflow_run_step, step.title, stepKindWords(step.kind)), color = colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("workflow-run-step-${run.id}"))
        }
        if (state.waitsForMe(run)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(Res.string.workflow_waiting_for_you), tone = Tone.ACCENT, dot = true, modifier = Modifier.testTag("workflow-run-mine-${run.id}"))
            TcButton(stringResource(Res.string.workflow_open_wait), onClick = onOpenWaits, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("workflow-run-open-wait-${run.id}"))
        }
        val started = stringResource(Res.string.workflow_run_started, formatEventTime(run.createdAt, today))
        val finished = run.finishedAt
        Text(
            if (finished != null) stringResource(Res.string.workflow_run_ended, formatEventTime(finished, today), durationWords(run.durationMillis(now)))
            else "$started · " + stringResource(Res.string.workflow_run_running_for, durationWords(run.durationMillis(now))),
            style = MetaStyle, color = colors.muted, modifier = Modifier.testTag("workflow-run-time-${run.id}"),
        )
        if (run.status == RunStatus.FAILED) Text(failureWords(run.failure), color = colors.danger, fontSize = 13.sp, modifier = Modifier.testTag("workflow-run-failure-${run.id}"))
        if (team) Text(run.starterId, style = MetaStyle, color = colors.muted)
    }
}

@Composable
private fun DefinitionsList(
    state: me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowsUiState,
    viewModel: WorkflowsViewModel,
    onOpen: (Workflow) -> Unit,
    onStart: (Workflow) -> Unit,
    onDuplicate: (Workflow) -> Unit,
    editors: WorkflowEditorsViewModel?,
) {
    val colors = TeamCode.colors
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
    LaunchedEffect(nearEnd, state.nextAfter) { if (nearEnd) viewModel.loadMore() }
    LazyColumn(
        Modifier.widthIn(max = ContentWidth).fillMaxWidth().testTag("workflow-definitions"),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (editors != null) item(key = "editors") { WorkflowEditorsCard(editors) }
        item(key = "error") {
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("workflow-definitions-error"), action = {
                    TcButton(stringResource(Res.string.action_retry), onClick = viewModel::reload, kind = ButtonKind.SECONDARY, small = true)
                })
            }
        }
        items(state.workflows.orEmpty(), key = { it.id }) { workflow ->
            TcCard(Modifier.testTag("workflow-${workflow.id}"), onClick = { onOpen(workflow) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(workflow.name, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        workflow.description?.let { Text(it, color = colors.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Text(stringResource(Res.string.workflow_version, workflow.version), style = MetaStyle, color = colors.muted)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TcButton(stringResource(Res.string.workflow_start), onClick = { onStart(workflow) }, small = true, modifier = Modifier.testTag("workflow-start-${workflow.id}"))
                        if (state.canEdit) TcButton(stringResource(Res.string.wfe_duplicate), onClick = { onDuplicate(workflow) }, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("workflow-duplicate-${workflow.id}"))
                    }
                }
            }
        }
        item(key = "end") {
            BusyRow(state.loading || state.loadingMore)
            if (state.empty && state.error == null) EmptyState(PhosphorIcons.Regular.FlowArrow, stringResource(Res.string.workflows_empty_title), stringResource(Res.string.workflows_empty_body), Modifier.testTag("workflow-definitions-empty"))
        }
    }
}
