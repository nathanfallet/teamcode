package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.window.core.layout.WindowSizeClass
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.ChatCircle
import com.adamglin.phosphoricons.regular.Clock
import com.adamglin.phosphoricons.regular.Eye
import com.adamglin.phosphoricons.regular.Robot
import com.adamglin.phosphoricons.regular.SidebarSimple
import com.adamglin.phosphoricons.regular.Terminal
import com.adamglin.phosphoricons.regular.X
import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.companion.domain.services.Clock as AppClock
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.components.handCursor
import me.nathanfallet.teamcode.companion.presentation.components.hoverHighlight
import me.nathanfallet.teamcode.companion.presentation.components.windowTitleBarPadding
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.transcript.BackgroundCommand
import me.nathanfallet.teamcode.companion.presentation.transcript.LiveTasks
import me.nathanfallet.teamcode.companion.presentation.transcript.Subagent
import me.nathanfallet.teamcode.companion.presentation.transcript.SubagentState
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_cancel
import me.nathanfallet.teamcode.companion.resources.subagents_badge
import me.nathanfallet.teamcode.companion.resources.subagents_close_panel
import me.nathanfallet.teamcode.companion.resources.subagents_commands
import me.nathanfallet.teamcode.companion.resources.subagents_duration_hours
import me.nathanfallet.teamcode.companion.resources.subagents_duration_minutes
import me.nathanfallet.teamcode.companion.resources.subagents_duration_seconds
import me.nathanfallet.teamcode.companion.resources.subagents_hide_panel
import me.nathanfallet.teamcode.companion.resources.subagents_main
import me.nathanfallet.teamcode.companion.resources.subagents_main_hint
import me.nathanfallet.teamcode.companion.resources.subagents_monitors
import me.nathanfallet.teamcode.companion.resources.subagents_no_task_reports
import me.nathanfallet.teamcode.companion.resources.subagents_open_panel
import me.nathanfallet.teamcode.companion.resources.subagents_read_only
import me.nathanfallet.teamcode.companion.resources.subagents_running_badge
import me.nathanfallet.teamcode.companion.resources.subagents_scheduled
import me.nathanfallet.teamcode.companion.resources.subagents_scheduled_count
import me.nathanfallet.teamcode.companion.resources.subagents_section
import me.nathanfallet.teamcode.companion.resources.subagents_state_background
import me.nathanfallet.teamcode.companion.resources.subagents_state_done
import me.nathanfallet.teamcode.companion.resources.subagents_state_failed
import me.nathanfallet.teamcode.companion.resources.subagents_state_running
import me.nathanfallet.teamcode.companion.resources.subagents_state_stopped
import me.nathanfallet.teamcode.companion.resources.subagents_state_waiting
import me.nathanfallet.teamcode.companion.resources.subagents_stop
import me.nathanfallet.teamcode.companion.resources.subagents_stop_body
import me.nathanfallet.teamcode.companion.resources.subagents_stop_title
import me.nathanfallet.teamcode.companion.resources.subagents_stopping
import me.nathanfallet.teamcode.companion.resources.subagents_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

/**
 * Where the panel of what runs in a session goes, by Material's window size classes, the breakpoints the left sidebar
 * already uses ([isWideWindow][me.nathanfallet.teamcode.companion.presentation.components.isWideWindow]).
 */
enum class SubagentPanelMode {
    /** Compact width, or shorter than medium (a phone, in either orientation): a screen of its own, entered and left. */
    SCREEN,
    /** Medium and expanded (600 to 1199 dp): the sidebar already takes a column; the panel slides over the conversation. */
    OVERLAY,
    /** Large (1200 to 1599 dp): a column beside the conversation. */
    SIDE,
    /** Extra-large (1600 dp and more): beside it too, and an open subagent gets its own column next to the main thread. */
    SIDE_AND_THREAD,
}

enum class PanelPlacement { NONE, SCREEN, OVERLAY, SIDE }

/** What shows: the panel's [panel] placement, and an open subagent [threadBeside] the main thread or [threadReplacesMain]. */
data class SubagentLayout(val panel: PanelPlacement, val threadBeside: Boolean, val threadReplacesMain: Boolean)

fun subagentPanelMode(widthDp: Int, heightDp: Int): SubagentPanelMode = when {
    widthDp < WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND || heightDp < WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND -> SubagentPanelMode.SCREEN
    widthDp < WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND -> SubagentPanelMode.OVERLAY
    widthDp < WindowSizeClass.WIDTH_DP_EXTRA_LARGE_LOWER_BOUND -> SubagentPanelMode.SIDE
    else -> SubagentPanelMode.SIDE_AND_THREAD
}

