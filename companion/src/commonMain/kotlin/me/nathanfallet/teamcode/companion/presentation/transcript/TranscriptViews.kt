package me.nathanfallet.teamcode.companion.presentation.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.CaretUp
import com.adamglin.phosphoricons.regular.ListBullets
import com.mikepenz.markdown.m3.Markdown
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.transcript_a_tool
import me.nathanfallet.teamcode.companion.resources.transcript_actions
import me.nathanfallet.teamcode.companion.resources.transcript_agent_exited
import me.nathanfallet.teamcode.companion.resources.transcript_agent_started
import me.nathanfallet.teamcode.companion.resources.transcript_error
import me.nathanfallet.teamcode.companion.resources.transcript_error_line
import me.nathanfallet.teamcode.companion.resources.transcript_exit_code
import me.nathanfallet.teamcode.companion.resources.transcript_from
import me.nathanfallet.teamcode.companion.resources.transcript_input
import me.nathanfallet.teamcode.companion.resources.transcript_input_cancelled
import me.nathanfallet.teamcode.companion.resources.transcript_input_rejected
import me.nathanfallet.teamcode.companion.resources.transcript_output
import me.nathanfallet.teamcode.companion.resources.transcript_permission
import me.nathanfallet.teamcode.companion.resources.transcript_permission_expired
import me.nathanfallet.teamcode.companion.resources.transcript_question
import me.nathanfallet.teamcode.companion.resources.transcript_seconds
import me.nathanfallet.teamcode.companion.resources.transcript_steps
import me.nathanfallet.teamcode.companion.resources.transcript_transferred
import me.nathanfallet.teamcode.companion.resources.transcript_truncated
import me.nathanfallet.teamcode.companion.resources.transcript_turn_completed
import me.nathanfallet.teamcode.companion.resources.transcript_turn_ended
import me.nathanfallet.teamcode.companion.resources.transcript_turn_failed
import me.nathanfallet.teamcode.companion.resources.transcript_turn_interrupted
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val UserBubbleShape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)

/**
 * A message bubble takes the room the conversation has, up to nine tenths of it so that it still reads as a message
 * against the right edge; there is no fixed maximum, a wide window uses its width.
 */
fun Modifier.atMostBubbleWidth(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(maxWidth = (constraints.maxWidth * 0.9f).roundToInt().coerceAtLeast(constraints.minWidth)))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

@Composable
fun TranscriptItemView(item: TranscriptItem, memberId: String?) {
    val indent = if (item.nested) Modifier.padding(start = 20.dp) else Modifier
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).then(indent)) {
        when (item) {
            is TranscriptItem.User -> UserBubble(item.event, memberId)
            is TranscriptItem.Agent -> SelectionContainer { Markdown(item.event.text, modifier = Modifier.fillMaxWidth().testTag("agent-${item.key}")) }
            is TranscriptItem.Tool -> ToolCard(item)
            is TranscriptItem.Tools -> ToolGroup(item)
            is TranscriptItem.TurnEnd -> TurnSeparator(item.event)
            is TranscriptItem.System -> SystemLine(item.event)
        }
    }
}

@Composable
private fun UserBubble(event: TranscriptEvent.UserMessage, memberId: String?) {
    val colors = TeamCode.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Another member (or an administrator) wrote it: say who.
        if (event.authorId != null && event.authorId != memberId) {
            Text(stringResource(Res.string.transcript_from), fontSize = 11.5.sp, color = colors.muted)
        }
        SelectionContainer {
            Text(
                event.text,
                modifier = Modifier.atMostBubbleWidth().clip(UserBubbleShape).background(colors.bubble).padding(horizontal = 12.dp, vertical = 9.dp),
                color = colors.onBubble,
                fontSize = 14.5.sp,
                lineHeight = 21.sp,
            )
        }
    }
}

