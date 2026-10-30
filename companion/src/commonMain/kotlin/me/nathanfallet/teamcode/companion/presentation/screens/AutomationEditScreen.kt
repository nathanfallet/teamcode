package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Trash
import me.nathanfallet.teamcode.companion.domain.models.AutomationPreview
import me.nathanfallet.teamcode.companion.domain.models.AutomationVariable
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.models.TriggerKind
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.Group
import me.nathanfallet.teamcode.companion.presentation.components.GroupDivider
import me.nathanfallet.teamcode.companion.presentation.components.NavBar
import me.nathanfallet.teamcode.companion.presentation.components.Segmented
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.components.dismissKeyboardOnTap
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationEditViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The automation form: name; a schedule (five-field cron with its time zone and the catch-up after an outage) or a
 * webhook (declared variables with their JSON paths, a sample payload); the action (a cloud agent on one of the
 * member's profiles; the remote session is shown greyed, it comes later); the prompt, in which `{{name}}` is replaced
 * by a variable or a run value; the overlap and daily limits; and a preview of the rendered prompt. Sections fold, as a
 * phone has little room.
 */
@Composable
fun AutomationEditScreen(viewModel: AutomationEditViewModel, onBack: (() -> Unit)?, onSaved: (automationId: String) -> Unit, backLabel: String = stringResource(Res.string.action_back)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TeamCode.colors
    val form = state.form
    LaunchedEffect(state.savedId) { state.savedId?.let(onSaved) }
    Column(Modifier.fillMaxSize().background(colors.background).imePadding().dismissKeyboardOnTap(), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, stringResource(if (viewModel.isEdit) Res.string.automation_edit else Res.string.automation_create))
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp).testTag("automation-form"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BusyRow(state.loading)
            state.loadError?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("automation-load-error"), action = {
                    TcButton(stringResource(Res.string.action_retry), onClick = viewModel::load, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("automation-load-retry"))
                })
            }
            if (state.notOwner) Banner(stringResource(Res.string.input_automation_not_owner), Tone.WARN, Modifier.testTag("automation-not-owner"))
            if (!state.loading && state.loadError == null && !state.notOwner) {
                TcTextField(form.name, { v -> viewModel.edit { it.copy(name = v) } }, label = stringResource(Res.string.automation_field_name), tag = "automation-name")

                Section("trigger", stringResource(Res.string.automation_section_trigger)) {
                    Segmented(
                        listOf(TriggerKind.CRON to stringResource(Res.string.automation_trigger_schedule), TriggerKind.WEBHOOK to stringResource(Res.string.automation_trigger_webhook)),
                        selected = form.kind, onSelect = { k -> if (!viewModel.isEdit) viewModel.edit { it.copy(kind = k) } }, modifier = Modifier.fillMaxWidth().alpha(if (viewModel.isEdit) 0.6f else 1f),
                        tag = { "automation-kind-${it.name.lowercase()}" },
                    )
                    if (form.kind == TriggerKind.CRON) {
                        TcTextField(form.cron, { v -> viewModel.edit { it.copy(cron = v) } }, label = stringResource(Res.string.automation_field_cron), mono = true, hint = stringResource(Res.string.automation_field_cron_hint),
                            keyboardOptions = plainKeyboard, tag = "automation-cron")
                        TcTextField(form.timezone, { v -> viewModel.edit { it.copy(timezone = v) } }, label = stringResource(Res.string.automation_field_timezone), hint = stringResource(Res.string.automation_field_timezone_hint),
                            keyboardOptions = plainKeyboard, tag = "automation-timezone")
                        Text(stringResource(Res.string.automation_field_catchup), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Segmented(
                            listOf("once" to stringResource(Res.string.automation_catchup_once_short), "skip" to stringResource(Res.string.automation_catchup_skip_short)),
                            selected = form.catchup, onSelect = { c -> viewModel.edit { it.copy(catchup = c) } }, modifier = Modifier.fillMaxWidth(), tag = { "automation-catchup-$it" },
                        )
                    } else {
                        Text(stringResource(Res.string.automation_variables_intro), color = colors.muted, fontSize = 13.sp, lineHeight = 18.sp)
                        form.variables.forEachIndexed { index, variable -> VariableEditor(index, variable, viewModel) }
                        TcButton(stringResource(Res.string.automation_variable_add), onClick = viewModel::addVariable, kind = ButtonKind.SECONDARY, small = true, icon = PhosphorIcons.Regular.Plus, modifier = Modifier.testTag("automation-variable-add"))
                        TcTextField(form.samplePayload, { v -> viewModel.edit { it.copy(samplePayload = v) } }, label = stringResource(Res.string.automation_field_sample), hint = stringResource(Res.string.automation_field_sample_hint),
                            mono = true, singleLine = false, minLines = 3, keyboardOptions = plainKeyboard, tag = "automation-sample")
                    }
                }

                Section("action", stringResource(Res.string.automation_section_action)) {
                    ActionKinds()
                    Text(stringResource(Res.string.new_session_profile), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (state.profiles.isEmpty()) Text(stringResource(Res.string.new_session_no_profile), color = colors.muted, fontSize = 14.sp, modifier = Modifier.testTag("automation-no-profile"))
                    else Group(Modifier.selectableGroup()) {
                        state.profiles.forEachIndexed { index, profile ->
                            if (index > 0) GroupDivider()
                            ProfileChoice(profile, form.profileId == profile.id) { viewModel.edit { it.copy(profileId = profile.id) } }
                        }
                    }
                    TcTextField(form.model, { v -> viewModel.edit { it.copy(model = v) } }, label = stringResource(Res.string.automation_field_model), keyboardOptions = plainKeyboard, tag = "automation-model")
                    TcTextField(form.effort, { v -> viewModel.edit { it.copy(effort = v) } }, label = stringResource(Res.string.automation_field_effort), keyboardOptions = plainKeyboard, tag = "automation-effort")
                    TcTextField(form.maxRuntime, { v -> viewModel.edit { it.copy(maxRuntime = v.filter(Char::isDigit)) } }, label = stringResource(Res.string.automation_field_max_runtime),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), tag = "automation-max-runtime")
                }

                Section("prompt", stringResource(Res.string.automation_section_prompt)) {
                    TcTextField(form.prompt, { v -> viewModel.edit { it.copy(prompt = v) } }, hint = stringResource(Res.string.automation_field_prompt_hint), singleLine = false, minLines = 6,
                        keyboardOptions = plainKeyboard, tag = "automation-prompt")
                }

                Section("limits", stringResource(Res.string.automation_section_limits), initiallyOpen = false) {
                    Text(stringResource(Res.string.automation_field_overlap), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Segmented(
                        listOf("skip" to stringResource(Res.string.automation_overlap_skip), "queue" to stringResource(Res.string.automation_overlap_queue), "allow" to stringResource(Res.string.automation_overlap_allow)),
                        selected = form.overlap, onSelect = { o -> viewModel.edit { it.copy(overlap = o) } }, modifier = Modifier.fillMaxWidth(), tag = { "automation-overlap-$it" },
                    )
                    TcTextField(form.maxRunsPerDay, { v -> viewModel.edit { it.copy(maxRunsPerDay = v.filter(Char::isDigit)) } }, label = stringResource(Res.string.automation_field_max_runs),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), tag = "automation-max-runs")
                }

                Section("preview", stringResource(Res.string.automation_section_preview), initiallyOpen = false) {
                    TcButton(stringResource(Res.string.automation_preview_prompt), onClick = viewModel::preview, kind = ButtonKind.SECONDARY, busy = state.previewing, modifier = Modifier.testTag("automation-preview"))
                    state.previewError?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("automation-preview-error")) }
                    state.preview?.let { PreviewView(it) }
                }
            }
            state.error?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("automation-save-error")) }
        }
        if (!state.loading && state.loadError == null && !state.notOwner) Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp)) {
            TcButton(stringResource(Res.string.automation_save), onClick = viewModel::save, busy = state.saving, modifier = Modifier.testTag("automation-save"))
        }
    }
}