/**
 * The layout for [mode]. Nothing shows without subagents, commands or wake-ups ([hasContent]). A screen or an overlay
 * opens on demand ([panelOpen]); a side panel shows unless the member hid it ([panelHidden]). The open subagent and both
 * flags live in the view model, so a resize only moves them: narrowed from a side panel, the panel folds away rather
 * than covering the conversation unasked, and the open subagent stays open.
 */
fun subagentLayout(mode: SubagentPanelMode, hasContent: Boolean, panelOpen: Boolean, panelHidden: Boolean, threadOpen: Boolean): SubagentLayout {
    if (!hasContent) return SubagentLayout(PanelPlacement.NONE, threadBeside = false, threadReplacesMain = false)
    val panel = when (mode) {
        SubagentPanelMode.SCREEN -> if (panelOpen) PanelPlacement.SCREEN else PanelPlacement.NONE
        SubagentPanelMode.OVERLAY -> if (panelOpen) PanelPlacement.OVERLAY else PanelPlacement.NONE
        SubagentPanelMode.SIDE, SubagentPanelMode.SIDE_AND_THREAD -> if (panelHidden) PanelPlacement.NONE else PanelPlacement.SIDE
    }
    val beside = threadOpen && mode == SubagentPanelMode.SIDE_AND_THREAD
    return SubagentLayout(panel, threadBeside = beside, threadReplacesMain = threadOpen && !beside)
}

/** The current window's [SubagentPanelMode]. */
@Composable
fun currentSubagentPanelMode(): SubagentPanelMode = currentWindowAdaptiveInfoV2().windowSizeClass.let { subagentPanelMode(it.minWidthDp, it.minHeightDp) }

/** The panel's width beside or over the conversation. */
val SubagentPanelWidth = 312.dp

/** Everything the panel lists, derived from the transcript ([subagentsOf][me.nathanfallet.teamcode.companion.presentation.transcript.subagentsOf]). */
data class RunningWork(val subagents: List<Subagent>, val live: LiveTasks?, val commands: List<BackgroundCommand>) {
    val scheduled: Int get() = live?.scheduled ?: 0
    val hasContent: Boolean get() = subagents.isNotEmpty() || commands.isNotEmpty() || scheduled > 0
    /** What runs now: active subagents, background commands and monitors, scheduled wake-ups. */
    val running: Int get() = subagents.count { it.active } + commands.size + scheduled
}

/**
 * The clock, every second while something runs (durations count up), every 30 s otherwise. The app's [AppClock] when
 * Koin holds one (the screenshots' fixed clock), else the system's.
 */
@Composable
fun rememberTicking(active: Boolean, clock: AppClock = remember { KoinPlatform.getKoinOrNull()?.getOrNull<AppClock>() ?: AppClock { kotlin.time.Clock.System.now().toEpochMilliseconds() } }): Long {
    val now by produceState(clock.nowMillis(), clock, active) {
        while (true) {
            value = clock.nowMillis()
            delay(if (active) 1_000 else 30_000)
        }
    }
    return now
}

