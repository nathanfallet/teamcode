package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ErrorText
import me.nathanfallet.teamcode.companion.presentation.components.FormScaffold
import me.nathanfallet.teamcode.companion.presentation.components.InfoCard
import me.nathanfallet.teamcode.companion.presentation.navigation.LoginRoute
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.viewmodels.LoginViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.add_server_found
import me.nathanfallet.teamcode.companion.resources.login_device_hint
import me.nathanfallet.teamcode.companion.resources.login_device_name
import me.nathanfallet.teamcode.companion.resources.login_password
import me.nathanfallet.teamcode.companion.resources.login_password_only_here
import me.nathanfallet.teamcode.companion.resources.login_submit
import me.nathanfallet.teamcode.companion.resources.login_title
import me.nathanfallet.teamcode.companion.resources.login_username
import me.nathanfallet.teamcode.companion.resources.password_change_confirm
import me.nathanfallet.teamcode.companion.resources.password_change_intro
import me.nathanfallet.teamcode.companion.resources.password_change_new
import me.nathanfallet.teamcode.companion.resources.password_change_submit
import me.nathanfallet.teamcode.companion.resources.password_change_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * A native form, never a web page: the origin stays visible the whole time, and the password goes only to it.
 * Field types let the system's password manager fill the username and password.
 */
@Composable
fun LoginScreen(
    route: LoginRoute,
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
    viewModel: LoginViewModel = koinViewModel { parametersOf(route) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedIn) { if (state.signedIn) onSignedIn() }
    val changing = state.passwordChange != null
    FormScaffold(stringResource(if (changing) Res.string.password_change_title else Res.string.login_title), onBack) {
        InfoCard(Modifier.testTag("login-server")) {
            state.server?.let { Text(stringResource(Res.string.add_server_found, it.serverVersion), style = MaterialTheme.typography.titleMedium) }
            Text(state.origin, style = MonospaceStyle)
        }
        if (state.server == null && !state.working) {
            ErrorText(state.error)
            TextButton(onClick = viewModel::loadServer) { Text(stringResource(Res.string.action_retry)) }
            return@FormScaffold
        }
        if (!changing) {
            OutlinedTextField(
                value = state.username, onValueChange = viewModel::onUsernameChange,
                label = { Text(stringResource(Res.string.login_username)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("username"),
            )
            PasswordField(state.password, viewModel::onPasswordChange, stringResource(Res.string.login_password), "password")
            OutlinedTextField(
                value = state.deviceName, onValueChange = viewModel::onDeviceNameChange,
                label = { Text(stringResource(Res.string.login_device_name)) },
                supportingText = { Text(stringResource(Res.string.login_device_hint)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("device"),
            )
            Text(stringResource(Res.string.login_password_only_here), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(Res.string.password_change_intro), style = MaterialTheme.typography.bodyMedium)
            PasswordField(state.newPassword, viewModel::onNewPasswordChange, stringResource(Res.string.password_change_new), "new-password")
            PasswordField(state.confirmation, viewModel::onConfirmationChange, stringResource(Res.string.password_change_confirm), "confirm-password")
        }
        ErrorText(state.error)
        BusyRow(state.working)
        Button(onClick = viewModel::submit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth().testTag("submit")) {
            Text(stringResource(if (changing) Res.string.password_change_submit else Res.string.login_submit))
        }
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, tag: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
