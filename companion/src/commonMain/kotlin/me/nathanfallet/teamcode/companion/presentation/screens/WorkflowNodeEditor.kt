package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.CaretUp
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Trash
import me.nathanfallet.teamcode.companion.domain.models.DefinitionAction
import me.nathanfallet.teamcode.companion.domain.models.DefinitionDeliverable
import me.nathanfallet.teamcode.companion.domain.models.DefinitionField
import me.nathanfallet.teamcode.companion.domain.models.DefinitionNode
import me.nathanfallet.teamcode.companion.domain.models.DefinitionOption
import me.nathanfallet.teamcode.companion.domain.models.StepKind
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.ProblemRule
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowEdits
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.Segmented
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowEditUiState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowEditViewModel
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.stringResource

/** One step of the definition as a card that folds. Its tags are numbered by position (`wfe-n0-...`), which does not change while the id is typed. */
@Composable
internal fun NodeEditor(node: DefinitionNode, index: Int, total: Int, viewModel: WorkflowEditViewModel, state: WorkflowEditUiState) {
    val colors = TeamCode.colors
    val definition = state.definition
    // A step that has just been added (nothing typed) opens; so does the only step.
    var open by rememberSaveable("wfe-node-$index") { mutableStateOf(total == 1 || (node.title.isBlank() && node.instruction.isBlank())) }
    val problems = state.problemsOf(node.id)
    val tag = "wfe-n$index"
    fun change(new: (DefinitionNode) -> DefinitionNode) = viewModel.editNode(node.id) { new(it) }
    TcCard(Modifier.testTag(tag), spacing = 10.dp, borderColor = if (problems.isEmpty()) colors.line else colors.danger) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Button) { open = !open }.testTag("$tag-header"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(node.title.ifBlank { stringResource(Res.string.wfe_untitled) }, color = if (node.title.isBlank()) colors.muted else colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (node.id == definition.start) Chip(stringResource(Res.string.wfe_first), tone = Tone.ACCENT)
            Chip(stepKindWords(node.kind, node.rawKind), tone = Tone.MUTED)
            if (problems.isNotEmpty()) Chip(problems.size.toString(), tone = Tone.DANGER)
            Icon(if (open) PhosphorIcons.Regular.CaretDown else PhosphorIcons.Regular.CaretRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
        }
        Problems(problems, Modifier.testTag("$tag-problems"))
        if (!open) return@TcCard
        Segmented(
            listOf(StepKind.AGENT to stringResource(Res.string.workflow_kind_agent), StepKind.HUMAN to stringResource(Res.string.workflow_kind_human), StepKind.WORKFLOW to stringResource(Res.string.workflow_kind_workflow)),
            selected = node.kind, onSelect = { kind -> viewModel.edit { WorkflowEdits.setKind(it, node.id, kind) } }, modifier = Modifier.fillMaxWidth(), tag = { "$tag-kind-${it.wire}" },
        )
        TcTextField(node.title, { v -> change { it.copy(title = v) } }, label = stringResource(Res.string.wfe_step_title), error = problems.any { it.rule == ProblemRule.TITLE }, tag = "$tag-title")
        TcTextField(node.id, { v -> viewModel.edit { WorkflowEdits.renameNode(it, node.id, v) } }, label = stringResource(Res.string.wfe_step_id), hint = stringResource(Res.string.wfe_step_id_hint), mono = true, keyboardOptions = flatKeyboard,
            error = problems.any { it.rule == ProblemRule.NODE_ID || it.rule == ProblemRule.NODE_DUPLICATE }, tag = "$tag-id")
        val hints = WorkflowEdits.templates(definition, node.id)
        when (node.kind) {
            StepKind.AGENT -> AgentBlock(node, tag, hints, ::change, state)
            StepKind.HUMAN -> HumanBlock(node, tag, hints, ::change, state)
            StepKind.WORKFLOW -> WorkflowBlock(node, tag, hints, ::change, state)
            StepKind.UNKNOWN -> Unit
        }
        TransitionsBlock(node, index, tag, viewModel, state)
        NumberField(node.maxVisits, { v -> change { it.copy(maxVisits = v ?: 0) } }, stringResource(Res.string.wfe_step_max_visits), "$tag-max-visits", error = problems.any { it.rule == ProblemRule.MAX_VISITS })
        Text(stringResource(Res.string.wfe_step_max_visits_hint), style = MetaStyle, color = colors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (node.id != definition.start && node.kind != StepKind.HUMAN) TcButton(stringResource(Res.string.wfe_make_first), onClick = { viewModel.edit { it.copy(start = node.id) } }, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.weight(1f).testTag("$tag-first"))
            else Text("", Modifier.weight(1f))
            TcIconButton(PhosphorIcons.Regular.CaretUp, stringResource(Res.string.wfe_step_up), onClick = { viewModel.edit { WorkflowEdits.moveNode(it, node.id, -1) } }, enabled = index > 0, modifier = Modifier.testTag("$tag-up"))
            TcIconButton(PhosphorIcons.Regular.CaretDown, stringResource(Res.string.wfe_step_down), onClick = { viewModel.edit { WorkflowEdits.moveNode(it, node.id, 1) } }, enabled = index < total - 1, modifier = Modifier.testTag("$tag-down"))
            TcIconButton(PhosphorIcons.Regular.Trash, stringResource(Res.string.wfe_step_remove), onClick = { viewModel.edit { WorkflowEdits.removeNode(it, node.id) } }, modifier = Modifier.testTag("$tag-remove"))
        }
    }
}

