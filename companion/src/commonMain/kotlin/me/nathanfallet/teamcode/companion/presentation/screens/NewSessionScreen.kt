package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ErrorText
import me.nathanfallet.teamcode.companion.presentation.components.FormScaffold
import me.nathanfallet.teamcode.companion.presentation.viewmodels.NewSessionViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.new_session_create
import me.nathanfallet.teamcode.companion.resources.new_session_effort
import me.nathanfallet.teamcode.companion.resources.new_session_message
import me.nathanfallet.teamcode.companion.resources.new_session_model
import me.nathanfallet.teamcode.companion.resources.new_session_name
import me.nathanfallet.teamcode.companion.resources.new_session_no_profile
import me.nathanfallet.teamcode.companion.resources.new_session_optional
import me.nathanfallet.teamcode.companion.resources.new_session_profile
import me.nathanfallet.teamcode.companion.resources.new_session_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun NewSessionScreen(viewModel: NewSessionViewModel, onBack: () -> Unit, onCreated: (CloudSession) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.created) { state.created?.let(onCreated) }
    FormScaffold(title = stringResource(Res.string.new_session_title), onBack = onBack) {
        Text(stringResource(Res.string.new_session_profile), style = MaterialTheme.typography.titleSmall)
        BusyRow(state.loadingProfiles)
        if (!state.loadingProfiles && state.profiles.isEmpty() && state.error == null) {
            Text(stringResource(Res.string.new_session_no_profile), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.profiles.forEach { profile ->
                FilterChip(
                    selected = state.profileId == profile.id,
                    onClick = { viewModel.onProfile(profile.id) },
                    label = { Text("${profile.name} · ${profile.agentKind}") },
                    modifier = Modifier.testTag("profile-${profile.id}"),
                )
            }
        }
        OutlinedTextField(state.name, viewModel::onName, label = { Text(stringResource(Res.string.new_session_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("name"))
        OutlinedTextField(state.model, viewModel::onModel, label = { Text(stringResource(Res.string.new_session_model)) }, supportingText = { Text(stringResource(Res.string.new_session_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("model"))
        OutlinedTextField(state.effort, viewModel::onEffort, label = { Text(stringResource(Res.string.new_session_effort)) }, supportingText = { Text(stringResource(Res.string.new_session_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("effort"))
        if (state.acceptsMessage) {
            OutlinedTextField(state.message, viewModel::onMessage, label = { Text(stringResource(Res.string.new_session_message)) }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag("message"))
        }
        ErrorText(state.error)
        if (state.error != null && state.profiles.isEmpty()) TextButton(onClick = viewModel::reload) { Text(stringResource(Res.string.action_retry)) }
        Button(onClick = viewModel::create, enabled = state.canCreate, modifier = Modifier.fillMaxWidth().testTag("create")) { Text(stringResource(Res.string.new_session_create)) }
        BusyRow(state.creating)
    }
}