/** "N running" (or "N subagents" once none runs) in the session's header: opens or hides the panel. */
@Composable
fun RunningBadge(work: RunningWork, onClick: () -> Unit) {
    val colors = TeamCode.colors
    val running = work.running
    val label = if (running > 0) pluralStringResource(Res.plurals.subagents_running_badge, running, running)
        else pluralStringResource(Res.plurals.subagents_badge, work.subagents.size, work.subagents.size)
    val description = stringResource(Res.string.subagents_open_panel)
    Row(
        Modifier.heightIn(min = 32.dp).clip(Radii.control).hoverHighlight(Radii.control).handCursor()
            .border(1.dp, if (running > 0) colors.accent else colors.line, Radii.control)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }
            .padding(horizontal = 10.dp).testTag("running-badge"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (running > 0) me.nathanfallet.teamcode.companion.presentation.components.Dot(colors.accentText, size = 6.dp)
        else Icon(PhosphorIcons.Regular.Robot, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
        Text(label, style = MetaStyle.copy(fontSize = 12.sp), color = if (running > 0) colors.accentText else colors.muted, maxLines = 1)
    }
}

/**
 * The panel: the main conversation, then each section that has something (subagents, background commands, monitors and
 * waits, scheduled wake-ups), and why a task cannot be stopped from here when it cannot. [onClose] closes a screen or
 * an overlay; [onHide] hides a side panel.
 */
@Composable
fun SubagentPanel(
    work: RunningWork,
    thread: String?,
    stopping: Set<String>,
    canStop: Boolean,
    now: Long,
    onOpen: (String) -> Unit,
    onMain: () -> Unit,
    onStop: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onHide: (() -> Unit)? = null,
) {
    val colors = TeamCode.colors
    Column(modifier.background(colors.panel).testTag("subagent-panel")) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.subagents_title), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (work.running > 0) Chip(pluralStringResource(Res.plurals.subagents_running_badge, work.running, work.running), tone = Tone.ACCENT, dot = true)
            onHide?.let { TcIconButton(PhosphorIcons.Regular.SidebarSimple, stringResource(Res.string.subagents_hide_panel), onClick = it, modifier = Modifier.testTag("panel-hide")) }
            onClose?.let { TcIconButton(PhosphorIcons.Regular.X, stringResource(Res.string.subagents_close_panel), onClick = it, modifier = Modifier.testTag("panel-close")) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PanelRow(PhosphorIcons.Regular.ChatCircle, stringResource(Res.string.subagents_main), stringResource(Res.string.subagents_main_hint), selected = thread == null,
                onClick = onMain, tag = "thread-main")
            if (work.subagents.isNotEmpty()) {
                SectionTitle(stringResource(Res.string.subagents_section), work.subagents.size)
                for (subagent in work.subagents) {
                    PanelRow(
                        PhosphorIcons.Regular.Robot, subagent.type, subagent.description, selected = thread == subagent.id, onClick = { onOpen(subagent.id) },
                        tag = "subagent-${subagent.id}", depth = subagent.depth,
                        trailing = {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                SubagentStateChip(subagent)
                                Text(duration(subagent.durationMs), style = MetaStyle.copy(fontSize = 11.sp), color = colors.muted)
                            }
                        },
                        footer = subagent.taskId?.takeIf { canStop && subagent.state == SubagentState.RUNNING }?.let { taskId -> { StopControl(taskId, taskId in stopping, onStop) } },
                    )
                }
            }
            val (monitors, commands) = work.commands.partition { it.monitor }
            for ((title, list, icon) in listOf(Triple(Res.string.subagents_commands, commands, PhosphorIcons.Regular.Terminal), Triple(Res.string.subagents_monitors, monitors, PhosphorIcons.Regular.Eye))) {
                if (list.isEmpty()) continue
                SectionTitle(stringResource(title), list.size)
                for (command in list) {
                    val started = command.task.startedAt
                    PanelRow(
                        icon, command.command ?: command.task.description, command.task.description.takeIf { command.command != null && it != command.command }, selected = false, onClick = null,
                        tag = "task-${command.task.id}", monospace = command.command != null,
                        trailing = { if (started != null) Text(duration(now - started), style = MetaStyle.copy(fontSize = 11.sp), color = colors.muted) },
                        footer = if (canStop) ({ StopControl(command.task.id, command.task.id in stopping, onStop) }) else null,
                    )
                }
            }
            if (work.scheduled > 0) {
                SectionTitle(stringResource(Res.string.subagents_scheduled), work.scheduled)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).testTag("scheduled"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(PhosphorIcons.Regular.Clock, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
                    Text(pluralStringResource(Res.plurals.subagents_scheduled_count, work.scheduled, work.scheduled), fontSize = 12.5.sp, color = colors.muted, lineHeight = 17.sp)
                }
            }
            val note = when {
                work.live == null -> Res.string.subagents_no_task_reports
                (work.commands.isNotEmpty() || work.subagents.any { it.taskId != null }) && !canStop -> Res.string.subagents_read_only
                else -> null
            }
            note?.let { Text(stringResource(it), fontSize = 11.5.sp, lineHeight = 16.sp, color = colors.muted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp).testTag("panel-note")) }
        }
    }
}

@Composable
private fun SectionTitle(text: String, count: Int) {
    Text("${text.uppercase()} · $count", style = MetaStyle.copy(fontSize = 10.5.sp, letterSpacing = 0.08.em), color = TeamCode.colors.muted,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun PanelRow(
    icon: ImageVector, title: String, subtitle: String?, selected: Boolean, onClick: (() -> Unit)?, tag: String,
    depth: Int = 0, monospace: Boolean = false,
    trailing: @Composable () -> Unit = {}, footer: (@Composable () -> Unit)? = null,
) {
    val colors = TeamCode.colors
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(4) * 14).dp).clip(shape)
            .then(if (selected) Modifier.background(colors.accentDim, shape) else Modifier)
            .then(if (onClick != null) Modifier.hoverHighlight(shape).handCursor().clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 7.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = if (selected) colors.accentText else colors.muted, modifier = Modifier.padding(top = 1.dp).size(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = if (monospace) MetaStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) else androidx.compose.ui.text.TextStyle.Default)
                subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp) }
            }
            trailing()
        }
        footer?.invoke()
    }
}