private val plainKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

/** A foldable group of fields: a header that opens and closes it. */
@Composable
private fun Section(id: String, title: String, initiallyOpen: Boolean = true, content: @Composable () -> Unit) {
    val colors = TeamCode.colors
    var open by rememberSaveable(id) { mutableStateOf(initiallyOpen) }
    TcCard(Modifier.testTag("automation-section-$id"), spacing = 10.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Button) { open = !open }.testTag("automation-section-$id-header"), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (open) PhosphorIcons.Regular.CaretDown else PhosphorIcons.Regular.CaretRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
        }
        if (open) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** The cloud agent, chosen; the remote session, shown greyed and not selectable: it comes later. */
@Composable
private fun ActionKinds() {
    val colors = TeamCode.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.weight(1f).border(1.5.dp, colors.accent, Radii.control).padding(10.dp).testTag("automation-action-cloud"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(stringResource(Res.string.automation_action_cloud), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(stringResource(Res.string.automation_action_cloud_hint), color = colors.muted, fontSize = 12.sp)
        }
        Column(
            Modifier.weight(1f).alpha(0.45f).border(1.dp, colors.line, Radii.control).padding(10.dp).testTag("automation-action-remote"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(stringResource(Res.string.automation_action_remote), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(stringResource(Res.string.automation_action_remote_soon), color = colors.muted, fontSize = 12.sp, modifier = Modifier.testTag("automation-action-remote-soon"))
        }
    }
}

@Composable
private fun ProfileChoice(profile: Profile, selected: Boolean, onClick: () -> Unit) {
    val colors = TeamCode.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp).testTag("automation-profile-${profile.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp).border(1.5.dp, if (selected) colors.accent else colors.line, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).background(colors.accent, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(profile.name, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(profile.agentKind, style = MetaStyle, color = colors.muted)
        }
    }
}

