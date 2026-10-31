package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.CaretUp
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Trash
import me.nathanfallet.teamcode.companion.domain.models.DefinitionNode
import me.nathanfallet.teamcode.companion.domain.models.StepKind
import me.nathanfallet.teamcode.companion.domain.models.WorkflowInput
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.EdgeKind
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.FlowMaps
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.ProblemRule
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowEdits
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowProblem
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.NavBar
import me.nathanfallet.teamcode.companion.presentation.components.Segmented
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.components.dismissKeyboardOnTap
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowEditViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.stringResource

internal val flatKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

/**
 * The definition editor. Top to bottom: the name, description and session mode; the run inputs; the map of the flow (a list that
 * shows where each outcome goes, with the loops back); the steps, each a card that folds (kind, instruction with template hints,
 * deliverable, fields and actions of a person's step, the workflow a step calls, where each outcome goes, how often a loop may
 * come back); the limits; Save. Every rule the server checks is checked as the member types and shown against its step, and Save
 * stays off while any is left. Edits are kept on the device; a save over a newer version offers to reload or to keep the edits as a copy.
 */
@Composable
fun WorkflowEditScreen(viewModel: WorkflowEditViewModel, onBack: (() -> Unit)?, onSaved: (workflowId: String) -> Unit, backLabel: String = stringResource(Res.string.action_back)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TeamCode.colors
    val definition = state.definition
    LaunchedEffect(state.savedId) { state.savedId?.let(onSaved) }
    Column(Modifier.fillMaxSize().background(colors.background).imePadding().dismissKeyboardOnTap(), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, stringResource(if (state.editingId != null) Res.string.wfe_title_edit else Res.string.wfe_title_new))
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp).testTag("wfe-form"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BusyRow(state.loading)
            state.loadError?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("wfe-load-error"), action = {
                    TcButton(stringResource(Res.string.action_retry), onClick = viewModel::load, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("wfe-load-retry"))
                })
            }
            if (state.notAllowed) Banner(stringResource(Res.string.wfe_not_allowed), Tone.WARN, Modifier.testTag("wfe-not-allowed"))
            if (state.restored) Banner(stringResource(Res.string.wfe_restored), Tone.ACCENT, Modifier.testTag("wfe-restored"), action = {
                TcButton(stringResource(Res.string.wfe_discard), onClick = viewModel::discardDraft, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("wfe-discard"))
            })
            if (state.conflict) Column(Modifier.testTag("wfe-conflict"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Banner(stringResource(Res.string.wfe_conflict), Tone.DANGER)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TcButton(stringResource(Res.string.wfe_conflict_reload), onClick = viewModel::reloadAfterConflict, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f).testTag("wfe-conflict-reload"))
                    TcButton(stringResource(Res.string.wfe_conflict_copy), onClick = viewModel::keepAsCopy, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f).testTag("wfe-conflict-copy"))
                }
            }
            if (!state.loading && state.loadError == null && !state.notAllowed) {
                TcCard(Modifier.testTag("wfe-general"), spacing = 10.dp) {
                    TcTextField(definition.name, { v -> viewModel.edit { it.copy(name = v) } }, label = stringResource(Res.string.wfe_name), error = state.problemsOf(null).any { it.rule == ProblemRule.NAME }, tag = "wfe-name")
                    TcTextField(definition.description, { v -> viewModel.edit { it.copy(description = v) } }, label = stringResource(Res.string.wfe_description), singleLine = false, minLines = 2, tag = "wfe-description")
                    Text(stringResource(Res.string.wfe_session_mode), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Segmented(
                        listOf("same" to stringResource(Res.string.wfe_session_same), "handoff" to stringResource(Res.string.wfe_session_handoff)),
                        selected = definition.sessionMode, onSelect = { m -> viewModel.edit { it.copy(sessionMode = m) } }, modifier = Modifier.fillMaxWidth(), tag = { "wfe-session-$it" },
                    )
                    Text(stringResource(if (definition.sessionMode == "handoff") Res.string.wfe_session_handoff_hint else Res.string.wfe_session_same_hint), style = MetaStyle, color = colors.muted)
                    Problems(state.problemsOf(null), Modifier.testTag("wfe-general-problems"))
                }

                FoldSection("inputs", stringResource(Res.string.wfe_inputs), count = definition.inputs.size) {
                    Text(stringResource(Res.string.wfe_inputs_hint), style = MetaStyle, color = colors.muted)
                    definition.inputs.forEachIndexed { index, input -> InputEditor(index, input, viewModel) }
                    TcButton(stringResource(Res.string.wfe_input_add), onClick = { viewModel.edit { it.copy(inputs = it.inputs + WorkflowInput(WorkflowEdits.freshInputId(it), "", true, null, 4_096)) } },
                        kind = ButtonKind.SECONDARY, small = true, icon = PhosphorIcons.Regular.Plus, modifier = Modifier.testTag("wfe-input-add"))
                }

                FoldSection("map", stringResource(Res.string.wfe_map)) { FlowMapView(viewModel) }

                Text(stringResource(Res.string.wfe_steps), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
                definition.nodes.forEachIndexed { index, node ->
                    NodeEditor(node, index, definition.nodes.size, viewModel, state)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TcButton(stringResource(Res.string.wfe_add_agent), onClick = { viewModel.edit { WorkflowEdits.addNode(it, StepKind.AGENT) } }, kind = ButtonKind.SECONDARY, small = true, icon = PhosphorIcons.Regular.Plus, modifier = Modifier.testTag("wfe-add-agent"))
                    TcButton(stringResource(Res.string.wfe_add_human), onClick = { viewModel.edit { WorkflowEdits.addNode(it, StepKind.HUMAN) } }, kind = ButtonKind.SECONDARY, small = true, icon = PhosphorIcons.Regular.Plus, modifier = Modifier.testTag("wfe-add-human"))
                    TcButton(stringResource(Res.string.wfe_add_workflow), onClick = { viewModel.edit { WorkflowEdits.addNode(it, StepKind.WORKFLOW) } }, kind = ButtonKind.SECONDARY, small = true, icon = PhosphorIcons.Regular.Plus, modifier = Modifier.testTag("wfe-add-workflow"))
                }

                FoldSection("limits", stringResource(Res.string.wfe_limits), initiallyOpen = false) {
                    Text(stringResource(Res.string.wfe_limits_hint), style = MetaStyle, color = colors.muted)
                    NumberField(definition.limits.maxNodes, { v -> viewModel.edit { it.copy(limits = it.limits.copy(maxNodes = v ?: 0)) } }, stringResource(Res.string.wfe_limit_nodes), "wfe-limit-nodes")
                    NumberField(definition.limits.maxVisits, { v -> viewModel.edit { it.copy(limits = it.limits.copy(maxVisits = v ?: 0)) } }, stringResource(Res.string.wfe_limit_visits), "wfe-limit-visits")
                    NumberField(definition.limits.maxDepth, { v -> viewModel.edit { it.copy(limits = it.limits.copy(maxDepth = v ?: -1)) } }, stringResource(Res.string.wfe_limit_depth), "wfe-limit-depth", zeroIsEmpty = false)
                }
            }
            state.error?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("wfe-save-error")) }
        }
        if (!state.loading && state.loadError == null && !state.notAllowed) Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.problems.isNotEmpty()) Text(stringResource(Res.string.wfe_problems_count, state.problems.size), style = MetaStyle, color = colors.danger, modifier = Modifier.testTag("wfe-problems-count"))
            TcButton(stringResource(Res.string.wfe_save), onClick = viewModel::save, enabled = state.canSave, busy = state.saving, modifier = Modifier.testTag("wfe-save"))
        }
    }
}

