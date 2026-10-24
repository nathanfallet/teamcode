package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.Plus
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.presentation.components.Avatar
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.Group
import me.nathanfallet.teamcode.companion.presentation.components.GroupDivider
import me.nathanfallet.teamcode.companion.presentation.components.GroupRow
import me.nathanfallet.teamcode.companion.presentation.components.LargeTitle
import me.nathanfallet.teamcode.companion.presentation.components.SectionTitle
import me.nathanfallet.teamcode.companion.presentation.components.Segmented
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.components.dismissKeyboardOnTap
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.components.hostOf
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SettingsViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.account_other
import me.nathanfallet.teamcode.companion.resources.account_signed_in
import me.nathanfallet.teamcode.companion.resources.account_title
import me.nathanfallet.teamcode.companion.resources.accounts_signed_out
import me.nathanfallet.teamcode.companion.resources.home_sign_in_again
import me.nathanfallet.teamcode.companion.resources.login_device_hint
import me.nathanfallet.teamcode.companion.resources.settings_about
import me.nathanfallet.teamcode.companion.resources.settings_add_account
import me.nathanfallet.teamcode.companion.resources.settings_app_version
import me.nathanfallet.teamcode.companion.resources.settings_device
import me.nathanfallet.teamcode.companion.resources.settings_device_rename
import me.nathanfallet.teamcode.companion.resources.settings_license
import me.nathanfallet.teamcode.companion.resources.accounts_cli
import me.nathanfallet.teamcode.companion.resources.account_cli_hint
import me.nathanfallet.teamcode.companion.resources.account_cli_sign_out
import me.nathanfallet.teamcode.companion.resources.settings_no_keyring
import me.nathanfallet.teamcode.companion.resources.settings_server_version
import me.nathanfallet.teamcode.companion.resources.settings_sign_out
import me.nathanfallet.teamcode.companion.resources.settings_switch
import me.nathanfallet.teamcode.companion.resources.settings_theme
import me.nathanfallet.teamcode.companion.resources.settings_theme_dark
import me.nathanfallet.teamcode.companion.resources.settings_theme_light
import me.nathanfallet.teamcode.companion.resources.settings_theme_system
import me.nathanfallet.teamcode.companion.resources.github_row_hint
import me.nathanfallet.teamcode.companion.resources.github_title
import me.nathanfallet.teamcode.companion.resources.subscriptions_row_hint
import me.nathanfallet.teamcode.companion.resources.subscriptions_section
import me.nathanfallet.teamcode.companion.resources.subscriptions_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** The Account tab: the active account, the others, this device's name, the theme, versions. */
@Composable
fun AccountScreen(
    onAddAccount: () -> Unit,
    onSignInAgain: (Account) -> Unit,
    onSwitchAccount: () -> Unit,
    onOpenSubscriptions: (Account) -> Unit = {},
    onOpenGitHub: (Account) -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TeamCode.colors
    val active = state.activeAccount
    Column(Modifier.fillMaxSize().background(colors.background).dismissKeyboardOnTap().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LargeTitle(stringResource(Res.string.account_title))
            state.message?.let { Banner(it, Tone.NEUTRAL, Modifier.testTag("message"), icon = null) }
            if (!state.persistentCredentials) Banner(stringResource(Res.string.settings_no_keyring), Tone.WARN)
            BusyRow(state.working)

            active?.let { account ->
                Section(stringResource(Res.string.account_signed_in)) {
                    Group(Modifier.testTag("account-${account.username}")) {
                        GroupRow(Modifier.padding(vertical = 4.dp)) {
                            Avatar(account.username, size = 40.dp, dimmed = !account.signedIn)
                            Column(Modifier.weight(1f)) {
                                Text(account.username, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text(account.origin, style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (account.usedByCli) Chip(stringResource(Res.string.accounts_cli), tone = Tone.ACCENT, modifier = Modifier.testTag("cli-badge"))
                            if (account.signedIn) Chip(account.role.lowercase()) else Chip(stringResource(Res.string.accounts_signed_out), tone = Tone.WARN)
                        }
                        if (account.usedByCli) state.cliConfigPath?.let { path ->
                            GroupDivider()
                            GroupRow { Text(stringResource(Res.string.account_cli_hint, path), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.testTag("cli-config-path")) }
                        }
                        GroupDivider()
                        GroupRow {
                            if (account.signedIn) TcButton(stringResource(Res.string.settings_switch), onClick = onSwitchAccount, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f).testTag("switch-account"))
                            else TcButton(stringResource(Res.string.home_sign_in_again), onClick = { onSignInAgain(account) }, modifier = Modifier.weight(1f).testTag("sign-in-again"))
                            TcButton(stringResource(Res.string.settings_sign_out), onClick = { viewModel.signOut(account) }, enabled = !state.working, kind = ButtonKind.DANGER,
                                modifier = Modifier.weight(1f).testTag("sign-out"))
                        }
                        // Said before the tap: signing this account out runs teamcode logout as well.
                        if (account.usedByCli) GroupRow {
                            Text(stringResource(Res.string.account_cli_sign_out), color = colors.muted, fontSize = 12.5.sp, modifier = Modifier.testTag("cli-sign-out-warning"))
                        }
                    }
                }
            }

            active?.takeIf { it.signedIn }?.let { account ->
                Section(stringResource(Res.string.subscriptions_section)) {
                    Group {
                        GroupRow(Modifier.testTag("subscriptions"), onClick = { onOpenSubscriptions(account) }) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(Res.string.subscriptions_title), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(stringResource(Res.string.subscriptions_row_hint), color = colors.muted, fontSize = 12.5.sp)
                            }
                            Icon(PhosphorIcons.Regular.CaretRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            active?.takeIf { it.signedIn }?.let { account ->
                Section(stringResource(Res.string.github_title)) {
                    Group {
                        GroupRow(Modifier.testTag("github"), onClick = { onOpenGitHub(account) }) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(Res.string.github_title), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(stringResource(Res.string.github_row_hint), color = colors.muted, fontSize = 12.5.sp)
                            }
                            Icon(PhosphorIcons.Regular.CaretRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Section(stringResource(Res.string.account_other)) {
                Group {
                    val others = state.accounts.filter { it.id != state.activeAccountId }
                    for (account in others) {
                        GroupRow(Modifier.testTag("account-${account.username}"), onClick = { viewModel.switchTo(account) }) {
                            Column(Modifier.weight(1f)) {
                                Text(account.username, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(hostOf(account.origin), style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (account.usedByCli) Chip(stringResource(Res.string.accounts_cli), tone = Tone.ACCENT)
                            if (account.signedIn) Chip(account.role.lowercase()) else Chip(stringResource(Res.string.accounts_signed_out), tone = Tone.WARN)
                        }
                        GroupDivider()
                    }
                    GroupRow(Modifier.testTag("add-account"), onClick = onAddAccount) {
                        Icon(PhosphorIcons.Regular.Plus, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(20.dp))
                        Text(stringResource(Res.string.settings_add_account), color = colors.accentText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
            }

            active?.takeIf { it.signedIn }?.let { account ->
                Section(stringResource(Res.string.settings_device)) {
                    Group {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            var name by remember(account.id, account.deviceName) { mutableStateOf(account.deviceName) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                TcTextField(name, { name = it }, modifier = Modifier.weight(1f), tag = "device-name", minHeight = 44.dp,
                                    onSubmit = { if (!state.working && name.isNotBlank() && name != account.deviceName) viewModel.renameDevice(name) })
                                TcButton(stringResource(Res.string.settings_device_rename), onClick = { viewModel.renameDevice(name) }, small = true,
                                    enabled = !state.working && name.isNotBlank() && name != account.deviceName, modifier = Modifier.testTag("rename"))
                            }
                            Text(stringResource(Res.string.login_device_hint), color = colors.muted, fontSize = 12.5.sp)
                        }
                    }
                }
            }

            Section(stringResource(Res.string.settings_theme)) {
                Segmented(
                    listOf(ThemePreference.SYSTEM to stringResource(Res.string.settings_theme_system), ThemePreference.LIGHT to stringResource(Res.string.settings_theme_light), ThemePreference.DARK to stringResource(Res.string.settings_theme_dark)),
                    selected = state.theme, onSelect = viewModel::setTheme, modifier = Modifier.fillMaxWidth(), tag = { "theme-${it.name.lowercase()}" },
                )
            }

            Section(stringResource(Res.string.settings_about)) {
                Group {
                    VersionRow(stringResource(Res.string.settings_app_version), state.appVersion)
                    active?.let {
                        GroupDivider()
                        VersionRow(stringResource(Res.string.settings_server_version), it.serverVersion)
                    }
                    GroupDivider()
                    GroupRow { Text(stringResource(Res.string.settings_license), color = colors.muted, fontSize = 12.5.sp) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        content()
    }
}

@Composable
private fun VersionRow(label: String, value: String) {
    GroupRow {
        Text(label, color = TeamCode.colors.muted, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
        Text(value, style = MonospaceStyle, color = TeamCode.colors.text)
    }
}