/** One tool use: its name and summary; a tap shows the input, the output and a failing exit code. */
@Composable
private fun ToolCard(item: TranscriptItem.Tool) {
    val colors = TeamCode.colors
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val result = item.result
    val failed = result?.isError == true || (result?.exitCode ?: 0) != 0
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.panelAlt).border(1.dp, colors.line, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button) { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 10.dp).testTag("tool-${item.key}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(item.call?.name ?: stringResource(Res.string.transcript_output), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (failed) colors.danger else colors.text)
            Text(item.call?.summary.orEmpty(), style = MetaStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.muted, modifier = Modifier.weight(1f))
            Icon(if (expanded) PhosphorIcons.Regular.CaretUp else PhosphorIcons.Regular.CaretDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
        }
        if (expanded) {
            item.call?.let { call ->
                Label(stringResource(Res.string.transcript_input), colors.muted)
                Code(call.input)
            }
            if (result != null) {
                Label(stringResource(if (result.isError) Res.string.transcript_error else Res.string.transcript_output), if (failed) colors.danger else colors.muted)
                Code(result.output)
                result.exitCode?.takeIf { it != 0 }?.let { Text(stringResource(Res.string.transcript_exit_code, it), color = colors.danger, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** A run of tool uses without text between them: "4 actions · Read · Bash · Grep", unfolded on a tap. */
@Composable
private fun ToolGroup(item: TranscriptItem.Tools) {
    val colors = TeamCode.colors
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val names = item.tools.mapNotNull { it.call?.name }.distinct()
    val summary = names.take(3).joinToString(" · ") + if (names.size > 3) " · …" else ""
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(colors.panelAlt).border(1.dp, colors.line, RoundedCornerShape(10.dp))
                .clickable(role = Role.Button) { expanded = !expanded }.padding(horizontal = 12.dp).testTag("tools-${item.key}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(PhosphorIcons.Regular.ListBullets, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
            Text(pluralStringResource(Res.plurals.transcript_actions, item.tools.size, item.tools.size), fontSize = 13.sp, color = colors.muted, modifier = Modifier.weight(1f))
            Text(summary, style = MetaStyle.copy(fontSize = 11.sp), color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(if (expanded) PhosphorIcons.Regular.CaretDown else PhosphorIcons.Regular.CaretRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
        }
        if (expanded) item.tools.forEach { ToolCard(it) }
    }
}

@Composable
private fun Label(text: String, color: Color) {
    Text(text.uppercase(), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.em, color = color)
}

@Composable
private fun Code(text: String) {
    val colors = TeamCode.colors
    SelectionContainer {
        Text(
            text,
            style = MonospaceStyle.copy(fontSize = 12.sp),
            color = colors.text,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.code).border(1.dp, colors.line, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

/** A centered monospace line between two hairlines: turn ends, the start of what the server keeps. */
@Composable
fun TranscriptSeparator(text: String, color: Color = TeamCode.colors.muted, inset: androidx.compose.ui.unit.Dp = 16.dp) {
    Row(Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(1.dp).background(TeamCode.colors.line))
        Text(text, style = MetaStyle.copy(fontSize = 11.sp), color = color, textAlign = TextAlign.Center)
        Box(Modifier.weight(1f).height(1.dp).background(TeamCode.colors.line))
    }
}

@Composable
private fun TurnSeparator(event: TranscriptEvent.Turn) {
    val label = stringResource(when (event.phase) {
        TurnPhase.COMPLETED -> Res.string.transcript_turn_completed
        TurnPhase.INTERRUPTED -> Res.string.transcript_turn_interrupted
        TurnPhase.FAILED -> Res.string.transcript_turn_failed
        else -> Res.string.transcript_turn_ended
    })
    val details = listOfNotNull(
        label,
        event.durationMs?.let { stringResource(Res.string.transcript_seconds, (it / 1000).toInt()) },
        event.steps?.let { pluralStringResource(Res.plurals.transcript_steps, it, it) },
    ).joinToString(" · ")
    val colors = TeamCode.colors
    TranscriptSeparator(details, if (event.phase == TurnPhase.FAILED) colors.danger else if (event.phase == TurnPhase.INTERRUPTED) colors.warn else colors.muted, inset = 0.dp)
}

@Composable
private fun SystemLine(event: TranscriptEvent) {
    val text = when (event) {
        is TranscriptEvent.Status -> event.exitCode?.let { stringResource(Res.string.transcript_agent_exited, it) } ?: stringResource(Res.string.transcript_agent_started)
        is TranscriptEvent.Error -> stringResource(Res.string.transcript_error_line, event.message ?: event.code ?: "")
        is TranscriptEvent.InputChanged -> stringResource(if (event.status == "cancelled") Res.string.transcript_input_cancelled else Res.string.transcript_input_rejected, event.reason ?: event.status ?: "")
        is TranscriptEvent.PermissionRequest -> if (event.question) stringResource(Res.string.transcript_question) else stringResource(Res.string.transcript_permission, event.toolName ?: stringResource(Res.string.transcript_a_tool))
        is TranscriptEvent.PermissionExpired -> stringResource(Res.string.transcript_permission_expired)
        is TranscriptEvent.Transferred -> stringResource(Res.string.transcript_transferred)
        is TranscriptEvent.Truncated -> stringResource(Res.string.transcript_truncated, event.type)
        else -> return
    }
    val color = if (event is TranscriptEvent.Error) TeamCode.colors.danger else TeamCode.colors.muted
    Text(text, fontSize = 13.sp, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("system-${event.seq}"))
}