@Composable
private fun AgentBlock(node: DefinitionNode, tag: String, hints: List<String>, change: ((DefinitionNode) -> DefinitionNode) -> Unit, state: WorkflowEditUiState) {
    val colors = TeamCode.colors
    val problems = state.problemsOf(node.id)
    TemplatedField(node.instruction, { v -> change { it.copy(instruction = v) } }, hints, stringResource(Res.string.wfe_instruction), "$tag-instruction", error = problems.any { it.rule == ProblemRule.AGENT_INSTRUCTION })
    Text(stringResource(Res.string.wfe_deliverable), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val deliverable = node.deliverable ?: DefinitionDeliverable("text")
    Segmented(
        listOf("artifact" to stringResource(Res.string.wfe_deliverable_artifact), "url" to stringResource(Res.string.wfe_deliverable_url), "text" to stringResource(Res.string.wfe_deliverable_text), "json" to stringResource(Res.string.wfe_deliverable_json)),
        selected = deliverable.type,
        onSelect = { type -> change { it.copy(deliverable = DefinitionDeliverable(type, deliverable.label, deliverable.domain.takeIf { type == "url" }, deliverable.schema.takeIf { type == "json" })) } },
        modifier = Modifier.fillMaxWidth(), tag = { "$tag-deliverable-$it" },
    )
    Text(
        stringResource(when (deliverable.type) { "artifact" -> Res.string.wfe_deliverable_artifact_hint; "url" -> Res.string.wfe_deliverable_url_hint; "json" -> Res.string.wfe_deliverable_json_hint; else -> Res.string.wfe_deliverable_text_hint }),
        style = MetaStyle, color = colors.muted,
    )
    if (deliverable.type == "url") TcTextField(deliverable.domain.orEmpty(), { v -> change { it.copy(deliverable = deliverable.copy(domain = v.ifEmpty { null })) } }, label = stringResource(Res.string.wfe_deliverable_domain), hint = "github.com", mono = true, keyboardOptions = flatKeyboard,
        error = problems.any { it.rule == ProblemRule.DELIVERABLE_DOMAIN }, tag = "$tag-domain")
    if (deliverable.type == "json") TcTextField(deliverable.schema.orEmpty(), { v -> change { it.copy(deliverable = deliverable.copy(schema = v)) } }, label = stringResource(Res.string.wfe_deliverable_schema), hint = stringResource(Res.string.wfe_deliverable_schema_hint),
        mono = true, singleLine = false, minLines = 4, keyboardOptions = flatKeyboard, error = problems.any { it.rule == ProblemRule.DELIVERABLE_SCHEMA_MISSING || it.rule == ProblemRule.DELIVERABLE_SCHEMA_INVALID }, tag = "$tag-schema")
    if (state.profiles.isNotEmpty()) {
        Text(stringResource(Res.string.wfe_profile), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Chips {
            ChoiceChip(stringResource(Res.string.wfe_profile_none), node.profileId.isNullOrBlank(), { change { it.copy(profileId = null) } }, "$tag-profile-none")
            state.profiles.forEach { profile -> ChoiceChip(profile.name, node.profileId == profile.id, { change { it.copy(profileId = profile.id) } }, "$tag-profile-${profile.id}") }
        }
        Text(stringResource(Res.string.wfe_profile_hint), style = MetaStyle, color = colors.muted)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TcTextField(node.agent.orEmpty(), { v -> change { it.copy(agent = v.ifEmpty { null }) } }, Modifier.weight(1f), label = stringResource(Res.string.wfe_agent), mono = true, keyboardOptions = flatKeyboard, tag = "$tag-agent")
        TcTextField(node.model.orEmpty(), { v -> change { it.copy(model = v.ifEmpty { null }) } }, Modifier.weight(1f), label = stringResource(Res.string.wfe_model), mono = true, keyboardOptions = flatKeyboard, tag = "$tag-model")
    }
}

@Composable
private fun HumanBlock(node: DefinitionNode, tag: String, hints: List<String>, change: ((DefinitionNode) -> DefinitionNode) -> Unit, state: WorkflowEditUiState) {
    val colors = TeamCode.colors
    val problems = state.problemsOf(node.id)
    TemplatedField(node.instruction, { v -> change { it.copy(instruction = v) } }, hints, stringResource(Res.string.wfe_ask), "$tag-instruction")
    Text(stringResource(Res.string.wfe_assignee), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Segmented(
        listOf(false to stringResource(Res.string.wfe_assignee_owner), true to stringResource(Res.string.wfe_assignee_member)),
        selected = node.assigneeMemberId != null, onSelect = { member -> change { it.copy(assigneeMemberId = if (member) it.assigneeMemberId ?: "" else null) } }, modifier = Modifier.fillMaxWidth(), tag = { "$tag-assignee-${if (it) "member" else "owner"}" },
    )
    if (node.assigneeMemberId != null) {
        val members = state.members
        if (members != null) Chips {
            members.forEach { member -> ChoiceChip(member.username, node.assigneeMemberId == member.id, { change { it.copy(assigneeMemberId = member.id) } }, "$tag-member-${member.id}") }
        } else TcTextField(node.assigneeMemberId, { v -> change { it.copy(assigneeMemberId = v.trim()) } }, label = stringResource(Res.string.wfe_assignee_id), mono = true, keyboardOptions = flatKeyboard,
            error = problems.any { it.rule == ProblemRule.ASSIGNEE_MEMBER }, tag = "$tag-member-id")
    }

    Text(stringResource(Res.string.wfe_fields), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(Res.string.wfe_fields_hint), style = MetaStyle, color = colors.muted)
    node.fields.forEachIndexed { j, field -> FieldEditor(node, j, field, tag, change) }
    Text(stringResource(Res.string.wfe_field_add), style = MetaStyle, color = colors.muted)
    Chips {
        for (type in FIELD_TYPES) AddChip(fieldTypeWords(type), { change { it.copy(fields = it.fields + WorkflowEdits.newField(it, type)) } }, "$tag-field-add-$type")
    }

    Text(stringResource(Res.string.wfe_actions), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(if (node.actions.isEmpty()) Res.string.wfe_actions_none else Res.string.wfe_actions_hint), style = MetaStyle, color = colors.muted)
    node.actions.forEachIndexed { j, action -> ActionEditor(node, j, action, tag, change) }
    TcButton(stringResource(Res.string.wfe_action_add), onClick = { change { it.copy(actions = it.actions + WorkflowEdits.newAction(it)) } }, kind = ButtonKind.SECONDARY, small = true, icon = PhosphorIcons.Regular.Plus, modifier = Modifier.testTag("$tag-action-add"))
}

private val FIELD_TYPES = listOf("text", "choice", "confirm", "number", "date", "file", "info")

@Composable
private fun fieldTypeWords(type: String): String = stringResource(
    when (type) {
        "text" -> Res.string.wfe_type_text; "choice" -> Res.string.wfe_type_choice; "confirm" -> Res.string.wfe_type_confirm; "number" -> Res.string.wfe_type_number
        "date" -> Res.string.wfe_type_date; "file" -> Res.string.wfe_type_file; "info" -> Res.string.wfe_type_info; else -> Res.string.wfe_type_text
    },
)

/** A field a person fills: the same shape as the fields of a wait (text, choice, yes/no, number, date, file name, or a note that takes no value). */
@Composable
private fun FieldEditor(node: DefinitionNode, j: Int, field: DefinitionField, tag: String, change: ((DefinitionNode) -> DefinitionNode) -> Unit) {
    val colors = TeamCode.colors
    val ft = "$tag-f$j"
    fun edit(new: DefinitionField) = change { it.copy(fields = it.fields.mapIndexed { i, old -> if (i == j) new else old }) }
    Column(Modifier.fillMaxWidth().border(1.dp, colors.line, Radii.control).padding(10.dp).testTag(ft), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TcTextField(field.id, { v -> change { WorkflowEdits.renameField(it, field.id, v) } }, Modifier.weight(1f), placeholder = stringResource(Res.string.wfe_field_id), mono = true, keyboardOptions = flatKeyboard, tag = "$ft-id")
            TcIconButton(PhosphorIcons.Regular.Trash, stringResource(Res.string.wfe_field_remove), onClick = { change { WorkflowEdits.removeField(it, field.id) } }, modifier = Modifier.testTag("$ft-remove"))
        }
        TcTextField(field.label, { v -> edit(field.copy(label = v)) }, placeholder = stringResource(Res.string.wfe_field_label), tag = "$ft-label")
        Chips { for (type in FIELD_TYPES) ChoiceChip(fieldTypeWords(type), field.type == type, { edit(WorkflowEdits.retype(field, type)) }, "$ft-type-$type") }
        TcTextField(field.help.orEmpty(), { v -> edit(field.copy(help = v.ifEmpty { null })) }, placeholder = stringResource(Res.string.wfe_field_help), tag = "$ft-help")
        if (field.type != "info") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.wfe_field_required), color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(field.required, { r -> edit(field.copy(required = r)) }, modifier = Modifier.testTag("$ft-required"))
        }
        when (field.type) {
            "text" -> {
                NumberField(field.maxLength, { v -> edit(field.copy(maxLength = v)) }, stringResource(Res.string.wfe_field_max_length), "$ft-max")
                TcTextField(field.default.orEmpty(), { v -> edit(field.copy(default = v.ifEmpty { null })) }, placeholder = stringResource(Res.string.wfe_field_default), tag = "$ft-default")
            }
            "number" -> TcTextField(field.default.orEmpty(), { v -> edit(field.copy(default = v.ifEmpty { null })) }, placeholder = stringResource(Res.string.wfe_field_default), tag = "$ft-default")
            "date" -> TcTextField(field.default.orEmpty(), { v -> edit(field.copy(default = v.ifEmpty { null })) }, placeholder = stringResource(Res.string.wfe_field_default_date), tag = "$ft-default")
            "choice" -> {
                field.options.forEachIndexed { k, option ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TcTextField(option.value, { v -> edit(field.copy(options = field.options.mapIndexed { i, o -> if (i == k) o.copy(value = v) else o })) }, Modifier.weight(1f), placeholder = stringResource(Res.string.wfe_option_value), mono = true, keyboardOptions = flatKeyboard, tag = "$ft-opt-$k-value")
                        TcTextField(option.label, { v -> edit(field.copy(options = field.options.mapIndexed { i, o -> if (i == k) o.copy(label = v) else o })) }, Modifier.weight(1f), placeholder = stringResource(Res.string.wfe_option_label), tag = "$ft-opt-$k-label")
                        TcIconButton(PhosphorIcons.Regular.Trash, stringResource(Res.string.wfe_option_remove), onClick = { edit(field.copy(options = field.options.filterIndexed { i, _ -> i != k })) }, modifier = Modifier.testTag("$ft-opt-$k-remove"))
                    }
                }
                TcButton(stringResource(Res.string.wfe_option_add), onClick = { edit(field.copy(options = field.options + DefinitionOption("option${field.options.size + 1}", ""))) }, kind = ButtonKind.SECONDARY, small = true, icon = PhosphorIcons.Regular.Plus, modifier = Modifier.testTag("$ft-opt-add"))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.wfe_field_multiple), color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(field.multiple, { m -> edit(field.copy(multiple = m, default = null)) }, modifier = Modifier.testTag("$ft-multiple"))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.wfe_field_other), color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(field.allowOther, { o -> edit(field.copy(allowOther = o)) }, modifier = Modifier.testTag("$ft-other"))
                }
            }
            else -> Unit
        }
    }
}

