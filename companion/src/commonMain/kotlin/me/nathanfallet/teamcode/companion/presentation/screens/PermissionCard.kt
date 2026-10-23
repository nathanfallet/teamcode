package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check
import me.nathanfallet.teamcode.companion.domain.models.AgentQuestion
import me.nathanfallet.teamcode.companion.domain.models.QuestionChoice
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.LinkButton
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AnswerState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.PermissionCard
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.permission_allow
import me.nathanfallet.teamcode.companion.resources.permission_back
import me.nathanfallet.teamcode.companion.resources.permission_choose_one
import me.nathanfallet.teamcode.companion.resources.permission_choose_several
import me.nathanfallet.teamcode.companion.resources.permission_decline
import me.nathanfallet.teamcode.companion.resources.permission_deny
import me.nathanfallet.teamcode.companion.resources.permission_failed
import me.nathanfallet.teamcode.companion.resources.permission_left_out
import me.nathanfallet.teamcode.companion.resources.permission_more_waiting
import me.nathanfallet.teamcode.companion.resources.permission_next
import me.nathanfallet.teamcode.companion.resources.permission_other
import me.nathanfallet.teamcode.companion.resources.permission_other_hint
import me.nathanfallet.teamcode.companion.resources.permission_progress
import me.nathanfallet.teamcode.companion.resources.permission_question_title
import me.nathanfallet.teamcode.companion.resources.permission_retry
import me.nathanfallet.teamcode.companion.resources.permission_send
import me.nathanfallet.teamcode.companion.resources.permission_sending
import me.nathanfallet.teamcode.companion.resources.permission_sent
import me.nathanfallet.teamcode.companion.resources.permission_tool_title
import org.jetbrains.compose.resources.stringResource

/**
 * The open permission request the agent waits on: a question shown one at a time (options, "Other" with its text, Back,
 * Next and Send on the last one, Decline), or a tool use with its input (Allow, Deny). Every text here is the agent's
 * and is shown as plain text: no Markdown, no links.
 */
@Composable
internal fun PermissionCardView(card: PermissionCard, viewModel: SessionViewModel, modifier: Modifier = Modifier) {
    val colors = TeamCode.colors
    val id = card.requestId
    val request = card.request
    TcCard(modifier.testTag("permission-card"), borderColor = colors.accentDim, spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val title = if (request.question) stringResource(Res.string.permission_question_title)
            else stringResource(Res.string.permission_tool_title, request.toolName ?: "tool")
            Text(title, color = colors.accentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (request.question && card.answerable && request.questions.size > 1) {
                Text(stringResource(Res.string.permission_progress, card.step + 1, request.questions.size), style = MetaStyle, color = colors.muted, modifier = Modifier.testTag("permission-progress"))
            }
        }
        if (card.waiting > 0) Text(stringResource(Res.string.permission_more_waiting, card.waiting), fontSize = 12.sp, color = colors.muted)
        when {
            !card.answerable -> {
                Text(stringResource(Res.string.permission_left_out), color = colors.text, fontSize = 13.5.sp)
                InputBlock(request.inputPreview.orEmpty())
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TcButton(stringResource(if (request.question) Res.string.permission_decline else Res.string.permission_deny), onClick = { viewModel.deny(id) },
                        enabled = card.editable, kind = ButtonKind.DANGER, small = true, modifier = Modifier.testTag("permission-deny"))
                }
            }
            request.question -> card.current?.let { question -> Question(card, question, viewModel) }
            else -> {
                request.description?.let { Text(it, color = colors.text, fontSize = 13.5.sp) }
                request.input?.let { InputBlock(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    TcButton(stringResource(Res.string.permission_deny), onClick = { viewModel.deny(id) }, enabled = card.editable, kind = ButtonKind.DANGER, small = true,
                        modifier = Modifier.testTag("permission-deny"))
                    TcButton(stringResource(Res.string.permission_allow), onClick = { viewModel.allowTool(id) }, enabled = card.editable, small = true,
                        modifier = Modifier.testTag("permission-allow"))
                }
            }
        }
        AnswerStatus(card, viewModel)
    }
}

