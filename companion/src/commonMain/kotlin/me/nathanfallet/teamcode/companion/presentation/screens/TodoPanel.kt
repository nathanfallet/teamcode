package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CheckCircle
import com.adamglin.phosphoricons.regular.Circle
import com.adamglin.phosphoricons.regular.MinusCircle
import com.adamglin.phosphoricons.regular.Warning
import me.nathanfallet.teamcode.companion.domain.models.SessionTodo
import me.nathanfallet.teamcode.companion.domain.models.TodoItem
import me.nathanfallet.teamcode.companion.domain.models.TodoItemState
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.components.handCursor
import me.nathanfallet.teamcode.companion.presentation.components.hoverHighlight
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.todo_add_action
import me.nathanfallet.teamcode.companion.resources.todo_add_hint
import me.nathanfallet.teamcode.companion.resources.todo_add_item
import me.nathanfallet.teamcode.companion.resources.todo_added_along
import me.nathanfallet.teamcode.companion.resources.todo_attested_flag
import me.nathanfallet.teamcode.companion.resources.todo_counter_description
import me.nathanfallet.teamcode.companion.resources.todo_current
import me.nathanfallet.teamcode.companion.resources.todo_done_when
import me.nathanfallet.teamcode.companion.resources.todo_hook_errors
import me.nathanfallet.teamcode.companion.resources.todo_last_proof
import me.nathanfallet.teamcode.companion.resources.todo_reconnecting
import me.nathanfallet.teamcode.companion.resources.todo_reference
import me.nathanfallet.teamcode.companion.resources.todo_state_doing
import me.nathanfallet.teamcode.companion.resources.todo_state_done
import me.nathanfallet.teamcode.companion.resources.todo_state_drop_requested
import me.nathanfallet.teamcode.companion.resources.todo_state_dropped
import me.nathanfallet.teamcode.companion.resources.todo_state_todo
import me.nathanfallet.teamcode.companion.resources.todo_status_abandoned
import me.nathanfallet.teamcode.companion.resources.todo_status_done
import me.nathanfallet.teamcode.companion.resources.todo_unverified
import me.nathanfallet.teamcode.companion.resources.todo_verify_plan
import org.jetbrains.compose.resources.stringResource

/** "4 / 9": the todo's counter, as the header's pill and the panel's tab show it. */
fun todoCounter(todo: SessionTodo) = "${todo.done} / ${todo.total}"

/** The pill of the session's header: the todo's counter, opening the panel on the Todo view. */
@Composable
fun TodoBadge(todo: SessionTodo, onClick: () -> Unit) {
    val colors = TeamCode.colors
    val finished = todo.status == "done" || (todo.total > 0 && todo.done == todo.total)
    val description = stringResource(Res.string.todo_counter_description, todo.done, todo.total)
    Row(
        Modifier.heightIn(min = 32.dp).clip(Radii.control).hoverHighlight(Radii.control).handCursor()
            .border(1.dp, if (finished) colors.line else colors.accent, Radii.control)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }
            .padding(horizontal = 10.dp).testTag("todo-badge"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(PhosphorIcons.Regular.CheckCircle, contentDescription = null, tint = if (finished) colors.muted else colors.accentText, modifier = Modifier.size(14.dp))
        Text(todoCounter(todo), style = MetaStyle.copy(fontSize = 12.sp), color = if (finished) colors.muted else colors.accentText, maxLines = 1)
    }
}

/**
 * The panel's Todo view, kept current by the feed: the plan's reference first, the counter, the items in order with their
 * state, end condition and last proof, the hook's errors, and two shortcuts that send an ordinary message to the session
 * ([onVerify], [onAdd]; null when the member may not write to it).
 */
@Composable
fun TodoView(
    todo: SessionTodo,
    now: Long,
    reconnecting: Boolean,
    onVerify: (() -> Unit)?,
    onAdd: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = TeamCode.colors
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp).testTag("todo-view"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (reconnecting) Text(stringResource(Res.string.todo_reconnecting), fontSize = 11.5.sp, color = colors.warn, modifier = Modifier.testTag("todo-reconnecting"))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel(stringResource(Res.string.todo_reference))
            Text(todo.reference, fontSize = 12.5.sp, lineHeight = 17.sp, color = colors.text, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("todo-reference"))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(todoCounter(todo), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.text, modifier = Modifier.testTag("todo-counter"))
                when (todo.status) {
                    "done" -> Chip(stringResource(Res.string.todo_status_done), tone = Tone.ACCENT)
                    "abandoned" -> Chip(stringResource(Res.string.todo_status_abandoned), tone = Tone.WARN)
                }
            }
            ProgressBar(todo.done, todo.total)
            todo.current?.let { Text(stringResource(Res.string.todo_current, it.name), fontSize = 12.sp, color = colors.accentText, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("todo-current")) }
            if (todo.mostlyAttested) Text(stringResource(Res.string.todo_attested_flag), fontSize = 11.5.sp, lineHeight = 16.sp, color = colors.warn, modifier = Modifier.testTag("todo-attested-flag"))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (item in todo.items) TodoRow(item, now)
        }
        val errors = todo.hookEvents.filter { it.kind == "hook_error" }
        if (errors.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("todo-hook-errors")) {
            SectionLabel(stringResource(Res.string.todo_hook_errors))
            for (event in errors.takeLast(3)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(PhosphorIcons.Regular.Warning, contentDescription = null, tint = colors.warn, modifier = Modifier.padding(top = 1.dp).size(14.dp))
                Text("${formatEventTime(event.at, utcEpochDay(now))} · ${event.text}", fontSize = 11.5.sp, lineHeight = 16.sp, color = colors.muted)
            }
        }
        if (onVerify != null && onAdd != null) Shortcuts(onVerify, onAdd)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MetaStyle.copy(fontSize = 10.5.sp, letterSpacing = 0.08.em), color = TeamCode.colors.muted)
}