/** A button of the person's step: its id is the outcome the step ends with; [DefinitionAction.requires] names the fields that must be filled for it. */
@Composable
private fun ActionEditor(node: DefinitionNode, j: Int, action: DefinitionAction, tag: String, change: ((DefinitionNode) -> DefinitionNode) -> Unit) {
    val colors = TeamCode.colors
    val at = "$tag-a$j"
    fun edit(new: DefinitionAction) = change { it.copy(actions = it.actions.mapIndexed { i, old -> if (i == j) new else old }) }
    Column(Modifier.fillMaxWidth().border(1.dp, colors.line, Radii.control).padding(10.dp).testTag(at), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TcTextField(action.id, { v -> change { WorkflowEdits.renameAction(it, action.id, v) } }, Modifier.weight(1f), placeholder = stringResource(Res.string.wfe_action_id), mono = true, keyboardOptions = flatKeyboard, tag = "$at-id")
            TcIconButton(PhosphorIcons.Regular.Trash, stringResource(Res.string.wfe_action_remove), onClick = { change { it.copy(actions = it.actions.filterIndexed { i, _ -> i != j }) } }, modifier = Modifier.testTag("$at-remove"))
        }
        TcTextField(action.label, { v -> edit(action.copy(label = v)) }, placeholder = stringResource(Res.string.wfe_action_label), tag = "$at-label")
        Chips {
            for (style in listOf<String?>(null, "primary", "danger")) ChoiceChip(
                stringResource(when (style) { "primary" -> Res.string.wfe_style_primary; "danger" -> Res.string.wfe_style_danger; else -> Res.string.wfe_style_neutral }),
                action.style == style, { edit(action.copy(style = style)) }, "$at-style-${style ?: "neutral"}",
            )
        }
        val fillable = node.fields.filter { it.type != "info" }
        if (fillable.isNotEmpty()) {
            Text(stringResource(Res.string.wfe_action_requires), style = MetaStyle, color = colors.muted)
            Chips {
                fillable.forEach { field ->
                    val on = field.id in action.requires
                    ChoiceChip(field.label.ifBlank { field.id }, on, { edit(action.copy(requires = if (on) action.requires - field.id else action.requires + field.id)) }, "$at-requires-${field.id}")
                }
            }
        }
    }
}

