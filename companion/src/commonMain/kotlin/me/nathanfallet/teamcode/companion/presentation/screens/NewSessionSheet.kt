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
import me.nathanfallet.teamcode.companion.presentation.viewmodels.NewSessionViewModel
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

/** The new-session form in a sheet over the Sessions tab: a profile, a name, a first message, optional overrides. */
@Composable
fun NewSessionSheet(viewModel: NewSessionViewModel, onDismiss: () -> Unit, onCreated: (CloudSession) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.created) { state.created?.let(onCreated) }
    val colors = TeamCode.colors
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
            TcTextField(state.name, viewModel::onName, label = stringResource(Res.string.new_session_name), tag = "name")
            if (state.acceptsMessage) {
                TcTextField(state.message, viewModel::onMessage, label = stringResource(Res.string.new_session_message), placeholder = stringResource(Res.string.new_session_message_hint),
                    singleLine = false, minLines = 3, maxLines = 8, tag = "message")
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(Res.string.new_session_overrides))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TcTextField(state.model, viewModel::onModel, label = stringResource(Res.string.new_session_model), placeholder = stringResource(Res.string.new_session_profile_default),
                        mono = true, tag = "model", modifier = Modifier.weight(1f))
                    TcTextField(state.effort, viewModel::onEffort, label = stringResource(Res.string.new_session_effort), placeholder = stringResource(Res.string.new_session_profile_default),
                        mono = true, tag = "effort", modifier = Modifier.weight(1f))
                }
            }
            ErrorText(state.error)
            if (state.error != null && state.profiles.isEmpty()) TcButton(stringResource(Res.string.action_retry), onClick = viewModel::reload, kind = ButtonKind.SECONDARY)
        }
        Box(Modifier.fillMaxWidth().background(colors.line).padding(top = 1.dp)) {
            Box(Modifier.fillMaxWidth().background(colors.panelAlt).navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp)) {
                TcButton(stringResource(Res.string.new_session_create), onClick = viewModel::create, enabled = state.canCreate, busy = state.creating, modifier = Modifier.testTag("create"))
            }
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
