package me.nathanfallet.teamcode.companion.presentation.screens

import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.HardDrives
import com.adamglin.phosphoricons.regular.Lock
import com.adamglin.phosphoricons.regular.User
import me.nathanfallet.teamcode.companion.presentation.components.BackLink
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.FormPage
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.navigation.LoginRoute
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.LoginViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_back
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.login_back_server
import me.nathanfallet.teamcode.companion.resources.login_device_hint
import me.nathanfallet.teamcode.companion.resources.login_device_name
import me.nathanfallet.teamcode.companion.resources.login_password
import me.nathanfallet.teamcode.companion.resources.login_password_only_here
import me.nathanfallet.teamcode.companion.resources.login_signing_in_to
import me.nathanfallet.teamcode.companion.resources.login_submit
import me.nathanfallet.teamcode.companion.resources.login_title
import me.nathanfallet.teamcode.companion.resources.login_use_for_cli
import me.nathanfallet.teamcode.companion.resources.login_use_for_cli_hint
import me.nathanfallet.teamcode.companion.resources.login_username
import me.nathanfallet.teamcode.companion.resources.password_change_confirm
import me.nathanfallet.teamcode.companion.resources.password_change_hint
import me.nathanfallet.teamcode.companion.resources.password_change_intro
import me.nathanfallet.teamcode.companion.resources.password_change_new
import me.nathanfallet.teamcode.companion.resources.password_change_submit
import me.nathanfallet.teamcode.companion.resources.password_change_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * A native form, never a web page: the origin stays visible the whole time, and the password goes only to it.
 * Autofill content types let the system's password manager fill the username and password, and save a new one.
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
    val unreachable = state.server == null && !state.working
    val colors = TeamCode.colors
    // Enter in any field submits once the form is complete (viewModel.submit checks it too).
    val submit = { viewModel.submit() }
    FormPage(footer = {
        if (unreachable) return@FormPage
        if (!changing) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(PhosphorIcons.Regular.Lock, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
            Text(stringResource(Res.string.login_password_only_here), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        TcButton(stringResource(if (changing) Res.string.password_change_submit else Res.string.login_submit), onClick = viewModel::submit, enabled = state.canSubmit, busy = state.working,
            modifier = Modifier.testTag("submit"))
    }) {
        BackLink(stringResource(if (changing || route.username != null) Res.string.action_back else Res.string.login_back_server), onBack)
        PageHeading(stringResource(if (changing) Res.string.password_change_title else Res.string.login_title), if (changing) stringResource(Res.string.password_change_intro) else null)
        if (changing) {
            ServerCard(PhosphorIcons.Regular.User, state.username, state.origin, Modifier.testTag("login-server"))
        } else {
            ServerCard(PhosphorIcons.Regular.HardDrives, stringResource(Res.string.login_signing_in_to), state.origin, Modifier.testTag("login-server"), mutedTitle = true)
        }
        if (unreachable) {
            state.error?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("error")) }
            TcButton(stringResource(Res.string.action_retry), onClick = viewModel::loadServer, kind = ButtonKind.SECONDARY)
            return@FormPage
        }
        if (!changing) {
            TcTextField(
                value = state.username, onValueChange = viewModel::onUsernameChange, label = stringResource(Res.string.login_username),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                tag = "username", contentType = ContentType.Username, onSubmit = submit,
            )
            PasswordField(state.password, viewModel::onPasswordChange, stringResource(Res.string.login_password), "password", ContentType.Password, onSubmit = submit)
            TcTextField(
                value = state.deviceName, onValueChange = viewModel::onDeviceNameChange, label = stringResource(Res.string.login_device_name),
                hint = stringResource(Res.string.login_device_hint), tag = "device", onSubmit = submit,
            )
            if (state.cliLinkOffered) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.login_use_for_cli), color = colors.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(Res.string.login_use_for_cli_hint), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
                }
                Switch(state.useForCli, viewModel::onUseForCliChange, modifier = Modifier.testTag("use-for-cli"))
            }
        } else {
            PasswordField(state.newPassword, viewModel::onNewPasswordChange, stringResource(Res.string.password_change_new), "new-password", ContentType.NewPassword,
                hint = stringResource(Res.string.password_change_hint), onSubmit = submit)
            PasswordField(state.confirmation, viewModel::onConfirmationChange, stringResource(Res.string.password_change_confirm), "confirm-password", ContentType.NewPassword, onSubmit = submit)
        }
        state.error?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("error")) }
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, tag: String, type: ContentType, hint: String? = null, onSubmit: (() -> Unit)? = null) {
    TcTextField(
        value = value, onValueChange = onChange, label = label, hint = hint, password = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        tag = tag, contentType = type, onSubmit = onSubmit,
    )
}
