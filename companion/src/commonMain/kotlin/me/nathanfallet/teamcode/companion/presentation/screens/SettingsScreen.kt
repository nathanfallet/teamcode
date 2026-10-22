package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.FormScaffold
import me.nathanfallet.teamcode.companion.presentation.components.InfoCard
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SettingsViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.home_sign_in_again
import me.nathanfallet.teamcode.companion.resources.settings_about
import me.nathanfallet.teamcode.companion.resources.settings_accounts
import me.nathanfallet.teamcode.companion.resources.settings_active
import me.nathanfallet.teamcode.companion.resources.settings_add_account
import me.nathanfallet.teamcode.companion.resources.settings_app_version
import me.nathanfallet.teamcode.companion.resources.settings_device
import me.nathanfallet.teamcode.companion.resources.settings_device_rename
import me.nathanfallet.teamcode.companion.resources.settings_license
import me.nathanfallet.teamcode.companion.resources.settings_no_keyring
import me.nathanfallet.teamcode.companion.resources.settings_server_version
import me.nathanfallet.teamcode.companion.resources.settings_sign_out
import me.nathanfallet.teamcode.companion.resources.settings_signed_out
import me.nathanfallet.teamcode.companion.resources.settings_switch
import me.nathanfallet.teamcode.companion.resources.settings_theme
import me.nathanfallet.teamcode.companion.resources.settings_theme_dark
import me.nathanfallet.teamcode.companion.resources.settings_theme_light
import me.nathanfallet.teamcode.companion.resources.settings_theme_system
import me.nathanfallet.teamcode.companion.resources.settings_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    onSignInAgain: (Account) -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FormScaffold(stringResource(Res.string.settings_title), onBack) {
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("message")) }
        BusyRow(state.working)
        if (!state.persistentCredentials) Text(stringResource(Res.string.settings_no_keyring), color = MaterialTheme.colorScheme.tertiary)

        Text(stringResource(Res.string.settings_accounts), style = MaterialTheme.typography.titleMedium)
        for (account in state.accounts) {
            val active = account.id == state.activeAccountId
            InfoCard(Modifier.testTag("account-${account.username}")) {
                Text(account.username, style = MaterialTheme.typography.titleMedium)
                Text(account.origin, style = MonospaceStyle)
                Text(
                    when {
                        !account.signedIn -> stringResource(Res.string.settings_signed_out)
                        active -> stringResource(Res.string.settings_active)
                        else -> account.role.lowercase()
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!active) TextButton(onClick = { viewModel.switchTo(account) }) { Text(stringResource(Res.string.settings_switch)) }
                    if (!account.signedIn) TextButton(onClick = { onSignInAgain(account) }) { Text(stringResource(Res.string.home_sign_in_again)) }
                    TextButton(onClick = { viewModel.signOut(account) }, enabled = !state.working) { Text(stringResource(Res.string.settings_sign_out)) }
                }
            }
        }
        OutlinedButton(onClick = onAddAccount, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.settings_add_account)) }

        state.activeAccount?.takeIf { it.signedIn }?.let { account ->
            Text(stringResource(Res.string.settings_device), style = MaterialTheme.typography.titleMedium)
            var name by remember(account.id, account.deviceName) { mutableStateOf(account.deviceName) }
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { viewModel.renameDevice(name) }, enabled = !state.working && name.isNotBlank() && name != account.deviceName) {
                Text(stringResource(Res.string.settings_device_rename))
            }
        }

        Text(stringResource(Res.string.settings_theme), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((theme, label) in listOf(
                ThemePreference.SYSTEM to Res.string.settings_theme_system,
                ThemePreference.LIGHT to Res.string.settings_theme_light,
                ThemePreference.DARK to Res.string.settings_theme_dark,
            )) FilterChip(selected = state.theme == theme, onClick = { viewModel.setTheme(theme) }, label = { Text(stringResource(label)) })
        }

        Text(stringResource(Res.string.settings_about), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.settings_app_version, state.appVersion), style = MonospaceStyle)
        state.activeAccount?.let { Text(stringResource(Res.string.settings_server_version, it.serverVersion), style = MonospaceStyle) }
        Text(stringResource(Res.string.settings_license), style = MaterialTheme.typography.bodySmall)
    }
}

