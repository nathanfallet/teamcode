package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowUp
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretUp
import com.adamglin.phosphoricons.regular.DotsThree
import com.adamglin.phosphoricons.regular.Files
import com.adamglin.phosphoricons.regular.Pause
import com.adamglin.phosphoricons.regular.Play
import com.adamglin.phosphoricons.regular.Stop
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.PendingMessage
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.ConfirmingLinks
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.Dot
import me.nathanfallet.teamcode.companion.presentation.components.IconButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.LinkButton
import me.nathanfallet.teamcode.companion.presentation.components.NavBar
import me.nathanfallet.teamcode.companion.presentation.components.StateChip
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptItemView
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptSeparator
import me.nathanfallet.teamcode.companion.presentation.transcript.transcriptItems
import me.nathanfallet.teamcode.companion.presentation.viewmodels.LiveState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionNotice
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionUiState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_interrupt
import me.nathanfallet.teamcode.companion.resources.action_resume
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.artifacts_session_title
import me.nathanfallet.teamcode.companion.resources.composer_ended
import me.nathanfallet.teamcode.companion.resources.composer_hint
import me.nathanfallet.teamcode.companion.resources.composer_send
import me.nathanfallet.teamcode.companion.resources.notice_nothing_running
import me.nathanfallet.teamcode.companion.resources.pending_discard
import me.nathanfallet.teamcode.companion.resources.pending_failed
import me.nathanfallet.teamcode.companion.resources.pending_refused
import me.nathanfallet.teamcode.companion.resources.pending_retry
import me.nathanfallet.teamcode.companion.resources.pending_sending
import me.nathanfallet.teamcode.companion.resources.pending_sent
import me.nathanfallet.teamcode.companion.resources.pending_woke
import me.nathanfallet.teamcode.companion.resources.session_actions
import me.nathanfallet.teamcode.companion.resources.session_connecting
import me.nathanfallet.teamcode.companion.resources.session_detail_agent
import me.nathanfallet.teamcode.companion.resources.session_detail_effort
import me.nathanfallet.teamcode.companion.resources.session_detail_model
import me.nathanfallet.teamcode.companion.resources.session_detail_phase
import me.nathanfallet.teamcode.companion.resources.session_detail_repositories
import me.nathanfallet.teamcode.companion.resources.session_details
import me.nathanfallet.teamcode.companion.resources.session_empty
import me.nathanfallet.teamcode.companion.resources.session_ended
import me.nathanfallet.teamcode.companion.resources.session_live
import me.nathanfallet.teamcode.companion.resources.session_load_older
import me.nathanfallet.teamcode.companion.resources.session_menu_pause
import me.nathanfallet.teamcode.companion.resources.session_menu_resume
import me.nathanfallet.teamcode.companion.resources.session_nothing_older
import me.nathanfallet.teamcode.companion.resources.session_paused_dock
import me.nathanfallet.teamcode.companion.resources.session_paused_stream
import me.nathanfallet.teamcode.companion.resources.session_problem
import me.nathanfallet.teamcode.companion.resources.session_reconnecting
import me.nathanfallet.teamcode.companion.resources.session_working
import me.nathanfallet.teamcode.companion.resources.sessions_title
import org.jetbrains.compose.resources.stringResource

/**
 * A session: a compact summary (state, live stream, details on demand), the transcript (history pages upward, live
 * events downward while visible), and a dock with Interrupt or Resume and the message field.
 */
@Composable
fun SessionScreen(
    viewModel: SessionViewModel, memberId: String?, onBack: () -> Unit, onOpenArtifacts: () -> Unit = {}, backLabel: String = stringResource(Res.string.sessions_title),
    /** More items of the "…" menu for the loaded session (the transfer action); call `close` before acting. */
    menuItems: @Composable (session: CloudSession, close: () -> Unit) -> Unit = { _, _ -> },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    val colors = TeamCode.colors
    Column(Modifier.fillMaxSize().background(colors.background)) {
        NavBar(backLabel, onBack, state.session?.name.orEmpty()) { SessionMenu(state, viewModel, onOpenArtifacts, menuItems) }
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.session?.let { Summary(it, state) }
                state.session?.problemCode?.let { Banner(stringResource(Res.string.session_problem, it), Tone.DANGER, Modifier.testTag("problem")) }
                state.error?.let { error ->
                    Banner(error, Tone.DANGER, Modifier.testTag("error"), action = if (!state.gone) {
                        { TcButton(stringResource(Res.string.action_retry), onClick = viewModel::retry, kind = ButtonKind.SECONDARY, small = true) }
                    } else null)
                }
                state.notice?.let { notice ->
                    val text = when (notice) {
                        is SessionNotice.Text -> notice.text
                        SessionNotice.NothingRunning -> stringResource(Res.string.notice_nothing_running)
                    }
                    Banner(text, Tone.WARN, Modifier.testTag("notice"))
                }
            }
            ConfirmingLinks { Transcript(state, memberId, viewModel, Modifier.weight(1f)) }
        }
        state.session?.let { Dock(it, state, viewModel) }
    }
}

