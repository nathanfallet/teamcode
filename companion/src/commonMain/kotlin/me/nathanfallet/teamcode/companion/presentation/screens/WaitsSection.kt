package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.nathanfallet.teamcode.companion.domain.models.FieldInput
import me.nathanfallet.teamcode.companion.domain.models.FieldType
import me.nathanfallet.teamcode.companion.domain.models.Wait
import me.nathanfallet.teamcode.companion.domain.models.WaitField
import me.nathanfallet.teamcode.companion.domain.models.WaitInputProblem
import me.nathanfallet.teamcode.companion.domain.models.WaitKind
import me.nathanfallet.teamcode.companion.domain.models.WaitSource
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.SectionTitle
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WaitDraft
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WaitNotice
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WaitsUiState
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.sessions_waiting_permission
import me.nathanfallet.teamcode.companion.resources.sessions_waiting_question
import me.nathanfallet.teamcode.companion.resources.waits_asks_you
import me.nathanfallet.teamcode.companion.resources.waits_closed_elsewhere
import me.nathanfallet.teamcode.companion.resources.waits_field_in_session
import me.nathanfallet.teamcode.companion.resources.waits_from_session
import me.nathanfallet.teamcode.companion.resources.waits_notice_failed
import me.nathanfallet.teamcode.companion.resources.waits_notice_refused
import me.nathanfallet.teamcode.companion.resources.waits_notice_retry
import me.nathanfallet.teamcode.companion.resources.waits_open_session
import me.nathanfallet.teamcode.companion.resources.waits_problem_date
import me.nathanfallet.teamcode.companion.resources.waits_problem_missing
import me.nathanfallet.teamcode.companion.resources.waits_problem_number
import me.nathanfallet.teamcode.companion.resources.waits_problem_too_long
import me.nathanfallet.teamcode.companion.resources.waits_problem_unsupported
import me.nathanfallet.teamcode.companion.resources.waits_title
import me.nathanfallet.teamcode.companion.resources.waits_turn_failed
import org.jetbrains.compose.resources.stringResource

/**
 * "Waiting for you": the top of the sessions list while something waits for the member. A session's own permission
 * request or question is answered in the session (a button opens it); a request from an agent is answered here, field
 * by field. Nothing is shown when the server has no waits or none is open.
 */
fun LazyListScope.waitsSection(
    state: WaitsUiState,
    onInput: (waitId: String, fieldId: String, FieldInput) -> Unit,
    onAnswer: (Wait, actionId: String) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    if (!state.available || (state.waits.isEmpty() && !state.closedElsewhere)) return
    item(key = "waits-title") { SectionTitle(stringResource(Res.string.waits_title), Modifier.testTag("waits-title")) }
    if (state.closedElsewhere) item(key = "waits-closed") { Banner(stringResource(Res.string.waits_closed_elsewhere), Tone.NEUTRAL, Modifier.testTag("waits-closed")) }
    items(state.waits.size, key = { "wait-${state.waits[it].id}" }) { index ->
        val wait = state.waits[index]
        WaitCard(wait, state.drafts[wait.id] ?: WaitDraft(), onInput, onAnswer, onOpenSession)
    }
}

