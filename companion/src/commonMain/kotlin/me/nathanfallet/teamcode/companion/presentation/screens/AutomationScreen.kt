package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.Copy
import com.adamglin.phosphoricons.regular.PencilSimple
import me.nathanfallet.teamcode.companion.domain.models.ActionKind
import me.nathanfallet.teamcode.companion.domain.models.Automation
import me.nathanfallet.teamcode.companion.domain.models.AutomationRun
import me.nathanfallet.teamcode.companion.domain.models.TriggerKind
import me.nathanfallet.teamcode.companion.domain.usecases.automations.SECRET_PLACEHOLDER
import me.nathanfallet.teamcode.companion.domain.usecases.automations.WebhookProof
import me.nathanfallet.teamcode.companion.domain.usecases.automations.WebhookVersion
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.NavBar
import me.nathanfallet.teamcode.companion.presentation.components.Segmented
import me.nathanfallet.teamcode.companion.presentation.components.SectionTitle
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationUiState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * One automation: its state and actions (arm or pause, test, edit, delete), its trigger (a webhook's URL with the proof
 * and the version chosen at the copy, the secret shown once, its rotation), its action, prompt and variables, and the
 * history of its runs with a link to each session. An administrator reading a member's automation sees it, pauses
 * it and deletes it; testing and changing it are the owner's.
 */
@Composable
fun AutomationScreen(
    viewModel: AutomationViewModel,
    onBack: (() -> Unit)?,
    onEdit: (Automation) -> Unit,
    onTest: (Automation) -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleted: () -> Unit,
    /** Opens the workflow run a run of a workflow action started. */
    onOpenWorkflowRun: (String) -> Unit = {},
    backLabel: String = stringResource(Res.string.automations_title),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val now = rememberNow()
    val colors = TeamCode.colors
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }
    // Back on the page (from the form, the test, a session): what was saved or run meanwhile shows.
    var started by remember { mutableStateOf(false) }
    LifecycleStartEffect(viewModel) {
        if (started) viewModel.reload()
        started = true
        onStopOrDispose { }
    }
    val automation = state.automation
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, automation?.name ?: stringResource(Res.string.automation_title)) {
            TcIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.automations_refresh), onClick = viewModel::reload, enabled = !state.loading, modifier = Modifier.testTag("automation-refresh"))
            if (automation != null && state.canEdit) TcIconButton(PhosphorIcons.Regular.PencilSimple, stringResource(Res.string.automation_edit), onClick = { onEdit(automation) }, modifier = Modifier.testTag("automation-edit"))
        }
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp).testTag("automation-page"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("automation-error"), action = {
                    TcButton(stringResource(Res.string.action_retry), onClick = viewModel::reload, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("automation-retry"))
                })
            }
            state.actionError?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("automation-action-error")) }
            BusyRow(state.loading && automation == null)
            if (automation != null) {
                state.secret?.let { SecretBanner(it, onStored = viewModel::secretStored) }
                if (automation.disabledReason == "owner_unavailable" && !automation.enabled) Banner(stringResource(Res.string.automation_owner_unavailable_note), Tone.WARN, Modifier.testTag("automation-owner-unavailable"))
                if (automation.consecutiveFailures > 0) Banner(stringResource(Res.string.automation_failures, automation.consecutiveFailures), Tone.DANGER, Modifier.testTag("automation-failures"))
                if (state.readOnly) Banner(stringResource(Res.string.automation_read_only, automation.ownerName), Tone.NEUTRAL, Modifier.testTag("automation-read-only"), icon = null)
                Overview(automation, state, now, onToggle = viewModel::toggle, onTest = { onTest(automation) })
                TriggerCard(automation, state, now, viewModel, onRotate = { confirmRotate = true })
                ActionCard(automation, state)
                if (automation.definition.action.kind == ActionKind.WORKFLOW) InputsCard(automation) else PromptCard(automation)
                if (automation.definition.variables.isNotEmpty()) VariablesCard(automation)
                History(state, viewModel, onOpenSession, onOpenWorkflowRun)
                if (state.canDelete) TcButton(stringResource(Res.string.automation_delete), onClick = { confirmDelete = true }, kind = ButtonKind.DANGER, enabled = !state.busy, modifier = Modifier.testTag("automation-delete"))
            }
        }
    }
    if (confirmDelete) ConfirmDialog(stringResource(Res.string.automation_delete_title), stringResource(Res.string.automation_delete_body), stringResource(Res.string.automation_delete),
        onConfirm = { confirmDelete = false; viewModel.remove() }, onDismiss = { confirmDelete = false }, kind = ButtonKind.DANGER, tag = "automation-delete-confirm")
    if (confirmRotate) ConfirmDialog(stringResource(Res.string.automation_rotate_title), stringResource(Res.string.automation_rotate_body), stringResource(Res.string.automation_rotate),
        onConfirm = { confirmRotate = false; viewModel.rotateSecret() }, onDismiss = { confirmRotate = false }, tag = "automation-rotate-confirm")
}