/** A foldable group of fields: a header that opens and closes it. */
@Composable
internal fun FoldSection(id: String, title: String, initiallyOpen: Boolean = true, count: Int? = null, content: @Composable () -> Unit) {
    val colors = TeamCode.colors
    var open by rememberSaveable("wfe-section-$id") { mutableStateOf(initiallyOpen) }
    TcCard(Modifier.testTag("wfe-section-$id"), spacing = 10.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Button) { open = !open }.testTag("wfe-section-$id-header"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            count?.let { Chip(it.toString(), tone = Tone.MUTED) }
            Icon(if (open) PhosphorIcons.Regular.CaretDown else PhosphorIcons.Regular.CaretRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
        }
        if (open) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** A number typed in digits. [zeroIsEmpty]: 0 is shown as an empty field (a number not typed yet), else as 0. */
@Composable
internal fun NumberField(value: Int?, onChange: (Int?) -> Unit, label: String, tag: String, modifier: Modifier = Modifier, zeroIsEmpty: Boolean = true, error: Boolean = false) {
    TcTextField(
        if (value == null || value < 0 || (zeroIsEmpty && value == 0)) "" else value.toString(), { text -> onChange(text.filter(Char::isDigit).take(6).toIntOrNull()) },
        modifier, label = label, error = error, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), tag = tag,
    )
}

@Composable
private fun InputEditor(index: Int, input: WorkflowInput, viewModel: WorkflowEditViewModel) {
    val colors = TeamCode.colors
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val bad = state.problemsOf(null).any { it.rule in INPUT_RULES && it.args.firstOrNull() == input.id }
    fun change(new: WorkflowInput) = viewModel.edit { it.copy(inputs = it.inputs.mapIndexed { i, old -> if (i == index) new else old }) }
    Column(Modifier.fillMaxWidth().border(1.dp, if (bad) colors.danger else colors.line, Radii.control).padding(10.dp).testTag("wfe-input-$index"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TcTextField(input.id, { v -> viewModel.edit { WorkflowEdits.renameInput(it, input.id, v) } }, Modifier.weight(1f), placeholder = stringResource(Res.string.wfe_input_id), mono = true, keyboardOptions = flatKeyboard, tag = "wfe-input-id-$index")
            TcIconButton(PhosphorIcons.Regular.Trash, stringResource(Res.string.wfe_input_remove), onClick = { viewModel.edit { it.copy(inputs = it.inputs.filterIndexed { i, _ -> i != index }) } }, modifier = Modifier.testTag("wfe-input-remove-$index"))
        }
        TcTextField(input.label, { v -> change(input.copy(label = v)) }, placeholder = stringResource(Res.string.wfe_input_label), tag = "wfe-input-label-$index")
        TcTextField(input.default.orEmpty(), { v -> change(input.copy(default = v.ifEmpty { null })) }, placeholder = stringResource(Res.string.wfe_input_default), tag = "wfe-input-default-$index")
        NumberField(input.maxLength, { v -> change(input.copy(maxLength = v ?: 0)) }, stringResource(Res.string.wfe_input_max_length), "wfe-input-max-$index")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.wfe_input_required), color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(input.required, { r -> change(input.copy(required = r)) }, modifier = Modifier.testTag("wfe-input-required-$index"))
        }
    }
}