@Composable
private fun WaitCard(wait: Wait, draft: WaitDraft, onInput: (String, String, FieldInput) -> Unit, onAnswer: (Wait, String) -> Unit, onOpenSession: (String) -> Unit) {
    val colors = TeamCode.colors
    TcCard(Modifier.testTag("wait-${wait.id}"), borderColor = colors.accentDim) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(
                stringResource(when (wait.kind) {
                    WaitKind.PERMISSION -> Res.string.sessions_waiting_permission
                    WaitKind.QUESTION -> Res.string.sessions_waiting_question
                    WaitKind.TURN_FAILED -> Res.string.waits_turn_failed
                    WaitKind.HUMAN_REQUEST -> Res.string.waits_asks_you
                }),
                tone = if (wait.kind == WaitKind.TURN_FAILED) Tone.DANGER else Tone.ACCENT, dot = true,
            )
            wait.sessionName?.let { Text(stringResource(Res.string.waits_from_session, it), style = MetaStyle, color = colors.muted, maxLines = 1) }
        }
        Text(wait.title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        wait.body?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.muted, fontSize = 13.sp, maxLines = 6) }
        if (wait.source == WaitSource.WAIT) {
            wait.fields.forEach { WaitFieldInput(wait, it, draft.inputs[it.id], onInput) }
            draft.problem?.let { Banner(it.words(), Tone.WARN, Modifier.testTag("wait-problem")) }
            draft.notice?.let { Banner(stringResource(when (it) {
                WaitNotice.REFUSED -> Res.string.waits_notice_refused
                WaitNotice.RETRY -> Res.string.waits_notice_retry
                WaitNotice.FAILED -> Res.string.waits_notice_failed
            }), Tone.DANGER) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                wait.actions.forEachIndexed { index, action ->
                    TcButton(
                        action.label, onClick = { onAnswer(wait, action.id) }, small = true, enabled = !draft.sending, busy = draft.sending && index == 0,
                        kind = when (action.style) { "primary" -> ButtonKind.PRIMARY; "danger" -> ButtonKind.DANGER; else -> ButtonKind.SECONDARY },
                        modifier = Modifier.testTag("wait-action-${action.id}"),
                    )
                }
            }
        }
        wait.sessionId?.let { sessionId ->
            TcButton(stringResource(Res.string.waits_open_session), onClick = { onOpenSession(sessionId) }, kind = ButtonKind.GHOST, small = true, modifier = Modifier.testTag("wait-open"))
        }
    }
}

@Composable
private fun WaitInputProblem.words(): String = when (reason) {
    WaitInputProblem.Reason.MISSING -> stringResource(Res.string.waits_problem_missing, label)
    WaitInputProblem.Reason.NOT_A_NUMBER -> stringResource(Res.string.waits_problem_number, label)
    WaitInputProblem.Reason.NOT_A_DATE -> stringResource(Res.string.waits_problem_date, label)
    WaitInputProblem.Reason.TOO_LONG -> stringResource(Res.string.waits_problem_too_long, label)
    WaitInputProblem.Reason.UNSUPPORTED -> stringResource(Res.string.waits_problem_unsupported, label)
}

@Composable
private fun WaitFieldInput(wait: Wait, field: WaitField, input: FieldInput?, onInput: (String, String, FieldInput) -> Unit) {
    val colors = TeamCode.colors
    val change = { value: FieldInput -> onInput(wait.id, field.id, value) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (field.type) {
            FieldType.INFO -> field.help?.let { Text(it, color = colors.muted, fontSize = 13.sp) }
            // A file or a secret is not typed here: the session takes it.
            FieldType.FILE, FieldType.SECRET -> {
                Text(field.label, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(Res.string.waits_field_in_session), style = MetaStyle, color = colors.muted)
            }
            FieldType.CONFIRM -> Row(
                Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { change(FieldInput.Flag(!((input as? FieldInput.Flag)?.value ?: false))) }.testTag("wait-field-${field.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox((input as? FieldInput.Flag)?.value ?: false, onCheckedChange = null)
                Text(field.label, color = colors.text, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
            }
            FieldType.CHOICE -> {
                val chosen = (input as? FieldInput.Chosen)?.values.orEmpty()
                Text(field.label, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                field.options.forEach { option ->
                    val selected = option.value in chosen
                    Row(
                        Modifier.fillMaxWidth().clickable(role = if (field.multiple) Role.Checkbox else Role.RadioButton) {
                            change(FieldInput.Chosen(if (field.multiple) (if (selected) chosen - option.value else chosen + option.value) else setOf(option.value)))
                        }.testTag("wait-option-${field.id}-${option.value}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (field.multiple) Checkbox(selected, onCheckedChange = null) else RadioButton(selected, onClick = null)
                        Text(option.label, color = colors.text, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            else -> TcTextField(
                (input as? FieldInput.Text)?.value.orEmpty(), { change(FieldInput.Text(it)) },
                label = field.label, hint = field.help, singleLine = field.type != FieldType.TEXT && field.type != FieldType.UNKNOWN, minLines = if (field.type == FieldType.TEXT || field.type == FieldType.UNKNOWN) 2 else 1,
                placeholder = if (field.type == FieldType.DATE) "2026-12-31" else null, tag = "wait-field-${field.id}",
            )
        }
    }
}