@Composable
private fun VariableEditor(index: Int, variable: AutomationVariable, viewModel: AutomationEditViewModel) {
    val colors = TeamCode.colors
    Column(Modifier.fillMaxWidth().border(1.dp, colors.line, Radii.control).padding(10.dp).testTag("automation-variable-$index"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TcTextField(variable.name, { v -> viewModel.setVariable(index, variable.copy(name = v)) }, Modifier.weight(1f), placeholder = stringResource(Res.string.automation_variable_name), mono = true, keyboardOptions = plainKeyboard, tag = "automation-variable-name-$index")
            TcIconButton(PhosphorIcons.Regular.Trash, stringResource(Res.string.automation_variable_remove), onClick = { viewModel.removeVariable(index) }, modifier = Modifier.testTag("automation-variable-remove-$index"))
        }
        TcTextField(variable.path, { v -> viewModel.setVariable(index, variable.copy(path = v)) }, placeholder = "$.ticket.number", mono = true, keyboardOptions = plainKeyboard, tag = "automation-variable-path-$index")
        Segmented(
            listOf("string" to "string", "int" to "int", "number" to "number", "boolean" to "bool"),
            selected = variable.type, onSelect = { t -> viewModel.setVariable(index, variable.copy(type = t)) }, modifier = Modifier.fillMaxWidth(), tag = { "automation-variable-type-$index-$it" },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.automation_variable_required), color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(variable.required, { r -> viewModel.setVariable(index, variable.copy(required = r)) }, modifier = Modifier.testTag("automation-variable-required-$index"))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.automation_variable_sensitive), color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(variable.sensitive, { s -> viewModel.setVariable(index, variable.copy(sensitive = s)) }, modifier = Modifier.testTag("automation-variable-sensitive-$index"))
        }
    }
}

/** The rendered prompt, the variables with their values (sensitive ones masked), the problems, and the slots a schedule gives. */
@Composable
internal fun PreviewView(preview: AutomationPreview) {
    val colors = TeamCode.colors
    Column(Modifier.testTag("automation-preview-result"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        preview.renderedPrompt?.let { prompt ->
            Text(stringResource(Res.string.automation_rendered_prompt), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(prompt, style = MonospaceStyle, color = colors.text, modifier = Modifier.fillMaxWidth().background(colors.panelAlt, Radii.control).padding(10.dp).testTag("automation-rendered-prompt"))
        }
        preview.problems.forEach { problem ->
            Text(stringResource(Res.string.automation_problem, problem.path, problem.rule), color = colors.danger, fontSize = 13.sp, modifier = Modifier.testTag("automation-problem"))
        }
        preview.wouldSkip?.let { Text(stringResource(Res.string.automation_would_skip, it), color = colors.warn, fontSize = 13.sp, modifier = Modifier.testTag("automation-would-skip")) }
        if (preview.variables.isNotEmpty()) {
            Text(stringResource(Res.string.automation_section_variables), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            preview.variables.forEach { v ->
                val value = if (v.sensitive) stringResource(Res.string.automation_value_masked) else if (!v.present) stringResource(Res.string.automation_value_missing) else v.value.orEmpty()
                Text("${v.name} = $value · ${v.type}", style = MonospaceStyle, color = colors.text, modifier = Modifier.testTag("automation-variable-value-${v.name}"))
            }
        }
        if (preview.nextRuns.isNotEmpty()) {
            Text(stringResource(Res.string.automation_upcoming), color = colors.muted, fontSize = 12.5.sp)
            preview.nextRuns.forEach { Text(formatUtc(it), style = MetaStyle, color = colors.text) }
        }
    }
}