/** Stop, or "Stopping…" until the runner's next report drops the task; asks first ([onStop] opens the confirmation). */
@Composable
private fun StopControl(taskId: String, stopping: Boolean, onStop: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (stopping) Text(stringResource(Res.string.subagents_stopping), style = MetaStyle.copy(fontSize = 11.5.sp), color = TeamCode.colors.warn, modifier = Modifier.testTag("stopping-$taskId"))
        else TcButton(stringResource(Res.string.subagents_stop), onClick = { onStop(taskId) }, kind = ButtonKind.DANGER, small = true, modifier = Modifier.testTag("stop-$taskId"))
    }
}

@Composable
fun SubagentStateChip(subagent: Subagent) {
    val (label, tone) = when {
        subagent.state == SubagentState.RUNNING && subagent.waiting -> Res.string.subagents_state_waiting to Tone.WARN
        subagent.state == SubagentState.RUNNING -> Res.string.subagents_state_running to Tone.ACCENT
        subagent.state == SubagentState.DONE -> Res.string.subagents_state_done to Tone.MUTED
        subagent.state == SubagentState.FAILED -> Res.string.subagents_state_failed to Tone.DANGER
        subagent.state == SubagentState.STOPPED -> Res.string.subagents_state_stopped to Tone.WARN
        else -> Res.string.subagents_state_background to Tone.NEUTRAL
    }
    Chip(stringResource(label), tone = tone, dot = subagent.state == SubagentState.RUNNING && !subagent.waiting)
}

/** 42s, 3m 05s, 1h 12m. */
@Composable
fun duration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> stringResource(Res.string.subagents_duration_seconds, seconds.toInt())
        seconds < 3600 -> stringResource(Res.string.subagents_duration_minutes, (seconds / 60).toInt(), (seconds % 60).toInt())
        else -> stringResource(Res.string.subagents_duration_hours, (seconds / 3600).toInt(), ((seconds % 3600) / 60).toInt())
    }
}

/** On a phone: the panel as a screen of its own, with a way back to the conversation. */
@Composable
fun SubagentPanelScreen(content: @Composable (Modifier) -> Unit) {
    Box(Modifier.fillMaxSize().background(TeamCode.colors.background).windowTitleBarPadding().testTag("subagent-screen")) {
        content(Modifier.fillMaxSize())
    }
}

/** On a medium window: the panel over the conversation, closed by a tap beside it. */
@Composable
fun SubagentPanelOverlay(onClose: () -> Unit, content: @Composable (Modifier) -> Unit) {
    val colors = TeamCode.colors
    Box(Modifier.fillMaxSize().testTag("subagent-overlay")) {
        Box(Modifier.fillMaxSize().background(colors.shade).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose))
        Row(Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxSize().background(colors.line))
            content(Modifier.width(SubagentPanelWidth).fillMaxSize())
        }
    }
}

/** The confirmation before a stop: the task ends at once and what it had not finished is lost. */
@Composable
fun StopTaskDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = TeamCode.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(Radii.card).background(colors.panel).border(1.dp, colors.line, Radii.card).padding(18.dp).testTag("stop-confirm"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.subagents_stop_title), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.subagents_stop_body, name), color = colors.muted, fontSize = 13.sp, lineHeight = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TcButton(stringResource(Res.string.action_cancel), onClick = onDismiss, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f).testTag("stop-cancel"))
                TcButton(stringResource(Res.string.subagents_stop), onClick = onConfirm, kind = ButtonKind.DANGER, modifier = Modifier.weight(1f).testTag("stop-confirm-yes"))
            }
        }
    }
}

/** The bar over a subagent's thread: back to the main conversation (or close its column), its type, description and state. */
@Composable
fun ThreadBar(subagent: Subagent?, backLabel: String, onBack: () -> Unit, besideMain: Boolean) {
    val colors = TeamCode.colors
    Row(
        Modifier.fillMaxWidth().background(colors.panelAlt).padding(horizontal = 8.dp, vertical = 6.dp).testTag("thread-bar"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (besideMain) TcIconButton(PhosphorIcons.Regular.X, backLabel, onClick = onBack, modifier = Modifier.testTag("thread-close"))
        else Row(
            Modifier.heightIn(min = 36.dp).clip(Radii.control).hoverHighlight(Radii.control).handCursor().clickable(role = Role.Button, onClick = onBack).padding(horizontal = 6.dp).testTag("thread-back"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(PhosphorIcons.Regular.ArrowLeft, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(16.dp))
            Text(backLabel, color = colors.accentText, fontSize = 13.5.sp, maxLines = 1)
        }
        Icon(PhosphorIcons.Regular.CaretRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(12.dp))
        Icon(PhosphorIcons.Regular.Robot, contentDescription = null, tint = colors.muted, modifier = Modifier.size(15.dp))
        Column(Modifier.weight(1f)) {
            Text(subagent?.type.orEmpty(), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subagent?.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        subagent?.let { SubagentStateChip(it) }
    }
}
