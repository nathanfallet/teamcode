package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.Cloud
import com.adamglin.phosphoricons.regular.Desktop
import me.nathanfallet.teamcode.companion.domain.services.LocalAvailability
import me.nathanfallet.teamcode.companion.domain.services.LocalResumeBlock
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.ErrorText
import me.nathanfallet.teamcode.companion.presentation.components.Group
import me.nathanfallet.teamcode.companion.presentation.components.GroupDivider
import me.nathanfallet.teamcode.companion.presentation.components.GroupRow
import me.nathanfallet.teamcode.companion.presentation.components.IconTile
import me.nathanfallet.teamcode.companion.presentation.components.SectionTitle
import me.nathanfallet.teamcode.companion.presentation.components.Segmented
import me.nathanfallet.teamcode.companion.presentation.components.SheetHeader
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcSheet
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.LocalSessionViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_cancel
import me.nathanfallet.teamcode.companion.resources.local_session_agent
import me.nathanfallet.teamcode.companion.resources.local_session_agent_claude
import me.nathanfallet.teamcode.companion.resources.local_session_agent_codex
import me.nathanfallet.teamcode.companion.resources.local_session_agent_default
import me.nathanfallet.teamcode.companion.resources.local_session_bypass
import me.nathanfallet.teamcode.companion.resources.local_session_bypass_hint_off
import me.nathanfallet.teamcode.companion.resources.local_session_bypass_warning
import me.nathanfallet.teamcode.companion.resources.local_session_check_again
import me.nathanfallet.teamcode.companion.resources.local_session_checking
import me.nathanfallet.teamcode.companion.resources.local_session_choose_folder
import me.nathanfallet.teamcode.companion.resources.local_session_cli_command
import me.nathanfallet.teamcode.companion.resources.local_session_cli_missing
import me.nathanfallet.teamcode.companion.resources.local_session_cli_missing_install
import me.nathanfallet.teamcode.companion.resources.local_session_cli_old
import me.nathanfallet.teamcode.companion.resources.local_session_folder
import me.nathanfallet.teamcode.companion.resources.local_session_folder_hint
import me.nathanfallet.teamcode.companion.resources.local_session_install_cli
import me.nathanfallet.teamcode.companion.resources.local_session_message_failed
import me.nathanfallet.teamcode.companion.resources.local_session_no_cli_account
import me.nathanfallet.teamcode.companion.resources.local_session_open
import me.nathanfallet.teamcode.companion.resources.local_session_other_account
import me.nathanfallet.teamcode.companion.resources.local_session_start
import me.nathanfallet.teamcode.companion.resources.local_session_title
import me.nathanfallet.teamcode.companion.resources.local_session_unsupported
import me.nathanfallet.teamcode.companion.resources.local_session_resume_cli_old
import me.nathanfallet.teamcode.companion.resources.local_session_resume_daemon_offline
import me.nathanfallet.teamcode.companion.resources.local_session_resume_daemon_old
import me.nathanfallet.teamcode.companion.resources.local_session_resume_hint
import me.nathanfallet.teamcode.companion.resources.local_session_resume_loading
import me.nathanfallet.teamcode.companion.resources.local_session_resume_platform
import me.nathanfallet.teamcode.companion.resources.local_session_resume_server
import me.nathanfallet.teamcode.companion.resources.new_session_resume
import me.nathanfallet.teamcode.companion.resources.new_session_cloud
import me.nathanfallet.teamcode.companion.resources.new_session_cloud_hint
import me.nathanfallet.teamcode.companion.resources.new_session_effort
import me.nathanfallet.teamcode.companion.resources.new_session_here
import me.nathanfallet.teamcode.companion.resources.new_session_here_hint
import me.nathanfallet.teamcode.companion.resources.new_session_message
import me.nathanfallet.teamcode.companion.resources.new_session_message_hint
import me.nathanfallet.teamcode.companion.resources.new_session_model
import me.nathanfallet.teamcode.companion.resources.new_session_name
import me.nathanfallet.teamcode.companion.resources.new_session_overrides
import me.nathanfallet.teamcode.companion.resources.new_session_title
import me.nathanfallet.teamcode.companion.resources.new_session_where
import org.jetbrains.compose.resources.stringResource

/** The desktop's first step of a new session: on this device (a folder) or in the cloud (a profile). */
@Composable
fun NewSessionChooserSheet(onHere: () -> Unit, onCloud: () -> Unit, onDismiss: () -> Unit) {
    TcSheet(onDismiss) {
        SheetHeader(
            stringResource(Res.string.new_session_title),
            centered = true,
            leading = { TcButton(stringResource(Res.string.action_cancel), onClick = onDismiss, kind = ButtonKind.GHOST, small = true) },
        )
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(Res.string.new_session_where))
            Group {
                ChooserRow(PhosphorIcons.Regular.Desktop, stringResource(Res.string.new_session_here), stringResource(Res.string.new_session_here_hint), "new-session-here", onHere)
                GroupDivider()
                ChooserRow(PhosphorIcons.Regular.Cloud, stringResource(Res.string.new_session_cloud), stringResource(Res.string.new_session_cloud_hint), "new-session-cloud", onCloud)
            }
        }
    }
}

