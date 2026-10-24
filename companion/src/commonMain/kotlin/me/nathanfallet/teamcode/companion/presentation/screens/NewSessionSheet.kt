package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.ErrorText
import me.nathanfallet.teamcode.companion.presentation.components.Group
import me.nathanfallet.teamcode.companion.presentation.components.GroupDivider
import me.nathanfallet.teamcode.companion.presentation.components.SectionTitle
import me.nathanfallet.teamcode.companion.presentation.components.SheetHeader
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcSheet
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.domain.models.HostDevice
import me.nathanfallet.teamcode.companion.domain.models.PermissionMode
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.Segmented
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.components.systemOf
import me.nathanfallet.teamcode.companion.presentation.viewmodels.NewSessionUiState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.NewSessionViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionTarget
import me.nathanfallet.teamcode.companion.resources.local_session_agent
import me.nathanfallet.teamcode.companion.resources.local_session_agent_claude
import me.nathanfallet.teamcode.companion.resources.local_session_agent_codex
import me.nathanfallet.teamcode.companion.resources.local_session_agent_default
import me.nathanfallet.teamcode.companion.resources.new_session_device
import me.nathanfallet.teamcode.companion.resources.new_session_device_this
import me.nathanfallet.teamcode.companion.resources.new_session_folder
import me.nathanfallet.teamcode.companion.resources.new_session_folder_hint
import me.nathanfallet.teamcode.companion.resources.new_session_no_device
import me.nathanfallet.teamcode.companion.resources.new_session_no_folder
import me.nathanfallet.teamcode.companion.resources.new_session_permissions
import me.nathanfallet.teamcode.companion.resources.new_session_permissions_ask
import me.nathanfallet.teamcode.companion.resources.new_session_permissions_ask_hint
import me.nathanfallet.teamcode.companion.resources.new_session_permissions_bypass
import me.nathanfallet.teamcode.companion.resources.new_session_permissions_bypass_warning
import me.nathanfallet.teamcode.companion.resources.new_session_refresh_devices
import me.nathanfallet.teamcode.companion.resources.new_session_start_on_device
import me.nathanfallet.teamcode.companion.resources.new_session_target_cloud
import me.nathanfallet.teamcode.companion.resources.new_session_target_device
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_cancel
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.new_session_create
import me.nathanfallet.teamcode.companion.resources.new_session_effort
import me.nathanfallet.teamcode.companion.resources.new_session_message
import me.nathanfallet.teamcode.companion.resources.new_session_message_hint
import me.nathanfallet.teamcode.companion.resources.new_session_model
import me.nathanfallet.teamcode.companion.resources.new_session_name
import me.nathanfallet.teamcode.companion.resources.new_session_no_profile
import me.nathanfallet.teamcode.companion.resources.new_session_overrides
import me.nathanfallet.teamcode.companion.resources.new_session_profile
import me.nathanfallet.teamcode.companion.resources.new_session_profile_default
import me.nathanfallet.teamcode.companion.resources.new_session_title
import org.jetbrains.compose.resources.stringResource

/**
 * The new-session form in a sheet over the Sessions tab: in the cloud (a profile) or, when the server offers it, on one
 * of the member's online devices (a folder it offers, the agent, the permission mode); then a name, a first message and
 * optional overrides.
 */
@Composable
fun NewSessionSheet(viewModel: NewSessionViewModel, onDismiss: () -> Unit, onCreated: (CloudSession) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.created) { state.created?.let(onCreated) }
    val colors = TeamCode.colors
    val device = state.devices?.firstOrNull { it.id == state.deviceId }
    TcSheet(onDismiss) {
        SheetHeader(
            stringResource(Res.string.new_session_title),
            centered = true,
            leading = { TcButton(stringResource(Res.string.action_cancel), onClick = onDismiss, kind = ButtonKind.GHOST, small = true, modifier = Modifier.testTag("new-session-cancel")) },
        )
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp).testTag("new-session-form"),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (state.onDevices) {
                Segmented(
                    listOf(SessionTarget.CLOUD to stringResource(Res.string.new_session_target_cloud), SessionTarget.DEVICE to stringResource(Res.string.new_session_target_device)),
                    selected = state.target, onSelect = viewModel::onTarget, modifier = Modifier.fillMaxWidth(), tag = { "target-${it.name.lowercase()}" },
                )
            }
            when (state.target) {
                SessionTarget.CLOUD -> CloudFields(state, viewModel)
                SessionTarget.DEVICE -> DeviceFields(state, viewModel, device?.let { deviceName(it) })
            }
            TcTextField(state.name, viewModel::onName, label = stringResource(Res.string.new_session_name), tag = "name")
            if (state.acceptsMessage) {
                TcTextField(state.message, viewModel::onMessage, label = stringResource(Res.string.new_session_message), placeholder = stringResource(Res.string.new_session_message_hint),
                    singleLine = false, minLines = 3, maxLines = 8, tag = "message")
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(Res.string.new_session_overrides))
                val default = stringResource(if (state.target == SessionTarget.CLOUD) Res.string.new_session_profile_default else Res.string.local_session_agent_default)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TcTextField(state.model, viewModel::onModel, label = stringResource(Res.string.new_session_model), placeholder = default,
                        mono = true, tag = "model", modifier = Modifier.weight(1f))
                    TcTextField(state.effort, viewModel::onEffort, label = stringResource(Res.string.new_session_effort), placeholder = default,
                        mono = true, tag = "effort", modifier = Modifier.weight(1f))
                }
            }
            if (state.target == SessionTarget.DEVICE) PermissionFields(state, viewModel, device?.let { deviceName(it) })
            ErrorText(state.error)
            if (state.error != null && state.target == SessionTarget.CLOUD && state.profiles.isEmpty()) TcButton(stringResource(Res.string.action_retry), onClick = viewModel::reload, kind = ButtonKind.SECONDARY)
        }
        Box(Modifier.fillMaxWidth().background(colors.line).padding(top = 1.dp)) {
            Box(Modifier.fillMaxWidth().background(colors.panelAlt).navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp)) {
                val label = if (state.target == SessionTarget.DEVICE && device != null) stringResource(Res.string.new_session_start_on_device, deviceName(device))
                    else stringResource(Res.string.new_session_create)
                TcButton(label, onClick = viewModel::create, enabled = state.canCreate, busy = state.creating, modifier = Modifier.testTag("create"))
            }
        }
    }
}

