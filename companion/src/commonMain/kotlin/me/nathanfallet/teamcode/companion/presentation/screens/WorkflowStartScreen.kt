package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.nathanfallet.teamcode.companion.domain.models.WorkflowRun
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.Group
import me.nathanfallet.teamcode.companion.presentation.components.GroupDivider
import me.nathanfallet.teamcode.companion.presentation.components.NavBar
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowStartViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The form that starts a run: a field for each input (label, required, default, maximum length), the profile picker of
 * the new-session form when an agent step names no profile, then Start, which lands on the new run's page.
 */
@Composable
fun WorkflowStartScreen(viewModel: WorkflowStartViewModel, onBack: (() -> Unit)?, onStarted: (WorkflowRun) -> Unit, backLabel: String = stringResource(Res.string.workflow_title)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TeamCode.colors
    val workflow = state.workflow
    LaunchedEffect(state.started) { state.started?.let(onStarted) }
    Column(Modifier.fillMaxSize().background(colors.background).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, stringResource(Res.string.workflow_start_title))
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp).testTag("workflow-start-form"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("workflow-start-load-error"), action = {
                    TcButton(stringResource(Res.string.action_retry), onClick = viewModel::reload, kind = ButtonKind.SECONDARY, small = true)
                })
            }
            if (state.missing) Banner(stringResource(Res.string.workflow_start_missing), Tone.WARN, Modifier.testTag("workflow-start-missing"))
            BusyRow(state.loading)
            if (workflow != null) {
                Text(workflow.name, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                workflow.inputs.forEach { input ->
                    val suffix = stringResource(if (input.required) Res.string.workflow_input_required else Res.string.workflow_input_optional)
                    TcTextField(
                        state.valueOf(input.id), { viewModel.onInput(input.id, it.take(input.maxLength)) }, Modifier.fillMaxWidth(),
                        label = "${input.label} ($suffix)", hint = stringResource(Res.string.workflow_input_max, input.maxLength), singleLine = input.maxLength <= 200,
                        minLines = if (input.maxLength <= 200) 1 else 3, tag = "workflow-input-${input.id}",
                    )
                }
                if (state.needsProfile) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(Res.string.new_session_profile), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(Res.string.workflow_start_profile), color = colors.muted, fontSize = 13.sp)
                    BusyRow(state.loadingProfiles)
                    if (!state.loadingProfiles && state.profiles.isEmpty() && state.error == null) Text(stringResource(Res.string.new_session_no_profile), color = colors.muted, fontSize = 14.sp)
                    if (state.profiles.isNotEmpty()) Group(Modifier.selectableGroup()) {
                        state.profiles.forEachIndexed { index, profile ->
                            if (index > 0) GroupDivider()
                            ProfileOption(profile, selected = state.profileId == profile.id) { viewModel.onProfile(profile.id) }
                        }
                    }
                }
                state.startError?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("workflow-start-error")) }
                TcButton(stringResource(Res.string.workflow_start_button), onClick = viewModel::start, enabled = state.canStart, busy = state.starting, modifier = Modifier.testTag("workflow-start-submit"))
            }
        }
    }
}
