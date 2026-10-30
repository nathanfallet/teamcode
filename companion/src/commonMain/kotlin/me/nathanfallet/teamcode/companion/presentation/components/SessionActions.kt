package me.nathanfallet.teamcode.companion.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.PencilSimple
import com.adamglin.phosphoricons.regular.Stop
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_cancel
import me.nathanfallet.teamcode.companion.resources.input_session_name
import me.nathanfallet.teamcode.companion.resources.session_end_body
import me.nathanfallet.teamcode.companion.resources.session_end_title
import me.nathanfallet.teamcode.companion.resources.session_menu_end
import me.nathanfallet.teamcode.companion.resources.session_menu_rename
import me.nathanfallet.teamcode.companion.resources.session_rename_save
import me.nathanfallet.teamcode.companion.resources.session_rename_title
import me.nathanfallet.teamcode.companion.resources.new_session_name
import org.jetbrains.compose.resources.stringResource

/*
 * The session actions that two menus offer: the "…" menu of the session's screen and the context menu of a row of the
 * sessions list. Both place these items, so the rules (who sees them, when they are disabled), the confirmation and the
 * dialogs are the same code; each menu only supplies what happens on confirmation.
 */

/** "End session" for the owner and administrators, while the session has not ended; disabled while [busy] (an action runs). */
@Composable
fun SessionEndMenuItem(session: CloudSession, canControl: Boolean, busy: Boolean, tag: String, onClick: () -> Unit) {
    if (!canControl || !ControlSessionUseCase.canEnd(session)) return
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.session_menu_end), color = TeamCode.colors.danger) },
        leadingIcon = { Icon(PhosphorIcons.Regular.Stop, null, Modifier.size(18.dp), tint = TeamCode.colors.danger) },
        enabled = !busy, onClick = onClick, modifier = Modifier.testTag(tag),
    )
}

/** "Rename" for the owner and administrators, while the session has not ended and the server renames sessions. */
@Composable
fun SessionRenameMenuItem(session: CloudSession, canControl: Boolean, supported: Boolean, busy: Boolean, tag: String, onClick: () -> Unit) {
    if (!supported || !canControl || !ControlSessionUseCase.canRename(session)) return
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.session_menu_rename)) },
        leadingIcon = { Icon(PhosphorIcons.Regular.PencilSimple, null, Modifier.size(18.dp)) },
        enabled = !busy, onClick = onClick, modifier = Modifier.testTag(tag),
    )
}

/** Ending a session deletes its workspace and cannot be undone: asked first. */
@Composable
fun EndSessionDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = TeamCode.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(Radii.card).background(colors.panel).border(1.dp, colors.line, Radii.card).padding(18.dp).testTag("end-confirm"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.session_end_title), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.session_end_body), color = colors.muted, fontSize = 13.sp, lineHeight = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TcButton(stringResource(Res.string.action_cancel), onClick = onDismiss, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f))
                TcButton(stringResource(Res.string.session_menu_end), onClick = onConfirm, kind = ButtonKind.DANGER, modifier = Modifier.weight(1f).testTag("end-confirm-yes"))
            }
        }
    }
}

/** The name of a session, prefilled with its current one; Save is offered once it is a valid new name (the server's rules). */
@Composable
fun RenameSessionDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = TeamCode.colors
    var name by rememberSaveable { mutableStateOf(current) }
    val sent = name.trim()
    val valid = ControlSessionUseCase.validName(sent)
    val canSave = valid && sent != current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(Radii.card).background(colors.panel).border(1.dp, colors.line, Radii.card).padding(18.dp).testTag("rename-dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.session_rename_title), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            TcTextField(
                name, { name = it }, label = stringResource(Res.string.new_session_name), error = !valid && name.isNotEmpty(),
                hint = if (!valid && name.isNotEmpty()) stringResource(Res.string.input_session_name, ControlSessionUseCase.MAX_NAME) else null,
                tag = "rename-name", onSubmit = { if (canSave) onConfirm(sent) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TcButton(stringResource(Res.string.action_cancel), onClick = onDismiss, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f))
                TcButton(stringResource(Res.string.session_rename_save), onClick = { onConfirm(sent) }, enabled = canSave, modifier = Modifier.weight(1f).testTag("rename-save"))
            }
        }
    }
}
