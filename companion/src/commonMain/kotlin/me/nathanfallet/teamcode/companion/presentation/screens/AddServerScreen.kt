package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ErrorText
import me.nathanfallet.teamcode.companion.presentation.components.FormScaffold
import me.nathanfallet.teamcode.companion.presentation.components.InfoCard
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AddServerViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_continue
import me.nathanfallet.teamcode.companion.resources.add_server_check
import me.nathanfallet.teamcode.companion.resources.add_server_found
import me.nathanfallet.teamcode.companion.resources.add_server_intro
import me.nathanfallet.teamcode.companion.resources.add_server_new_warning
import me.nathanfallet.teamcode.companion.resources.add_server_origin
import me.nathanfallet.teamcode.companion.resources.add_server_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AddServerScreen(
    canGoBack: Boolean,
    onBack: () -> Unit,
    onContinue: (origin: String) -> Unit,
    viewModel: AddServerViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FormScaffold(stringResource(Res.string.add_server_title), onBack.takeIf { canGoBack }) {
        Text(stringResource(Res.string.add_server_intro), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = state.origin,
            onValueChange = viewModel::onOriginChange,
            label = { Text(stringResource(Res.string.add_server_origin)) },
            placeholder = { Text("https://teamcode.example.org") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { viewModel.check() }),
            modifier = Modifier.fillMaxWidth().testTag("origin"),
        )
        ErrorText(state.error)
        BusyRow(state.checking)
        val server = state.server
        if (server == null) {
            Button(onClick = viewModel::check, enabled = !state.checking && state.origin.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("check")) {
                Text(stringResource(Res.string.add_server_check))
            }
        } else {
            InfoCard(Modifier.testTag("server")) {
                Text(stringResource(Res.string.add_server_found, server.serverVersion), style = MaterialTheme.typography.titleMedium)
                Text(server.origin, style = MonospaceStyle)
                if (state.newServer) Text(stringResource(Res.string.add_server_new_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Button(onClick = { onContinue(server.origin) }, modifier = Modifier.fillMaxWidth().testTag("continue")) {
                Text(stringResource(Res.string.action_continue))
            }
        }
    }
}