@Suppress("DEPRECATION") // LocalClipboardManager: plain text is all the secret needs
@Composable
private fun SecretBanner(secret: String, onStored: () -> Unit) {
    val colors = TeamCode.colors
    val clipboard = LocalClipboardManager.current
    TcCard(Modifier.testTag("automation-secret"), borderColor = colors.warn) {
        Text(stringResource(Res.string.automation_secret_title), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(stringResource(Res.string.automation_secret_body), color = colors.muted, fontSize = 13.sp, lineHeight = 18.sp)
        SelectionContainer { Text(secret, style = MonospaceStyle, color = colors.text, modifier = Modifier.testTag("automation-secret-value")) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TcButton(stringResource(Res.string.automation_secret_copy), onClick = { clipboard.setText(AnnotatedString(secret)) }, kind = ButtonKind.SECONDARY, small = true, icon = PhosphorIcons.Regular.Copy, modifier = Modifier.testTag("automation-secret-copy"))
            TcButton(stringResource(Res.string.automation_secret_stored), onClick = onStored, small = true, modifier = Modifier.testTag("automation-secret-stored"))
        }
    }
}

@Composable
private fun Overview(automation: Automation, state: AutomationUiState, now: Long, onToggle: () -> Unit, onTest: () -> Unit) {
    val colors = TeamCode.colors
    TcCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AutomationStateChip(automation.enabled, automation.disabledReason, Modifier.testTag("automation-state"))
            Text(stringResource(Res.string.automation_version, automation.version), style = MetaStyle, color = colors.muted)
        }
        automation.nextRunAt?.takeIf { automation.enabled }?.let {
            Text(stringResource(Res.string.automation_next_run, untilTime(it, now), formatUtc(it)), style = MetaStyle, color = colors.muted, modifier = Modifier.testTag("automation-next"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.canEnable) TcButton(stringResource(Res.string.automation_enable), onClick = onToggle, enabled = !state.busy, modifier = Modifier.weight(1f).testTag("automation-enable"))
            if (state.canPause) TcButton(stringResource(Res.string.automation_pause), onClick = onToggle, kind = ButtonKind.SECONDARY, enabled = !state.busy, modifier = Modifier.weight(1f).testTag("automation-pause"))
            TcButton(stringResource(if (state.canTest) Res.string.automation_test else Res.string.automation_preview), onClick = onTest, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f).testTag("automation-test"))
        }
    }
}