@Composable
private fun WorkflowBlock(node: DefinitionNode, tag: String, hints: List<String>, change: ((DefinitionNode) -> DefinitionNode) -> Unit, state: WorkflowEditUiState) {
    val colors = TeamCode.colors
    val saved = state.saved
    Text(stringResource(Res.string.wfe_callee), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    if (saved != null) {
        val others = saved.values.filter { it.id != state.editingId }
        if (others.isEmpty()) Text(stringResource(Res.string.wfe_callee_none), color = colors.muted, fontSize = 13.sp)
        Chips { others.forEach { w -> ChoiceChip(w.name, node.workflowId == w.id, { change { it.copy(workflowId = w.id, arguments = emptyMap()) } }, "$tag-callee-${w.id}") } }
    } else TcTextField(node.workflowId.orEmpty(), { v -> change { it.copy(workflowId = v.trim().ifEmpty { null }) } }, label = stringResource(Res.string.wfe_callee_id), mono = true, keyboardOptions = flatKeyboard, tag = "$tag-callee-id")
    val callee = node.workflowId?.let { saved?.get(it) }
    if (callee != null) {
        Text(stringResource(Res.string.wfe_arguments), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (callee.inputs.isEmpty()) Text(stringResource(Res.string.wfe_arguments_none), color = colors.muted, fontSize = 13.sp)
        callee.inputs.forEach { input ->
            val required = input.required && input.default == null
            TemplatedField(
                node.arguments[input.id].orEmpty(), { v -> change { it.copy(arguments = if (v.isBlank()) it.arguments - input.id else it.arguments + (input.id to v)) } }, hints,
                input.label + if (required) " *" else "", "$tag-arg-${input.id}", minLines = 1, error = required && node.arguments[input.id].isNullOrBlank(),
            )
        }
        node.arguments.keys.filter { key -> callee.inputs.none { it.id == key } }.forEach { key ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.wfe_argument_unknown, key), color = colors.danger, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TcIconButton(PhosphorIcons.Regular.Trash, stringResource(Res.string.wfe_argument_remove), onClick = { change { it.copy(arguments = it.arguments - key) } }, modifier = Modifier.testTag("$tag-arg-remove-$key"))
            }
        }
    }
}

