package me.nathanfallet.teamcode.companion.presentation.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.resources.Res
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
import org.jetbrains.compose.resources.stringResource

@Composable
fun TranscriptItemView(item: TranscriptItem, memberId: String?) {
    val indent = if (item.nested) Modifier.padding(start = 20.dp) else Modifier
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).then(indent)) {
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
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        // Another member (or an administrator) wrote it: say who.
        if (event.authorId != null && event.authorId != memberId) {
            Text(stringResource(Res.string.transcript_from, event.authorId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SelectionContainer {
            Text(
                event.text,
                modifier = Modifier.widthIn(max = 560.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ToolCard(item: TranscriptItem.Tool) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val result = item.result
    val failed = result?.isError == true || (result?.exitCode ?: 0) != 0
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).clickable { expanded = !expanded }.padding(10.dp).testTag("tool-${item.key}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(item.call?.name ?: stringResource(Res.string.transcript_output), style = MaterialTheme.typography.labelLarge, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            item.call?.summary?.let { Text(it, style = MonospaceStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (expanded) {
            item.call?.let { call ->
                Text(stringResource(Res.string.transcript_input), style = MaterialTheme.typography.labelSmall)
                Code(call.input)
            }
            if (result != null) {
                Text(stringResource(if (result.isError) Res.string.transcript_error else Res.string.transcript_output), style = MaterialTheme.typography.labelSmall, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Code(result.output)
                result.exitCode?.takeIf { it != 0 }?.let { Text(stringResource(Res.string.transcript_exit_code, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun ToolGroup(item: TranscriptItem.Tools) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(Res.string.transcript_actions, item.tools.size) + if (expanded) " ▾" else " ▸",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp).testTag("tools-${item.key}"),
        )
        if (expanded) item.tools.forEach { ToolCard(it) }
    }
}

@Composable
private fun Code(text: String) {
    SelectionContainer {
        Text(
            text,
            style = MonospaceStyle,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp)).horizontalScroll(rememberScrollState()).padding(8.dp),
        )
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
        event.steps?.let { stringResource(Res.string.transcript_steps, it) },
    ).joinToString(" · ")
    val color = if (event.phase == TurnPhase.FAILED || event.phase == TurnPhase.INTERRUPTED) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(details, style = MaterialTheme.typography.labelSmall, color = color)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SystemLine(event: TranscriptEvent) {
    val text = when (event) {
        is TranscriptEvent.Status -> event.exitCode?.let { stringResource(Res.string.transcript_agent_exited, it) } ?: stringResource(Res.string.transcript_agent_started)
        is TranscriptEvent.Error -> stringResource(Res.string.transcript_error_line, event.message ?: event.code ?: "")
        is TranscriptEvent.InputChanged -> stringResource(if (event.status == "cancelled") Res.string.transcript_input_cancelled else Res.string.transcript_input_rejected, event.reason ?: event.status ?: "")
        is TranscriptEvent.PermissionRequest -> if (event.question) stringResource(Res.string.transcript_question) else stringResource(Res.string.transcript_permission, event.toolName ?: "a tool")
        is TranscriptEvent.PermissionExpired -> stringResource(Res.string.transcript_permission_expired)
        is TranscriptEvent.Transferred -> stringResource(Res.string.transcript_transferred)
        is TranscriptEvent.Truncated -> stringResource(Res.string.transcript_truncated, event.type)
        else -> return
    }
    val color = if (event is TranscriptEvent.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("system-${event.seq}"))
}