@Composable
private fun ProgressBar(done: Int, total: Int) {
    val colors = TeamCode.colors
    val shape = RoundedCornerShape(3.dp)
    Box(Modifier.fillMaxWidth().height(6).clip(shape).background(colors.line)) {
        if (total > 0 && done > 0) Box(Modifier.fillMaxWidth(done.toFloat() / total).fillMaxHeight().background(colors.accent, shape))
    }
}

private fun Modifier.height(dp: Int) = this.heightIn(min = dp.dp, max = dp.dp)

@Composable
private fun TodoRow(item: TodoItem, now: Long) {
    val colors = TeamCode.colors
    val (icon, tint) = when (item.state) {
        TodoItemState.DONE -> PhosphorIcons.Regular.CheckCircle to colors.accentText
        TodoItemState.DOING -> PhosphorIcons.Regular.Circle to colors.accent
        TodoItemState.DROPPED -> PhosphorIcons.Regular.MinusCircle to colors.muted
        TodoItemState.DROP_REQUESTED -> PhosphorIcons.Regular.Warning to colors.warn
        else -> PhosphorIcons.Regular.Circle to colors.muted
    }
    val label = stringResource(when (item.state) {
        TodoItemState.DONE -> Res.string.todo_state_done
        TodoItemState.DOING -> Res.string.todo_state_doing
        TodoItemState.DROPPED -> Res.string.todo_state_dropped
        TodoItemState.DROP_REQUESTED -> Res.string.todo_state_drop_requested
        else -> Res.string.todo_state_todo
    })
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).then(if (item.state == TodoItemState.DOING) Modifier.background(colors.accentDim, shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 7.dp).testTag("todo-item-${item.id}"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.name, color = if (item.state == TodoItemState.DROPPED) colors.muted else colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (item.description.isNotBlank()) Text(item.description, color = colors.muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(stringResource(Res.string.todo_done_when, item.doneWhen), color = colors.muted, fontSize = 11.5.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            item.proof?.let { proof ->
                Text(stringResource(Res.string.todo_last_proof, formatEventTime(proof.at, utcEpochDay(now)), proof.text), color = colors.text, fontSize = 11.5.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("todo-proof-${item.id}"))
            }
            if (item.addedLater || item.unverified || item.state == TodoItemState.DROP_REQUESTED) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.addedLater) Chip(stringResource(Res.string.todo_added_along), tone = Tone.NEUTRAL, modifier = Modifier.testTag("todo-added-${item.id}"))
                if (item.unverified) Chip(stringResource(Res.string.todo_unverified), tone = Tone.WARN, modifier = Modifier.testTag("todo-unverified-${item.id}"))
                if (item.state == TodoItemState.DROP_REQUESTED) Chip(stringResource(Res.string.todo_state_drop_requested), tone = Tone.WARN)
            }
        }
    }
}

@Composable
private fun Shortcuts(onVerify: () -> Unit, onAdd: (String) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        TcButton(stringResource(Res.string.todo_verify_plan), onClick = onVerify, kind = ButtonKind.SECONDARY, small = true, fill = true, modifier = Modifier.testTag("todo-verify"))
        if (!adding) TcButton(stringResource(Res.string.todo_add_item), onClick = { adding = true }, kind = ButtonKind.SECONDARY, small = true, fill = true, modifier = Modifier.testTag("todo-add"))
        else {
            TcTextField(text, { text = it }, placeholder = stringResource(Res.string.todo_add_hint), singleLine = false, minLines = 2, maxLines = 5, tag = "todo-add-field")
            TcButton(stringResource(Res.string.todo_add_action), onClick = { onAdd(text); text = ""; adding = false }, small = true, fill = true, enabled = text.isNotBlank(), modifier = Modifier.testTag("todo-add-send"))
        }
    }
}
