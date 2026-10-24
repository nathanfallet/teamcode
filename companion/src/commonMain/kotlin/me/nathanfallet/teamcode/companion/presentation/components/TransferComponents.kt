package me.nathanfallet.teamcode.companion.presentation.components

import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsLeftRight
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.TransferState
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.TransferAvailabilityUseCase
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.TransferError
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.transfer_menu
import me.nathanfallet.teamcode.companion.resources.transfer_problem_effort
import me.nathanfallet.teamcode.companion.resources.transfer_problem_limit
import me.nathanfallet.teamcode.companion.resources.transfer_problem_model
import me.nathanfallet.teamcode.companion.resources.transfer_problem_not_found
import me.nathanfallet.teamcode.companion.resources.transfer_problem_permission
import me.nathanfallet.teamcode.companion.resources.transfer_problem_profile
import me.nathanfallet.teamcode.companion.resources.transfer_problem_quota
import me.nathanfallet.teamcode.companion.resources.transfer_problem_timeout
import me.nathanfallet.teamcode.companion.resources.transfer_problem_unavailable
import me.nathanfallet.teamcode.companion.resources.transfer_refused_busy
import me.nathanfallet.teamcode.companion.resources.transfer_refused_conflict
import me.nathanfallet.teamcode.companion.resources.transfer_refused_disabled
import me.nathanfallet.teamcode.companion.resources.transfer_refused_expired
import me.nathanfallet.teamcode.companion.resources.transfer_refused_forbidden
import me.nathanfallet.teamcode.companion.resources.transfer_refused_invalid
import me.nathanfallet.teamcode.companion.resources.transfer_refused_not_open
import me.nathanfallet.teamcode.companion.resources.transfer_refused_not_running
import me.nathanfallet.teamcode.companion.resources.transfer_refused_other
import me.nathanfallet.teamcode.companion.resources.transfer_refused_pending
import me.nathanfallet.teamcode.companion.resources.transfer_refused_preparing
import me.nathanfallet.teamcode.companion.resources.transfer_refused_recipient
import me.nathanfallet.teamcode.companion.resources.transfer_refused_remote
import me.nathanfallet.teamcode.companion.resources.transfer_refused_transferring
import me.nathanfallet.teamcode.companion.resources.transfer_state_accepted
import me.nathanfallet.teamcode.companion.resources.transfer_state_cancelled
import me.nathanfallet.teamcode.companion.resources.transfer_state_completed
import me.nathanfallet.teamcode.companion.resources.transfer_state_declined
import me.nathanfallet.teamcode.companion.resources.transfer_state_expired
import me.nathanfallet.teamcode.companion.resources.transfer_state_failed
import me.nathanfallet.teamcode.companion.resources.transfer_state_offered
import me.nathanfallet.teamcode.companion.resources.transfer_state_unknown
import me.nathanfallet.teamcode.companion.resources.transfers_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** The sentence for a refusal of the server, by its code; an unknown code is named as is. */
@Composable
fun transferErrorText(error: TransferError): String = when (error) {
    is TransferError.Text -> error.text.words()
    is TransferError.Refused -> when (error.code) {
        "recipient_ineligible" -> stringResource(Res.string.transfer_refused_recipient, error.problems.map { transferProblem(it) }.joinToString("; ").ifEmpty { error.code })
        else -> refusal(error.code)?.let { stringResource(it) } ?: stringResource(Res.string.transfer_refused_other, error.code)
    }
}

private fun refusal(code: String): StringResource? = when (code) {
    "transfer_pending" -> Res.string.transfer_refused_pending
    "session_preparing" -> Res.string.transfer_refused_preparing
    "session_not_running" -> Res.string.transfer_refused_not_running
    "session_transferring", "transfer_in_progress" -> Res.string.transfer_refused_transferring
    "session_busy" -> Res.string.transfer_refused_busy
    "remote_not_transferable" -> Res.string.transfer_refused_remote
    "transfer_disabled" -> Res.string.transfer_refused_disabled
    "transfer_forbidden", "forbidden" -> Res.string.transfer_refused_forbidden
    "transfer_expired" -> Res.string.transfer_refused_expired
    "transfer_not_open" -> Res.string.transfer_refused_not_open
    "idempotency_conflict" -> Res.string.transfer_refused_conflict
    "invalid_transfer" -> Res.string.transfer_refused_invalid
    else -> null
}

