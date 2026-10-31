package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.nathanfallet.teamcode.companion.domain.models.EditorMode
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Segmented
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowEditorsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.stringResource

/** "Who can edit workflows": an administrator's rule for the team (everyone reads, administrators always edit). Nothing is shown to a member. */
@Composable
fun WorkflowEditorsCard(viewModel: WorkflowEditorsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.administrator) return
    val colors = TeamCode.colors
    FoldSection("editors", stringResource(Res.string.wfe_editors_title), initiallyOpen = false) {
        BusyRow(state.loading)
        state.error?.let { error ->
            Banner(error.words(), Tone.DANGER, Modifier.testTag("wfe-editors-error"), action = if (!state.loaded) ({
                TcButton(stringResource(Res.string.action_retry), onClick = viewModel::load, kind = ButtonKind.SECONDARY, small = true)
            }) else null)
        }
        if (state.loaded) {
            Text(stringResource(Res.string.wfe_editors_hint), style = MetaStyle, color = colors.muted)
            Segmented(
                listOf(EditorMode.ADMINS to stringResource(Res.string.wfe_editors_admins), EditorMode.MEMBERS to stringResource(Res.string.wfe_editors_members), EditorMode.ALL to stringResource(Res.string.wfe_editors_all)),
                selected = state.mode, onSelect = viewModel::setMode, modifier = Modifier.fillMaxWidth(), tag = { "wfe-editors-${it.wire}" },
            )
            if (state.mode == EditorMode.MEMBERS) {
                val members = state.members
                if (members == null) Text(stringResource(Res.string.wfe_editors_no_list), color = colors.muted, fontSize = 13.sp)
                else Chips {
                    members.forEach { member -> ChoiceChip(member.username, member.id in state.memberIds, { viewModel.toggleMember(member.id) }, "wfe-editors-member-${member.id}") }
                }
            }
            if (state.changed) TcButton(stringResource(Res.string.wfe_editors_save), onClick = viewModel::save, busy = state.saving, modifier = Modifier.testTag("wfe-editors-save"))
        }
    }
}