private val INPUT_RULES = setOf(ProblemRule.INPUT_ID, ProblemRule.INPUT_LABEL, ProblemRule.INPUT_MAX_LENGTH, ProblemRule.INPUT_DEFAULT_LONG, ProblemRule.INPUT_DUPLICATE)

/** The flow as a list from the first step: what each outcome does, loops back marked, steps nothing leads to last. */
@Composable
private fun FlowMapView(viewModel: WorkflowEditViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TeamCode.colors
    val definition = state.definition
    val map = FlowMaps.of(definition)
    fun title(id: String) = definition.node(id)?.title?.ifBlank { null } ?: id
    Column(Modifier.testTag("wfe-map"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.wfe_map_hint), style = MetaStyle, color = colors.muted)
        if (map.rows.isEmpty()) Text(stringResource(Res.string.wfe_map_empty), color = colors.muted, fontSize = 13.sp)
        map.rows.forEach { row ->
            val node = definition.node(row.nodeId) ?: return@forEach
            Column(Modifier.fillMaxWidth().padding(start = (row.depth.coerceAtMost(4) * 14).dp).testTag("wfe-map-${row.nodeId}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title(row.nodeId), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Chip(stepKindWords(node.kind, node.rawKind), tone = Tone.MUTED)
                    if (row.nodeId == definition.start) Chip(stringResource(Res.string.wfe_first), tone = Tone.ACCENT)
                }
                row.edges.forEach { edge ->
                    val outcome = outcomeWords(node, edge.outcome)
                    val (text, color) = when (edge.kind) {
                        EdgeKind.NEXT, EdgeKind.JOIN -> stringResource(Res.string.wfe_map_to, outcome, title(edge.to.orEmpty())) to colors.muted
                        EdgeKind.LOOP -> stringResource(Res.string.wfe_map_loop, outcome, title(edge.to.orEmpty()), node.maxVisits) to colors.warn
                        EdgeKind.END -> stringResource(Res.string.wfe_map_end, outcome) to colors.muted
                        EdgeKind.MISSING -> stringResource(Res.string.wfe_map_missing, outcome, edge.to.orEmpty()) to colors.danger
                        EdgeKind.UNSET -> stringResource(Res.string.wfe_map_unset, outcome) to colors.danger
                    }
                    Text(text, color = color, fontSize = 12.5.sp, modifier = Modifier.padding(start = 10.dp).testTag("wfe-map-${row.nodeId}-${edge.outcome}"))
                }
            }
        }
        if (map.unreachable.isNotEmpty()) {
            Text(stringResource(Res.string.wfe_map_unreachable), color = colors.danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            map.unreachable.forEach { Text(title(it), color = colors.danger, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp).testTag("wfe-map-unreachable-$it")) }
        }
    }
}

/** An outcome in words: `delivered`, the label of the action it is the id of, or `done`. */
@Composable
internal fun outcomeWords(node: DefinitionNode, outcome: String): String = when {
    outcome == DefinitionNode.DELIVERED && node.kind != StepKind.HUMAN -> stringResource(Res.string.wfe_outcome_delivered)
    else -> node.actions.firstOrNull { it.id == outcome }?.label?.takeIf { it.isNotBlank() } ?: outcome
}

/** The problems of a node or of the workflow, one line each, in the member's words. */
@Composable
internal fun Problems(problems: List<WorkflowProblem>, modifier: Modifier = Modifier) {
    if (problems.isEmpty()) return
    val colors = TeamCode.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        problems.forEachIndexed { index, problem ->
            Text(problem.words(), color = colors.danger, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.testTag("wfe-problem-${problem.nodeId ?: "root"}-${problem.rule.name.lowercase()}-$index"))
        }
    }
}