/** Where each outcome of the step goes: a step, or the end of the workflow. A step that is not routed yet shows no choice made. */
@Composable
private fun TransitionsBlock(node: DefinitionNode, index: Int, tag: String, viewModel: WorkflowEditViewModel, state: WorkflowEditUiState) {
    val colors = TeamCode.colors
    Text(stringResource(Res.string.wfe_transitions), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    node.outcomes.forEach { outcome ->
        val transition = node.transition(outcome)
        Column(Modifier.testTag("$tag-out-$outcome"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.wfe_when, outcomeWords(node, outcome)), color = if (transition == null) colors.danger else colors.muted, fontSize = 13.sp)
            Chips {
                ChoiceChip(stringResource(Res.string.wfe_end), transition != null && transition.to == null, { viewModel.edit { WorkflowEdits.route(it, node.id, outcome, null) } }, "$tag-to-$outcome-end")
                state.definition.nodes.forEachIndexed { k, target ->
                    ChoiceChip(target.title.ifBlank { target.id }, transition?.to == target.id, { viewModel.edit { WorkflowEdits.route(it, node.id, outcome, target.id) } }, "$tag-to-$outcome-n$k")
                }
            }
        }
    }
}

/** A multi-line text with the templates the step may use offered under it: a tap adds one at the end. */
@Composable
internal fun TemplatedField(value: String, onChange: (String) -> Unit, hints: List<String>, label: String, tag: String, minLines: Int = 3, error: Boolean = false) {
    val colors = TeamCode.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TcTextField(value, onChange, label = label, singleLine = false, minLines = minLines, keyboardOptions = flatKeyboard, error = error, tag = tag)
        if (hints.isNotEmpty()) {
            Text(stringResource(Res.string.wfe_templates), style = MetaStyle, color = colors.muted)
            Chips {
                hints.forEachIndexed { k, token ->
                    AddChip(token, { onChange(value + (if (value.isEmpty() || value.endsWith(" ") || value.endsWith("\n")) "" else " ") + token) }, "$tag-hint-$k", mono = true)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Chips(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/** One choice among several: the chosen one is outlined with the accent. */
@Composable
internal fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    val colors = TeamCode.colors
    Box(
        Modifier.heightIn(min = 36.dp).clip(Radii.control).background(if (selected) colors.panel else colors.panelAlt).border(if (selected) 1.5.dp else 1.dp, if (selected) colors.accent else colors.line, Radii.control)
            .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) colors.text else colors.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** A chip that adds something when tapped (a template to a text, a field to a step). */
@Composable
internal fun AddChip(text: String, onClick: () -> Unit, tag: String, mono: Boolean = false) {
    val colors = TeamCode.colors
    Box(
        Modifier.heightIn(min = 34.dp).clip(Radii.control).background(colors.panelAlt).border(1.dp, colors.line, Radii.control).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = colors.accentText, fontSize = 12.5.sp, fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null, maxLines = 1) }
}
