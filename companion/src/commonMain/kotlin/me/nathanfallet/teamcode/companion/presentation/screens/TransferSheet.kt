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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.nathanfallet.teamcode.companion.domain.models.TeamMember
import me.nathanfallet.teamcode.companion.domain.models.TransferState
import me.nathanfallet.teamcode.companion.presentation.components.Avatar
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.Group
import me.nathanfallet.teamcode.companion.presentation.components.GroupDivider
import me.nathanfallet.teamcode.companion.presentation.components.SheetHeader
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcSheet
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.components.ToggleRow
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.components.transferErrorText
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.TransferViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_cancel
import me.nathanfallet.teamcode.companion.resources.action_done
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.transfer_administrator
import me.nathanfallet.teamcode.companion.resources.transfer_done_admin
import me.nathanfallet.teamcode.companion.resources.transfer_done_member
import me.nathanfallet.teamcode.companion.resources.transfer_force
import me.nathanfallet.teamcode.companion.resources.transfer_force_body
import me.nathanfallet.teamcode.companion.resources.transfer_intro_admin
import me.nathanfallet.teamcode.companion.resources.transfer_intro_member
import me.nathanfallet.teamcode.companion.resources.transfer_member_id
import me.nathanfallet.teamcode.companion.resources.transfer_member_id_hint
import me.nathanfallet.teamcode.companion.resources.transfer_no_member
import me.nathanfallet.teamcode.companion.resources.transfer_resume
import me.nathanfallet.teamcode.companion.resources.transfer_resume_body
import me.nathanfallet.teamcode.companion.resources.transfer_search
import me.nathanfallet.teamcode.companion.resources.transfer_submit_admin
import me.nathanfallet.teamcode.companion.resources.transfer_submit_member
import me.nathanfallet.teamcode.companion.resources.transfer_title
import me.nathanfallet.teamcode.companion.resources.transfer_unavailable
import org.jetbrains.compose.resources.stringResource

/**
 * Transfer a session, in a sheet over it. An administrator picks a member (searched by name) and transfers at once, with
 * the options to resume it for them and to pause without waiting for the running turn; a member names the recipient by
 * member ID and sends an offer they accept.
 */
@Composable
fun TransferSheet(viewModel: TransferViewModel, onDismiss: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TeamCode.colors
    TcSheet(onDismiss) {
        SheetHeader(
            stringResource(Res.string.transfer_title),
            centered = true,
            leading = { TcButton(stringResource(Res.string.action_cancel), onClick = onDismiss, kind = ButtonKind.GHOST, small = true, modifier = Modifier.testTag("transfer-cancel")) },
        )
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp).testTag("transfer-form"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val done = state.done
            if (done != null) {
                val name = state.recipient?.username ?: done.toMemberId
                Banner(stringResource(if (done.state == TransferState.OFFERED) Res.string.transfer_done_member else Res.string.transfer_done_admin, name), Tone.ACCENT, Modifier.testTag("transfer-done"))
                return@Column
            }
            Text(stringResource(if (state.administrator) Res.string.transfer_intro_admin else Res.string.transfer_intro_member), color = colors.muted, fontSize = 14.sp, lineHeight = 20.sp)
            BusyRow(state.loading)
            val members = state.members
            if (members != null) {
                TcTextField(state.query, viewModel::onQuery, label = stringResource(Res.string.transfer_search), tag = "transfer-search")
                val matches = state.matches
                if (matches.isEmpty()) Text(stringResource(Res.string.transfer_no_member), color = colors.muted, fontSize = 14.sp)
                else Group(Modifier.selectableGroup()) {
                    matches.forEachIndexed { index, member ->
                        if (index > 0) GroupDivider()
                        MemberOption(member, selected = state.recipientId == member.id) { viewModel.onRecipient(member.id) }
                    }
                }
            } else if (!state.loading && !state.administrator) {
                TcTextField(state.recipientId, viewModel::onRecipient, label = stringResource(Res.string.transfer_member_id), hint = stringResource(Res.string.transfer_member_id_hint),
                    mono = true, tag = "transfer-member-id")
            }
            if (state.administrator && members != null) Group {
                ToggleRow(stringResource(Res.string.transfer_resume), stringResource(Res.string.transfer_resume_body), state.resume, viewModel::onResume, Modifier.testTag("transfer-resume"))
                GroupDivider()
                ToggleRow(stringResource(Res.string.transfer_force), stringResource(Res.string.transfer_force_body), state.force, viewModel::onForce, Modifier.testTag("transfer-force"))
            }
            state.error?.let { error ->
                Banner(transferErrorText(error), Tone.DANGER, Modifier.testTag("transfer-error"), action = if (state.members == null && state.administrator) {
                    { TcButton(stringResource(Res.string.action_retry), onClick = viewModel::reload, kind = ButtonKind.SECONDARY, small = true) }
                } else null)
            }
        }
        Box(Modifier.fillMaxWidth().background(colors.line).padding(top = 1.dp)) {
            Box(Modifier.fillMaxWidth().background(colors.panelAlt).navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp)) {
                if (state.done != null) TcButton(stringResource(Res.string.action_done), onClick = onDismiss, modifier = Modifier.testTag("transfer-close"))
                else TcButton(stringResource(if (state.administrator) Res.string.transfer_submit_admin else Res.string.transfer_submit_member), onClick = viewModel::send,
                    enabled = state.canSend, busy = state.sending, modifier = Modifier.testTag("transfer-submit"))
            }
        }
    }
}

@Composable
private fun MemberOption(member: TeamMember, selected: Boolean, onClick: () -> Unit) {
    val colors = TeamCode.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, enabled = member.available, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp).alpha(if (member.available) 1f else 0.5f).testTag("recipient-${member.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(member.username, size = 32.dp, dimmed = !member.available)
        Column(Modifier.weight(1f)) {
            Text(member.username, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(if (member.available) member.id else stringResource(Res.string.transfer_unavailable), style = MetaStyle, color = colors.muted)
        }
        if (member.administrator) Chip(stringResource(Res.string.transfer_administrator), tone = Tone.MUTED)
        Box(Modifier.size(20.dp).border(1.5.dp, if (selected) colors.accent else colors.line, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).background(colors.accent, CircleShape))
        }
    }
}