/** One failed check of the recipient (or why a transfer failed at the change of owner), in a few words. */
@Composable
fun transferProblem(code: String): String = when (code) {
    "recipient_not_found" -> stringResource(Res.string.transfer_problem_not_found)
    "recipient_unavailable" -> stringResource(Res.string.transfer_problem_unavailable)
    "profile_unassigned" -> stringResource(Res.string.transfer_problem_profile)
    "cloud_permission_denied" -> stringResource(Res.string.transfer_problem_permission)
    "model_not_allowed" -> stringResource(Res.string.transfer_problem_model)
    "effort_not_allowed" -> stringResource(Res.string.transfer_problem_effort)
    "recipient_no_quota" -> stringResource(Res.string.transfer_problem_quota)
    "cloud_session_limit" -> stringResource(Res.string.transfer_problem_limit)
    "transfer_timeout" -> stringResource(Res.string.transfer_problem_timeout)
    else -> code
}

fun TransferState.label(): StringResource = when (this) {
    TransferState.OFFERED -> Res.string.transfer_state_offered
    TransferState.ACCEPTED -> Res.string.transfer_state_accepted
    TransferState.COMPLETED -> Res.string.transfer_state_completed
    TransferState.DECLINED -> Res.string.transfer_state_declined
    TransferState.CANCELLED -> Res.string.transfer_state_cancelled
    TransferState.EXPIRED -> Res.string.transfer_state_expired
    TransferState.FAILED -> Res.string.transfer_state_failed
    TransferState.UNKNOWN -> Res.string.transfer_state_unknown
}

/** Waiting offers in amber, a change of owner under way with the accent, failures red, the rest muted. */
@Composable
fun TransferStateChip(state: TransferState, modifier: Modifier = Modifier) {
    val tone = when (state) {
        TransferState.OFFERED -> Tone.WARN
        TransferState.ACCEPTED -> Tone.ACCENT
        TransferState.COMPLETED -> Tone.NEUTRAL
        TransferState.FAILED -> Tone.DANGER
        else -> Tone.MUTED
    }
    Chip(stringResource(state.label()), modifier, tone = tone, dot = state == TransferState.ACCEPTED)
}

/** A setting row of a [Group]: a title, a line under it, and a switch; the whole row toggles. */
@Composable
fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = TeamCode.colors
    GroupRow(modifier.heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)) {
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            Text(title, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(body, color = colors.muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent, checkedBorderColor = colors.accent,
                uncheckedTrackColor = colors.panelAlt, uncheckedThumbColor = colors.muted, uncheckedBorderColor = colors.line,
            ),
        )
    }
}

/**
 * "Transfer…" in a session's menu, shown only when the member may give this session away (its owner or an
 * administrator, a cloud session that is neither being prepared, ended nor already changing owner) on a server with
 * transfers.
 */
@Composable
fun TransferMenuItem(accountId: String, session: CloudSession, onClick: () -> Unit) {
    val availability: TransferAvailabilityUseCase = koinInject()
    val visible by produceState(false, accountId, session.id, session.state, session.ownerId, session.kind) { value = availability.canTransfer(accountId, session) }
    if (!visible) return
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.transfer_menu)) },
        leadingIcon = { Icon(PhosphorIcons.Regular.ArrowsLeftRight, null, Modifier.size(18.dp)) },
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("transfer"),
    )
}

/** The Sessions tab's way to the transfers list, on a server with transfers. */
@Composable
fun TransfersButton(accountId: String, onClick: () -> Unit) {
    val availability: TransferAvailabilityUseCase = koinInject()
    val visible by produceState(false, accountId) { value = availability.available(accountId) }
    if (visible) TcIconButton(PhosphorIcons.Regular.ArrowsLeftRight, stringResource(Res.string.transfers_title), onClick = onClick, kind = IconButtonKind.OUTLINED, modifier = Modifier.testTag("open-transfers"))
}