@Suppress("DEPRECATION") // LocalClipboardManager: plain text is all the URL needs
@Composable
private fun TriggerCard(automation: Automation, state: AutomationUiState, now: Long, viewModel: AutomationViewModel, onRotate: () -> Unit) {
    val colors = TeamCode.colors
    val clipboard = LocalClipboardManager.current
    SectionTitle(stringResource(Res.string.automation_section_trigger))
    TcCard {
        when (automation.trigger.kind) {
            TriggerKind.CRON -> {
                Text(triggerLine(automation.trigger), style = MonospaceStyle, color = colors.text, modifier = Modifier.testTag("automation-cron"))
                automation.trigger.catchup?.let { Text(stringResource(if (it == "skip") Res.string.automation_catchup_skip else Res.string.automation_catchup_once), color = colors.muted, fontSize = 13.sp) }
                if (automation.upcomingRuns.isNotEmpty()) {
                    Text(stringResource(Res.string.automation_upcoming), color = colors.muted, fontSize = 12.5.sp)
                    automation.upcomingRuns.forEach { Text(formatUtc(it), style = MetaStyle, color = colors.text) }
                }
            }
            TriggerKind.WEBHOOK -> {
                Text(stringResource(Res.string.automation_webhook_copy_as), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Segmented(
                    listOf(WebhookProof.SIGNATURE to stringResource(Res.string.automation_proof_signature), WebhookProof.SECRET_URL to stringResource(Res.string.automation_proof_secret_url)),
                    selected = state.proof, onSelect = viewModel::setProof, modifier = Modifier.fillMaxWidth(), tag = { "automation-proof-${it.name.lowercase()}" },
                )
                Segmented(
                    listOf(WebhookVersion.LATEST to stringResource(Res.string.automation_version_latest), WebhookVersion.PINNED to stringResource(Res.string.automation_version_pinned, automation.version)),
                    selected = state.version, onSelect = viewModel::setVersion, modifier = Modifier.fillMaxWidth(), tag = { "automation-version-${it.name.lowercase()}" },
                )
                state.link?.let { link ->
                    SelectionContainer { Text(link.url, style = MonospaceStyle, color = colors.text, modifier = Modifier.testTag("automation-url")) }
                    Text(
                        stringResource(if (state.proof == WebhookProof.SIGNATURE) Res.string.automation_proof_signature_hint else if (link.hasSecret) Res.string.automation_proof_secret_url_hint else Res.string.automation_proof_secret_url_placeholder_hint, SECRET_PLACEHOLDER),
                        color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.testTag("automation-proof-hint"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TcButton(stringResource(Res.string.automation_copy_url), onClick = { clipboard.setText(AnnotatedString(link.url)) }, kind = ButtonKind.SECONDARY, small = true, icon = PhosphorIcons.Regular.Copy, modifier = Modifier.testTag("automation-copy-url"))
                        if (state.canRotate) TcButton(stringResource(Res.string.automation_rotate), onClick = onRotate, kind = ButtonKind.SECONDARY, small = true, enabled = !state.busy, modifier = Modifier.testTag("automation-rotate"))
                    }
                }
                automation.webhook?.previousSecretValidUntil?.takeIf { it > now }?.let {
                    Text(stringResource(Res.string.automation_previous_secret, formatUtc(it)), color = colors.warn, fontSize = 12.5.sp, modifier = Modifier.testTag("automation-previous-secret"))
                }
            }
            TriggerKind.UNKNOWN -> Text(automation.trigger.rawKind, color = colors.muted)
        }
    }
}

@Composable
private fun ActionCard(automation: Automation, state: AutomationUiState) {
    val colors = TeamCode.colors
    val action = automation.definition.action
    SectionTitle(stringResource(Res.string.automation_section_action))
    TcCard {
        val line = if (action.kind == ActionKind.WORKFLOW) listOfNotNull(
            stringResource(Res.string.automation_action_workflow_line, state.workflowName ?: action.workflowId.orEmpty()), state.profileName?.let { stringResource(Res.string.automation_action_profile, it) },
        ) else listOfNotNull(
            stringResource(Res.string.automation_action_cloud), state.profileName?.let { stringResource(Res.string.automation_action_profile, it) },
            action.model, action.effort, stringResource(Res.string.automation_action_max_runtime, action.maxRuntimeMinutes),
        )
        Text(line.joinToString(" · "), color = colors.text, fontSize = 14.sp, modifier = Modifier.testTag("automation-action"))
        Text(stringResource(Res.string.automation_overlap_line, stringResource(overlapWords(automation.definition.overlap)), automation.definition.maxRunsPerDay), color = colors.muted, fontSize = 13.sp)
    }
}

@Composable
private fun PromptCard(automation: Automation) {
    SectionTitle(stringResource(Res.string.automation_section_prompt))
    TcCard {
        SelectionContainer { Text(automation.definition.prompt, style = MonospaceStyle, color = TeamCode.colors.text, modifier = Modifier.testTag("automation-prompt")) }
    }
}

/** The text of each input of a workflow action (a template: `{{name}}` is replaced at each run). */
@Composable
private fun InputsCard(automation: Automation) {
    val colors = TeamCode.colors
    SectionTitle(stringResource(Res.string.automation_inputs_title))
    TcCard(Modifier.testTag("automation-inputs")) {
        val inputs = automation.definition.action.inputs
        if (inputs.isEmpty()) Text(stringResource(Res.string.automation_inputs_none), color = colors.muted, fontSize = 13.sp)
        inputs.forEach { (id, text) ->
            Text(id, style = MetaStyle, color = colors.muted)
            SelectionContainer { Text(text, style = MonospaceStyle, color = colors.text, modifier = Modifier.testTag("automation-input-$id")) }
        }
    }
}

@Composable
private fun VariablesCard(automation: Automation) {
    val colors = TeamCode.colors
    SectionTitle(stringResource(Res.string.automation_section_variables))
    TcCard(Modifier.testTag("automation-variables")) {
        automation.definition.variables.forEach { variable ->
            Text(listOfNotNull(variable.name, variable.path, variable.type + if (variable.sensitive) " · ${stringResource(Res.string.automation_variable_sensitive)}" else "").joinToString(" · "),
                style = MonospaceStyle, color = colors.text)
        }
    }
}

internal fun overlapWords(overlap: String) = when (overlap) {
    "queue" -> Res.string.automation_overlap_queue
    "allow" -> Res.string.automation_overlap_allow
    else -> Res.string.automation_overlap_skip
}

@Composable
private fun History(state: AutomationUiState, viewModel: AutomationViewModel, onOpenSession: (String) -> Unit, onOpenWorkflowRun: (String) -> Unit) {
    val colors = TeamCode.colors
    SectionTitle(stringResource(Res.string.automation_section_history))
    Segmented(
        listOf(false to stringResource(Res.string.automation_history_all), true to stringResource(Res.string.automation_history_no_tests)),
        selected = state.hideTests, onSelect = viewModel::setHideTests, modifier = Modifier.fillMaxWidth(), tag = { if (it) "automation-history-no-tests" else "automation-history-all" },
    )
    state.runsError?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("automation-runs-error")) }
    Column(Modifier.testTag("automation-runs"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.runs.forEach { run -> RunRow(run, canCancel = state.canDelete && !state.busy, onOpenSession = onOpenSession, onOpenWorkflowRun = onOpenWorkflowRun, onCancel = { viewModel.cancelRun(run) }) }
    }
    BusyRow(state.runsLoading)
    if (!state.runsLoading && state.runsError == null && state.runs.isEmpty()) Text(stringResource(Res.string.automation_history_empty), color = colors.muted, fontSize = 14.sp, modifier = Modifier.testTag("automation-runs-empty"))
    if (state.runsNext != null && !state.runsLoading) TcButton(stringResource(Res.string.artifacts_load_more), onClick = viewModel::loadMoreRuns, kind = ButtonKind.GHOST, modifier = Modifier.testTag("automation-runs-more"))
}

/** One run: when, from what, its status and reason, its session, and the cancel of a waiting or working one. */
@Composable
private fun RunRow(run: AutomationRun, canCancel: Boolean, onOpenSession: (String) -> Unit, onOpenWorkflowRun: (String) -> Unit, onCancel: () -> Unit) {
    val colors = TeamCode.colors
    TcCard(Modifier.testTag("run-${run.id}"), padding = 10.dp, spacing = 5.dp) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(formatUtc(run.scheduledFor ?: run.queuedAt), style = MetaStyle, color = colors.muted)
            Chip(runSourceWords(run.source), tone = if (run.source == me.nathanfallet.teamcode.companion.domain.models.RunSource.TEST) Tone.WARN else Tone.MUTED, modifier = Modifier.testTag("run-source-${run.id}"))
            Chip(runStatusWords(run.status), tone = runStatusTone(run.status), modifier = Modifier.testTag("run-status-${run.id}"))
        }
        runReasonWords(run.reason)?.let { Text(it, color = colors.muted, fontSize = 13.sp, modifier = Modifier.testTag("run-reason-${run.id}")) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            run.sessionId?.let { TcButton(stringResource(Res.string.automation_open_session), onClick = { onOpenSession(it) }, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("run-session-${run.id}")) }
            run.workflowRunId?.let { TcButton(stringResource(Res.string.automation_open_workflow_run), onClick = { onOpenWorkflowRun(it) }, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("run-workflow-${run.id}")) }
            if (run.cancellable && canCancel) TcButton(stringResource(Res.string.automation_cancel_run), onClick = onCancel, kind = ButtonKind.DANGER, small = true, modifier = Modifier.testTag("run-cancel-${run.id}"))
        }
    }
}