/** "…": pause or resume (owner and administrators), and the artifacts this session published. */
@Composable
private fun SessionMenu(state: SessionUiState, viewModel: SessionViewModel, onOpenArtifacts: () -> Unit, menuItems: @Composable (CloudSession, () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val session = state.session
    Box {
        TcIconButton(PhosphorIcons.Regular.DotsThree, stringResource(Res.string.session_actions), onClick = { open = true }, modifier = Modifier.testTag("session-menu"))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = TeamCode.colors.panel) {
            if (session != null && state.canControl && ControlSessionUseCase.canPause(session)) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.session_menu_pause)) }, leadingIcon = { Icon(PhosphorIcons.Regular.Pause, null, Modifier.size(18.dp)) },
                    enabled = !state.acting, onClick = { open = false; viewModel.pause() }, modifier = Modifier.testTag("pause"))
            }
            if (session != null && state.canControl && ControlSessionUseCase.canResume(session)) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.session_menu_resume)) }, leadingIcon = { Icon(PhosphorIcons.Regular.Play, null, Modifier.size(18.dp)) },
                    enabled = !state.acting, onClick = { open = false; viewModel.resume() }, modifier = Modifier.testTag("resume"))
            }
            DropdownMenuItem(text = { Text(stringResource(Res.string.artifacts_session_title)) }, leadingIcon = { Icon(PhosphorIcons.Regular.Files, null, Modifier.size(18.dp)) },
                onClick = { open = false; onOpenArtifacts() }, modifier = Modifier.testTag("session-artifacts"))
            if (session != null) menuItems(session) { open = false }
        }
    }
}

