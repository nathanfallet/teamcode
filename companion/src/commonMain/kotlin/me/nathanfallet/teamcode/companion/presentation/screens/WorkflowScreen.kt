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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import me.nathanfallet.teamcode.companion.domain.models.StepKind
import me.nathanfallet.teamcode.companion.domain.models.Workflow
import me.nathanfallet.teamcode.companion.domain.models.WorkflowStep
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
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.stringResource

/** One saved workflow, read-only: its description, inputs and steps with their transitions as a list, and Start. */
@Composable
fun WorkflowScreen(viewModel: WorkflowViewModel, onBack: (() -> Unit)?, onStart: (Workflow) -> Unit, backLabel: String = stringResource(Res.string.workflows_title)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TeamCode.colors
    val workflow = state.workflow
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, workflow?.name ?: stringResource(Res.string.workflow_title)) {
            TcIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.workflows_refresh), onClick = viewModel::reload, enabled = !state.loading, modifier = Modifier.testTag("workflow-refresh"))
        }
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp).testTag("workflow-page"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("workflow-error"), action = {
                    TcButton(stringResource(Res.string.action_retry), onClick = viewModel::reload, kind = ButtonKind.SECONDARY, small = true)
                })
            }
            if (state.missing) Banner(stringResource(Res.string.workflow_start_missing), Tone.WARN, Modifier.testTag("workflow-missing"))
            BusyRow(state.loading && workflow == null)
            if (workflow != null) {
                TcCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Chip(stringResource(Res.string.workflow_version, workflow.version), tone = Tone.MUTED)
                    }
                    workflow.description?.let { Text(it, color = colors.text, fontSize = 14.sp) }
                    Text(stringResource(if (workflow.sessionMode == "handoff") Res.string.workflow_session_handoff else Res.string.workflow_session_same), style = MetaStyle, color = colors.muted)
                    TcButton(stringResource(Res.string.workflow_start), onClick = { onStart(workflow) }, modifier = Modifier.testTag("workflow-start"))
                }
                Text(stringResource(Res.string.workflows_edit_note), style = MetaStyle, color = colors.muted)
                SectionTitle(stringResource(Res.string.workflow_inputs_title))
                if (workflow.inputs.isEmpty()) Text(stringResource(Res.string.workflow_inputs_none), color = colors.muted, fontSize = 14.sp)
                else TcCard(Modifier.testTag("workflow-inputs")) {
                    workflow.inputs.forEach { input ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(input.label, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(
                                    stringResource(if (input.required) Res.string.workflow_input_required else Res.string.workflow_input_optional),
                                    stringResource(Res.string.workflow_input_max, input.maxLength),
                                    input.default?.takeIf { it.isNotEmpty() }?.let { stringResource(Res.string.workflow_input_default, it) },
                                ).joinToString(" · "),
                                style = MetaStyle, color = colors.muted,
                            )
                        }
                    }
                }
                SectionTitle(stringResource(Res.string.workflow_steps_title))
                workflow.steps.forEach { step -> StepCard(step, workflow) }
            }
        }
    }
}

@Composable
private fun StepCard(step: WorkflowStep, workflow: Workflow) {
    val colors = TeamCode.colors
    TcCard(Modifier.testTag("workflow-step-${step.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(step.title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Chip(stepKindWords(step.kind, step.rawKind), tone = Tone.MUTED)
        }
        if (step.id == workflow.start) Text(stringResource(Res.string.workflow_step_first), style = MetaStyle, color = colors.accentText)
        step.instruction?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.muted, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        step.deliverable?.let { deliverable ->
            val what = when (deliverable.type) {
                "artifact" -> stringResource(Res.string.workflow_step_deliverable_artifact)
                "url" -> stringResource(Res.string.workflow_step_deliverable_url)
                "text" -> stringResource(Res.string.workflow_step_deliverable_text)
                "json" -> stringResource(Res.string.workflow_step_deliverable_json)
                else -> deliverable.type
            }
            Text(deliverable.domain?.let { stringResource(Res.string.workflow_step_delivers_url, it) } ?: stringResource(Res.string.workflow_step_delivers, what), style = MetaStyle, color = colors.muted)
        }
        if (step.kind == StepKind.HUMAN) Text(stringResource(if (step.assignee == "owner" || step.assignee == null) Res.string.workflow_step_assignee_owner else Res.string.workflow_step_assignee_member), style = MetaStyle, color = colors.muted)
        step.calls?.let { id -> Text(stringResource(Res.string.workflow_step_calls, id), style = MetaStyle, color = colors.muted) }
        step.transitions.forEach { transition ->
            val target = transition.to?.let { id -> workflow.step(id)?.title ?: id } ?: stringResource(Res.string.workflow_step_ends)
            val on = step.actions.firstOrNull { it.id == transition.on }?.label ?: transition.on
            Text(stringResource(Res.string.workflow_step_then, on, target), color = colors.text, fontSize = 13.sp, modifier = Modifier.testTag("workflow-transition-${step.id}-${transition.on}"))
        }
        Text(stringResource(Res.string.workflow_step_max_visits, step.maxVisits), style = MetaStyle, color = colors.muted)
    }
}