@Composable
private fun CloudFields(state: NewSessionUiState, viewModel: NewSessionViewModel) {
    val colors = TeamCode.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.new_session_profile), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        BusyRow(state.loadingProfiles)
        if (!state.loadingProfiles && state.profiles.isEmpty() && state.error == null) {
            Text(stringResource(Res.string.new_session_no_profile), color = colors.muted, fontSize = 14.sp)
        }
        if (state.profiles.isNotEmpty()) Group(Modifier.selectableGroup()) {
            state.profiles.forEachIndexed { index, profile ->
                if (index > 0) GroupDivider()
                ProfileOption(profile, selected = state.profileId == profile.id) { viewModel.onProfile(profile.id) }
            }
        }
    }
}

/** The online devices only, then the folders the chosen one offers: never a path typed here. */
@Composable
private fun DeviceFields(state: NewSessionUiState, viewModel: NewSessionViewModel, deviceName: String?) {
    val colors = TeamCode.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.new_session_device), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TcButton(stringResource(Res.string.new_session_refresh_devices), onClick = viewModel::reloadDevices, kind = ButtonKind.GHOST, small = true,
                enabled = !state.loadingDevices && !state.creating, modifier = Modifier.testTag("refresh-devices"))
        }
        BusyRow(state.loadingDevices)
        val devices = state.devices
        if (!state.loadingDevices && devices != null && devices.isEmpty()) {
            Text(stringResource(Res.string.new_session_no_device), color = colors.muted, fontSize = 14.sp, modifier = Modifier.testTag("no-device"))
        }
        if (!devices.isNullOrEmpty()) Group(Modifier.selectableGroup()) {
            devices.forEachIndexed { index, device ->
                if (index > 0) GroupDivider()
                Option(deviceName(device), systemOf(device.platform, device.osVersion), selected = state.deviceId == device.id, tag = "device-${device.id}") { viewModel.onDevice(device.id) }
            }
        }
    }
    if (state.deviceId != null) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.new_session_folder), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        BusyRow(state.loadingFolders)
        val folders = state.folders
        if (!state.loadingFolders && folders != null && folders.isEmpty() && state.error == null) {
            Text(stringResource(Res.string.new_session_no_folder), color = colors.muted, fontSize = 14.sp, modifier = Modifier.testTag("no-folder"))
        }
        if (!folders.isNullOrEmpty()) {
            Group(Modifier.selectableGroup()) {
                folders.forEachIndexed { index, folder ->
                    if (index > 0) GroupDivider()
                    Option(folder.name, folder.label?.takeIf { it != folder.name }, selected = state.folderId == folder.id, tag = "folder-${folder.id}") { viewModel.onFolder(folder.id) }
                }
            }
            Text(stringResource(Res.string.new_session_folder_hint), color = colors.muted, fontSize = 12.5.sp)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.local_session_agent), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Segmented(listOf("claude" to stringResource(Res.string.local_session_agent_claude), "codex" to stringResource(Res.string.local_session_agent_codex)),
            selected = state.agent, onSelect = viewModel::onAgent, modifier = Modifier.fillMaxWidth(), tag = { "agent-$it" })
    }
}

/** Ask or bypass, chosen at each launch; the last choice comes back the next time. */
@Composable
private fun PermissionFields(state: NewSessionUiState, viewModel: NewSessionViewModel, deviceName: String?) {
    val colors = TeamCode.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.new_session_permissions), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Segmented(listOf(PermissionMode.ASK to stringResource(Res.string.new_session_permissions_ask), PermissionMode.BYPASS to stringResource(Res.string.new_session_permissions_bypass)),
            selected = state.permissionMode, onSelect = viewModel::onPermissionMode, modifier = Modifier.fillMaxWidth(), tag = { "permissions-${it.wire}" })
        if (state.permissionMode == PermissionMode.BYPASS) {
            Banner(stringResource(Res.string.new_session_permissions_bypass_warning, deviceName ?: stringResource(Res.string.new_session_device).lowercase()), Tone.WARN, Modifier.testTag("bypass-warning"))
        } else Text(stringResource(Res.string.new_session_permissions_ask_hint), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun deviceName(device: HostDevice): String = if (device.current) stringResource(Res.string.new_session_device_this) else device.name

@Composable
private fun Option(title: String, subtitle: String?, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = TeamCode.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp).border(1.5.dp, if (selected) colors.accent else colors.line, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).background(colors.accent, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            subtitle?.let { Text(it, style = MetaStyle, color = colors.muted) }
        }
    }
}

@Composable
private fun ProfileOption(profile: Profile, selected: Boolean, onClick: () -> Unit) {
    val colors = TeamCode.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp).testTag("profile-${profile.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