@Composable
private fun ChooserRow(icon: ImageVector, title: String, hint: String, tag: String, onClick: () -> Unit) {
    val colors = TeamCode.colors
    GroupRow(Modifier.heightIn(min = 64.dp).testTag(tag), onClick = onClick) {
        IconTile(icon)
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(hint, color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        Icon(PhosphorIcons.Regular.CaretRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
    }
}

/**
 * "New session on this device": what the CLI needs first when something is missing (the CLI itself, a newer one, its
 * sign-in), then the folder, the agent, a name, a first message and the permission mode. [onStarted] opens the session.
 */
@Composable
fun LocalSessionSheet(viewModel: LocalSessionViewModel, onDismiss: () -> Unit, onStarted: (sessionId: String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // A first message that did not reach the session is said here before the session opens, so it can be sent again.
    LaunchedEffect(state.started) { state.started?.takeIf { !state.messageFailed }?.let { onStarted(it.sessionId) } }
    val colors = TeamCode.colors
    TcSheet(onDismiss) {
        SheetHeader(
            stringResource(Res.string.local_session_title),
            centered = true,
            leading = { TcButton(stringResource(Res.string.action_cancel), onClick = onDismiss, kind = ButtonKind.GHOST, small = true, modifier = Modifier.testTag("local-session-cancel")) },
        )
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp).testTag("local-session-form"),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when (val availability = state.availability) {
                null -> if (state.error == null) {
                    Text(stringResource(Res.string.local_session_checking), color = colors.muted, fontSize = 14.sp)
                    BusyRow(true)
                }
                LocalAvailability.Ready -> Form(viewModel)
                else -> Blocked(availability, state.installing, state.installOutput, viewModel::check, viewModel::installCli)
            }
            state.started?.takeIf { state.messageFailed }?.let { started ->
                Banner(stringResource(Res.string.local_session_message_failed), Tone.WARN, Modifier.testTag("local-message-failed"))
                TcButton(stringResource(Res.string.local_session_open), onClick = { onStarted(started.sessionId) }, modifier = Modifier.testTag("local-open"))
            }
            ErrorText(state.error)
            if (state.error != null && state.availability == null) TcButton(stringResource(Res.string.local_session_check_again), onClick = viewModel::check, kind = ButtonKind.SECONDARY)
        }
        if (state.availability == LocalAvailability.Ready) Box(Modifier.fillMaxWidth().background(colors.line).padding(top = 1.dp)) {
            Box(Modifier.fillMaxWidth().background(colors.panelAlt).navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp)) {
                TcButton(stringResource(Res.string.local_session_start), onClick = viewModel::create, enabled = state.canStart, busy = state.starting, modifier = Modifier.testTag("local-start"))
            }
        }
    }
}