/** State, stream and the one-line details; a tap shows every detail. */
@Composable
private fun Summary(session: CloudSession, state: SessionUiState) {
    val colors = TeamCode.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 10.dp).testTag("summary"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StateChip(session.state)
            LiveLabel(state)
            Spacer(Modifier.weight(1f))
            Text(stringResource(Res.string.session_details), style = MetaStyle.copy(fontSize = 11.5.sp), color = colors.muted)
            Icon(if (expanded) PhosphorIcons.Regular.CaretUp else PhosphorIcons.Regular.CaretDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
        }
        if (!expanded) {
            val line = listOfNotNull(session.agentKind, session.model, session.effort, session.repositories.firstOrNull()?.let { if (session.repositories.size > 1) "$it +${session.repositories.size - 1}" else it })
            Text(line.joinToString(" · "), style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Detail(stringResource(Res.string.session_detail_agent), session.agentKind)
            session.model?.let { Detail(stringResource(Res.string.session_detail_model), it) }
            session.effort?.let { Detail(stringResource(Res.string.session_detail_effort), it) }
            session.phase?.let { Detail(stringResource(Res.string.session_detail_phase), it) }
            if (session.repositories.isNotEmpty()) Detail(stringResource(Res.string.session_detail_repositories), session.repositories.joinToString("\n"))
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = TeamCode.colors.muted, fontSize = 12.5.sp, modifier = Modifier.widthIn(min = 96.dp))
        Text(value, style = MetaStyle, color = TeamCode.colors.text, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LiveLabel(state: SessionUiState) {
    val colors = TeamCode.colors
    // Stopped by an error, the error says why: "paused in the background" would be wrong.
    if (state.live == LiveState.STOPPED && state.error != null) return
    val (text, color) = when (state.live) {
        LiveState.CONNECTING -> Res.string.session_connecting to colors.muted
        LiveState.LIVE -> Res.string.session_live to colors.accentText
        LiveState.RECONNECTING -> Res.string.session_reconnecting to colors.warn
        LiveState.ENDED -> Res.string.session_ended to colors.muted
        LiveState.STOPPED -> Res.string.session_paused_stream to colors.muted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.testTag("live")) {
        if (state.live == LiveState.LIVE) Dot(color, size = 6.dp)
        Text(stringResource(text).uppercase(), style = MetaStyle.copy(fontSize = 11.sp, letterSpacing = 0.06.em), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Transcript(state: SessionUiState, memberId: String?, viewModel: SessionViewModel, modifier: Modifier) {
    val colors = TeamCode.colors
    val items = remember(state.transcript.events) { transcriptItems(state.transcript.events) }
    val listState = rememberLazyListState()
    // Follow the newest event while the member is at the bottom; leave the position alone when reading older ones.
    val atBottom by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 2 } ?: true } }
    // Keyed on the newest event, not the last item: a tool's result or a folded run grows the tail under the same item.
    LaunchedEffect(state.transcript.events.lastOrNull()?.seq, state.running, state.pending.size) {
        // The bottom item's index from the new items, not from the last layout, which has not measured them yet.
        if (atBottom) listState.scrollToItem(items.size + 1)
    }
    LazyColumn(modifier.widthIn(max = ContentWidth).fillMaxWidth().testTag("transcript"), state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
        item(key = "top") {
            when {
                state.loadingOlder -> BusyRow(true)
                state.transcript.nextBefore != null -> TcButton(stringResource(Res.string.session_load_older), onClick = viewModel::loadOlder, kind = ButtonKind.GHOST, small = true)
                state.loaded && items.isNotEmpty() -> TranscriptSeparator(stringResource(Res.string.session_nothing_older))
                !state.loaded -> BusyRow(state.error == null)
            }
        }
        items(items, key = { it.key }) { TranscriptItemView(it, memberId) }
        item(key = "bottom") {
            if (state.loaded && items.isEmpty()) Text(stringResource(Res.string.session_empty), modifier = Modifier.fillMaxWidth().padding(16.dp), color = colors.muted, textAlign = TextAlign.Center)
            state.pending.forEach { PendingBubble(it, viewModel) }
        }
    }
}

/** The bottom dock: what the agent does (Interrupt) or that the session is paused (Resume), then the message field. */
@Composable
private fun Dock(session: CloudSession, state: SessionUiState, viewModel: SessionViewModel) {
    val colors = TeamCode.colors
    Column(Modifier.fillMaxWidth().background(colors.line).padding(top = 1.dp)) {
        Column(
            Modifier.fillMaxWidth().background(colors.panelAlt).navigationBarsPadding().imePadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ControlSessionUseCase.canMessage(session)) {
                    Text(stringResource(Res.string.composer_ended), color = colors.muted, fontSize = 13.5.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("composer-ended"))
                    return@Column
                }
                val paused = session.state == SessionState.PAUSED || session.state == SessionState.PAUSING
                when {
                    state.running -> DockStatus(stringResource(Res.string.session_working), colors.accentText, dot = true, tag = "running") {
                        TcButton(stringResource(Res.string.action_interrupt), onClick = viewModel::interrupt, enabled = !state.acting, busy = state.acting, kind = ButtonKind.SECONDARY, small = true,
                            icon = PhosphorIcons.Regular.Stop, modifier = Modifier.testTag("interrupt"))
                    }
                    paused -> DockStatus(stringResource(Res.string.session_paused_dock), colors.warn, dot = false, tag = "paused") {
                        if (state.canControl && ControlSessionUseCase.canResume(session)) {
                            TcButton(stringResource(Res.string.action_resume), onClick = viewModel::resume, enabled = !state.acting, busy = state.acting, kind = ButtonKind.SECONDARY, small = true,
                                icon = PhosphorIcons.Regular.Play, modifier = Modifier.testTag("dock-resume"))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TcTextField(
                        state.draft, viewModel::onDraftChange, placeholder = stringResource(Res.string.composer_hint), singleLine = false, maxLines = 6,
                        modifier = Modifier.weight(1f), tag = "composer", shape = RoundedCornerShape(22.dp), minHeight = 44.dp,
                    )
                    TcIconButton(PhosphorIcons.Regular.ArrowUp, stringResource(Res.string.composer_send), onClick = viewModel::send, kind = IconButtonKind.FILLED,
                        enabled = state.draft.isNotBlank(), modifier = Modifier.testTag("send"))
                }
            }
        }
    }
}

@Composable
private fun DockStatus(text: String, color: androidx.compose.ui.graphics.Color, dot: Boolean, tag: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dot) Dot(color)
        Text(text, color = color, fontSize = 13.5.sp, fontWeight = if (dot) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun PendingBubble(message: PendingMessage, viewModel: SessionViewModel) {
    val colors = TeamCode.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("pending-${message.clientMessageId}"), horizontalAlignment = Alignment.End) {
        Text(
            message.text,
            modifier = Modifier.widthIn(max = 560.dp).alpha(0.7f).clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)).background(colors.bubble).padding(horizontal = 12.dp, vertical = 9.dp),
            color = colors.onBubble,
            fontSize = 14.5.sp,
        )
        val failed = message.state == PendingMessage.State.FAILED || message.state == PendingMessage.State.REFUSED
        val status = when (message.state) {
            PendingMessage.State.SENDING -> stringResource(Res.string.pending_sending)
            PendingMessage.State.SENT -> stringResource(if (message.woke) Res.string.pending_woke else Res.string.pending_sent)
            PendingMessage.State.FAILED -> stringResource(Res.string.pending_failed, message.reason ?: "")
            PendingMessage.State.REFUSED -> stringResource(Res.string.pending_refused, message.reason ?: "")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status, fontSize = 12.sp, color = if (failed) colors.danger else colors.muted)
            if (failed) {
                if (message.state == PendingMessage.State.FAILED) LinkButton(stringResource(Res.string.pending_retry), onClick = { viewModel.retry(message.clientMessageId) }, modifier = Modifier.testTag("retry-${message.clientMessageId}"))
                LinkButton(stringResource(Res.string.pending_discard), onClick = { viewModel.discard(message.clientMessageId) }, color = colors.muted)
            }
        }
    }
}