@Composable
private fun Question(card: PermissionCard, question: AgentQuestion, viewModel: SessionViewModel) {
    val colors = TeamCode.colors
    val id = card.requestId
    val choice = card.draft.choices[question.index] ?: QuestionChoice()
    question.header?.let { Chip(it) }
    Text(question.text, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("permission-question"))
    Text(stringResource(if (question.multiSelect) Res.string.permission_choose_several else Res.string.permission_choose_one), fontSize = 12.sp, color = colors.muted)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, colors.line, RoundedCornerShape(12.dp))) {
        question.options.forEachIndexed { index, option ->
            Choice(option.label, option.description, index in choice.options, question.multiSelect, card.editable, "option-${question.index}-$index") {
                viewModel.selectOption(id, question.index, index)
            }
        }
        Choice(stringResource(Res.string.permission_other), null, choice.other, question.multiSelect, card.editable, "other-${question.index}") { viewModel.selectOther(id, question.index) }
        if (choice.other) {
            TcTextField(
                choice.otherText, { viewModel.onOtherText(id, question.index, it) }, placeholder = stringResource(Res.string.permission_other_hint), singleLine = false, maxLines = 4,
                enabled = card.editable, tag = "other-text-${question.index}", modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (card.step > 0) TcButton(stringResource(Res.string.permission_back), onClick = { viewModel.previousQuestion(id) }, enabled = card.editable, kind = ButtonKind.GHOST, small = true,
            modifier = Modifier.testTag("permission-back"))
        TcButton(stringResource(Res.string.permission_decline), onClick = { viewModel.deny(id) }, enabled = card.editable, kind = ButtonKind.DANGER, small = true,
            modifier = Modifier.testTag("permission-deny"))
        Spacer(Modifier.weight(1f))
        if (card.last) TcButton(stringResource(Res.string.permission_send), onClick = { viewModel.sendAnswers(id) }, enabled = card.editable && card.currentAnswered,
            busy = card.draft.state == AnswerState.SENDING, small = true, modifier = Modifier.testTag("permission-send"))
        else TcButton(stringResource(Res.string.permission_next), onClick = { viewModel.nextQuestion(id) }, enabled = card.editable && card.currentAnswered, small = true,
            modifier = Modifier.testTag("permission-next"))
    }
}

/** One option: a radio button for a single choice, a checkbox for several. */
@Composable
private fun Choice(label: String, description: String?, selected: Boolean, multiSelect: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val colors = TeamCode.colors
    val selection = if (multiSelect) Modifier.toggleable(selected, enabled = enabled, role = Role.Checkbox) { onClick() }
    else Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).then(selection).padding(horizontal = 12.dp, vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val tint = if (selected) colors.accent else colors.line
        if (multiSelect) {
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(if (selected) colors.accent else colors.panel).border(1.5.dp, tint, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                if (selected) Icon(PhosphorIcons.Regular.Check, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(14.dp))
            }
        } else {
            Box(Modifier.size(20.dp).border(1.5.dp, tint, CircleShape), contentAlignment = Alignment.Center) {
                if (selected) Box(Modifier.size(10.dp).background(colors.accent, CircleShape))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            description?.let { Text(it, color = colors.muted, fontSize = 12.5.sp) }
        }
    }
}

/** A tool input or a preview, scrolled inside its own box. */
@Composable
private fun InputBlock(text: String) {
    val colors = TeamCode.colors
    Box(Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(10.dp)).background(colors.panelAlt).border(1.dp, colors.line, RoundedCornerShape(10.dp))
        .verticalScroll(rememberScrollState()).padding(10.dp).testTag("permission-input")) {
        Text(text, style = MonospaceStyle, color = colors.text)
    }
}

@Composable
private fun AnswerStatus(card: PermissionCard, viewModel: SessionViewModel) {
    val colors = TeamCode.colors
    when (card.draft.state) {
        AnswerState.EDITING -> Unit
        AnswerState.SENDING -> Text(stringResource(Res.string.permission_sending), fontSize = 12.sp, color = colors.muted, modifier = Modifier.testTag("permission-status"))
        AnswerState.SENT -> Text(stringResource(Res.string.permission_sent), fontSize = 12.sp, color = colors.muted, modifier = Modifier.testTag("permission-status"))
        AnswerState.FAILED -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("permission-status")) {
            Text(stringResource(Res.string.permission_failed, card.draft.reason ?: ""), fontSize = 12.sp, color = colors.danger, modifier = Modifier.weight(1f, fill = false))
            LinkButton(stringResource(Res.string.permission_retry), onClick = { viewModel.retryAnswer(card.requestId) }, modifier = Modifier.testTag("permission-retry"))
        }
    }
}