@Composable
private fun Form(viewModel: LocalSessionViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TeamCode.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.local_session_folder), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.directory.ifEmpty { "—" }, style = MonospaceStyle, color = if (state.directory.isEmpty()) colors.muted else colors.text, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("local-directory"))
            TcButton(stringResource(Res.string.local_session_choose_folder), onClick = viewModel::chooseDirectory, kind = ButtonKind.SECONDARY, small = true,
                modifier = Modifier.testTag("local-choose-folder"))
        }
        Text(stringResource(Res.string.local_session_folder_hint), color = colors.muted, fontSize = 12.5.sp)
    }
    LocalResumeFields(viewModel)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.local_session_agent), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Segmented(listOf("claude" to stringResource(Res.string.local_session_agent_claude), "codex" to stringResource(Res.string.local_session_agent_codex)),
            selected = state.agent, onSelect = viewModel::onAgent, modifier = Modifier.fillMaxWidth(), tag = { "local-agent-$it" })
    }
    TcTextField(state.name, viewModel::onName, label = stringResource(Res.string.new_session_name), tag = "local-name", onSubmit = { if (state.canStart) viewModel.create() })
    TcTextField(state.message, viewModel::onMessage, label = stringResource(Res.string.new_session_message), placeholder = stringResource(Res.string.new_session_message_hint),
        singleLine = false, minLines = 3, maxLines = 8, tag = "local-message", onSubmit = { if (state.canStart) viewModel.create() })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(Res.string.new_session_overrides))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TcTextField(state.model, viewModel::onModel, label = stringResource(Res.string.new_session_model), placeholder = stringResource(Res.string.local_session_agent_default),
                mono = true, tag = "local-model", modifier = Modifier.weight(1f))
            TcTextField(state.effort, viewModel::onEffort, label = stringResource(Res.string.new_session_effort), placeholder = stringResource(Res.string.local_session_agent_default),
                mono = true, tag = "local-effort", modifier = Modifier.weight(1f))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.local_session_bypass), color = colors.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Switch(state.bypassPermissions, viewModel::onBypass, modifier = Modifier.testTag("local-bypass"))
        }
        if (state.bypassPermissions) Banner(stringResource(Res.string.local_session_bypass_warning), Tone.WARN, Modifier.testTag("local-bypass-warning"))
        else Text(stringResource(Res.string.local_session_bypass_hint_off), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

/**
 * "Resume a session" under the folder, as on another device: None by default, the folder's Claude Code conversations
 * on this computer once opened. When this computer cannot continue one (its TeamCode service off or too old, Windows,
 * an older server or CLI), the row is disabled and says why, with Check again.
 */
@Composable
private fun LocalResumeFields(viewModel: LocalSessionViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.showsResume) return
    val colors = TeamCode.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("resume")) {
        Text(stringResource(Res.string.new_session_resume), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val block = state.resumeBlock
        if (block != null) {
            val reason = when (block) {
                LocalResumeBlock.SERVER -> Res.string.local_session_resume_server
                LocalResumeBlock.PLATFORM -> Res.string.local_session_resume_platform
                LocalResumeBlock.DAEMON_OFFLINE -> Res.string.local_session_resume_daemon_offline
                LocalResumeBlock.DAEMON_TOO_OLD -> Res.string.local_session_resume_daemon_old
                LocalResumeBlock.CLI_TOO_OLD -> Res.string.local_session_resume_cli_old
            }
            Text(stringResource(reason), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.testTag("resume-blocked"))
            if (block != LocalResumeBlock.PLATFORM && block != LocalResumeBlock.SERVER)
                TcButton(stringResource(Res.string.local_session_check_again), onClick = viewModel::onResumeRetry, kind = ButtonKind.SECONDARY, small = true,
                    busy = state.loadingResumable, modifier = Modifier.testTag("resume-check-again"))
            return@Column
        }
        ResumePicker(state.resumable, state.resumeId, state.resumeOpen, state.loadingResumable, state.resumableError, state.resumableNext != null,
            enabled = !state.starting, loadingText = stringResource(Res.string.local_session_resume_loading),
            onOpen = viewModel::onResumeOpen, onRetry = viewModel::onResumeRetry, onMore = viewModel::onResumeMore, onPick = viewModel::onResume)
        Text(stringResource(Res.string.local_session_resume_hint), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun Blocked(availability: LocalAvailability, installing: Boolean, installOutput: List<String>, onCheck: () -> Unit, onInstall: () -> Unit) {
    val colors = TeamCode.colors
    val canInstall = availability is LocalAvailability.CliMissing && availability.canInstall
    val (text, command) = when (availability) {
        is LocalAvailability.CliMissing -> stringResource(if (canInstall) Res.string.local_session_cli_missing_install else Res.string.local_session_cli_missing) to availability.installCommand
        is LocalAvailability.CliTooOld -> stringResource(Res.string.local_session_cli_old, availability.found, availability.required) to availability.updateCommand
        is LocalAvailability.OtherAccount -> (availability.cliAccount?.let { stringResource(Res.string.local_session_other_account, it) }
            ?: stringResource(Res.string.local_session_no_cli_account)) to null
        LocalAvailability.ServerUnsupported -> stringResource(Res.string.local_session_unsupported) to null
        LocalAvailability.Ready -> return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("local-blocked")) {
        Text(text, color = colors.text, fontSize = 14.sp, lineHeight = 20.sp)
        if (canInstall) TcButton(stringResource(Res.string.local_session_install_cli), onClick = onInstall, busy = installing, enabled = !installing,
            modifier = Modifier.testTag("local-install-cli"))
        if (installOutput.isNotEmpty()) SelectionContainer {
            // The last lines, like a terminal: the installer's progress and its final message.
            Text(installOutput.takeLast(12).joinToString("\n"), style = MonospaceStyle, color = colors.muted, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(colors.panelAlt).padding(12.dp).testTag("local-install-output"))
        }
        command?.let {
            if (canInstall) Text(stringResource(Res.string.local_session_cli_command), color = colors.muted, fontSize = 12.5.sp)
            SelectionContainer {
                Text(it, style = MonospaceStyle, color = colors.text, modifier = Modifier.fillMaxWidth().background(colors.panelAlt).padding(12.dp).testTag("local-command"))
            }
        }
        TcButton(stringResource(Res.string.local_session_check_again), onClick = onCheck, kind = ButtonKind.SECONDARY, enabled = !installing, modifier = Modifier.testTag("local-check-again"))
    }
}
