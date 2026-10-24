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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.window.Dialog
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
import kotlinx.coroutines.flow.drop
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.SessionHost
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
import me.nathanfallet.teamcode.companion.presentation.components.KindChips
import me.nathanfallet.teamcode.companion.presentation.components.hostName
import me.nathanfallet.teamcode.companion.presentation.components.systemOf
import me.nathanfallet.teamcode.companion.presentation.components.LinkButton
import me.nathanfallet.teamcode.companion.presentation.components.NavBar
import me.nathanfallet.teamcode.companion.presentation.components.StateChip
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.components.dismissKeyboardOnTap
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import me.nathanfallet.teamcode.companion.presentation.dictation.DictationButton
import me.nathanfallet.teamcode.companion.presentation.dictation.DictationNotice
import me.nathanfallet.teamcode.companion.presentation.dictation.listening
import me.nathanfallet.teamcode.companion.presentation.dictation.listeningHint
import me.nathanfallet.teamcode.companion.presentation.dictation.rememberDictation
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptItem
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptItemView
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptSeparator
import me.nathanfallet.teamcode.companion.presentation.transcript.indexOfEvent
import me.nathanfallet.teamcode.companion.presentation.transcript.transcriptItems
import me.nathanfallet.teamcode.companion.presentation.viewmodels.LiveState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionNotice
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionUiState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionViewModel
import me.nathanfallet.teamcode.companion.resources.session_waiting_host
import me.nathanfallet.teamcode.companion.presentation.components.refusalText
import me.nathanfallet.teamcode.companion.presentation.components.phaseLabel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_interrupt
import me.nathanfallet.teamcode.companion.resources.action_resume
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.artifacts_session_title
import me.nathanfallet.teamcode.companion.resources.composer_ended
import me.nathanfallet.teamcode.companion.resources.composer_hint
import me.nathanfallet.teamcode.companion.resources.composer_send
import me.nathanfallet.teamcode.companion.resources.notice_nothing_running
import me.nathanfallet.teamcode.companion.resources.notice_permission_answered
import me.nathanfallet.teamcode.companion.resources.notice_permission_denied
import me.nathanfallet.teamcode.companion.resources.notice_permission_ended
import me.nathanfallet.teamcode.companion.resources.notice_permission_expired
import me.nathanfallet.teamcode.companion.resources.notice_permission_input_unseen
import me.nathanfallet.teamcode.companion.resources.notice_permission_invalid
import me.nathanfallet.teamcode.companion.resources.notice_permission_rejected
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
import me.nathanfallet.teamcode.companion.resources.session_detail_folder
import me.nathanfallet.teamcode.companion.resources.session_detail_permissions
import me.nathanfallet.teamcode.companion.resources.session_detail_runs_on
import me.nathanfallet.teamcode.companion.resources.session_detail_system
import me.nathanfallet.teamcode.companion.resources.session_host_offline
import me.nathanfallet.teamcode.companion.resources.session_kind_cloud
import me.nathanfallet.teamcode.companion.resources.session_paused_remote_dock
import me.nathanfallet.teamcode.companion.resources.session_permissions_ask
import me.nathanfallet.teamcode.companion.resources.session_permissions_bypass
import me.nathanfallet.teamcode.companion.resources.session_empty
import me.nathanfallet.teamcode.companion.resources.session_ended
import me.nathanfallet.teamcode.companion.resources.session_live
import me.nathanfallet.teamcode.companion.resources.session_load_older
import me.nathanfallet.teamcode.companion.resources.session_menu_pause
import me.nathanfallet.teamcode.companion.resources.action_cancel
import me.nathanfallet.teamcode.companion.resources.session_menu_end
import me.nathanfallet.teamcode.companion.resources.session_end_title
import me.nathanfallet.teamcode.companion.resources.session_end_body
import me.nathanfallet.teamcode.companion.resources.session_menu_resume
import me.nathanfallet.teamcode.companion.resources.session_nothing_older
import me.nathanfallet.teamcode.companion.resources.session_paused_dock
import me.nathanfallet.teamcode.companion.resources.session_paused_stream
import me.nathanfallet.teamcode.companion.resources.session_problem
import me.nathanfallet.teamcode.companion.resources.session_reconnecting
import me.nathanfallet.teamcode.companion.resources.session_working
import me.nathanfallet.teamcode.companion.resources.sessions_title
import me.nathanfallet.teamcode.companion.presentation.components.AttachButton
import me.nathanfallet.teamcode.companion.presentation.components.MessageFiles
import me.nathanfallet.teamcode.companion.presentation.components.PendingFileChips
import me.nathanfallet.teamcode.companion.presentation.components.fileProblemText
import me.nathanfallet.teamcode.companion.presentation.files.FileDropArea
import me.nathanfallet.teamcode.companion.presentation.files.rememberFilePicker
import me.nathanfallet.teamcode.companion.resources.files_dismiss
import me.nathanfallet.teamcode.companion.resources.files_drop
import me.nathanfallet.teamcode.companion.resources.files_not_ready
import me.nathanfallet.teamcode.companion.resources.message_files_invalid
import me.nathanfallet.teamcode.companion.resources.message_files_too_large
import me.nathanfallet.teamcode.companion.resources.message_files_unavailable
import me.nathanfallet.teamcode.companion.resources.message_files_unsupported
import org.jetbrains.compose.resources.stringResource

/**
 * A session: a compact summary (state, live stream, details on demand), the transcript (history pages upward, live
 * events downward while visible), and a dock with Interrupt or Resume and the message field.
 */
@Composable
fun SessionScreen(
    viewModel: SessionViewModel, memberId: String?, onBack: (() -> Unit)?, onOpenArtifacts: () -> Unit = {}, backLabel: String = stringResource(Res.string.sessions_title),
    /** More items of the "…" menu for the loaded session (the transfer action); call `close` before acting. */
    menuItems: @Composable (session: CloudSession, close: () -> Unit) -> Unit = { _, _ -> },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    val colors = TeamCode.colors
    val messaging = state.session?.let(ControlSessionUseCase::canMessage) == true
    // The desktop takes files dropped anywhere on the session; the phones have the paperclip only.
    FileDropArea(enabled = messaging && state.files.canAttach, onDropped = viewModel::attach, modifier = Modifier.fillMaxSize()) { dropping ->
        Column(Modifier.fillMaxSize().background(colors.background).dismissKeyboardOnTap()) {
            NavBar(backLabel, onBack, state.session?.name.orEmpty()) { SessionMenu(state, viewModel, onOpenArtifacts, menuItems) }
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.session?.let { Summary(it, state) }
                    state.session?.let { session ->
                        when {
                            // Not a failure of the session: its device went quiet, and it goes on once the device is back.
                            session.hostOffline -> Banner(stringResource(Res.string.session_host_offline, hostName(session.host)), Tone.WARN, Modifier.testTag("host-offline"))
                            // The order is the session: it waits until the device's daemon picks it up, and says so.
                            session.waitingForHost -> Banner(stringResource(Res.string.session_waiting_host, hostName(session.host)), Tone.ACCENT, Modifier.testTag("waiting-host"))
                            session.hostRefused -> Banner(refusalText(session), Tone.DANGER, Modifier.testTag("host-refused"))
                            session.problemCode != null -> Banner(stringResource(Res.string.session_problem, session.problemCode), Tone.DANGER, Modifier.testTag("problem"))
                        }
                    }
                    state.error?.let { error ->
                        Banner(error, Tone.DANGER, Modifier.testTag("error"), action = if (!state.gone) {
                            { TcButton(stringResource(Res.string.action_retry), onClick = viewModel::retry, kind = ButtonKind.SECONDARY, small = true) }
                        } else null)
                    }
                    state.notice?.let { notice ->
                        val text = when (notice) {
                            is SessionNotice.Text -> notice.text
                            SessionNotice.NothingRunning -> stringResource(Res.string.notice_nothing_running)
                            is SessionNotice.PermissionClosed -> stringResource(when (notice.code) {
                                "permission_request_answered" -> Res.string.notice_permission_answered
                                "permission_request_expired" -> Res.string.notice_permission_expired
                                else -> Res.string.notice_permission_ended
                            })
                            is SessionNotice.PermissionInvalid -> if (notice.code == "permission_input_unseen") stringResource(Res.string.notice_permission_input_unseen)
                                else stringResource(Res.string.notice_permission_invalid, notice.code ?: "400")
                            SessionNotice.PermissionDenied -> stringResource(Res.string.notice_permission_denied)
                            is SessionNotice.PermissionRejected -> stringResource(Res.string.notice_permission_rejected, notice.reason ?: "rejected")
                            SessionNotice.FilesNotReady -> stringResource(Res.string.files_not_ready)
                        }
                        Banner(text, Tone.WARN, Modifier.testTag("notice"))
                    }
                }
                ConfirmingLinks { Transcript(state, memberId, viewModel, Modifier.weight(1f)) }
            }
            state.session?.let { Dock(it, state, viewModel) }
        }
        if (dropping) {
            Box(Modifier.fillMaxSize().background(colors.shade).padding(24.dp).testTag("drop-files"), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.files_drop), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(colors.panel).border(1.dp, colors.accent, RoundedCornerShape(12.dp)).padding(20.dp))
            }
        }
    }
}

/** "…": pause, resume or end (owner and administrators), and the artifacts this session published. */
@Composable
private fun SessionMenu(state: SessionUiState, viewModel: SessionViewModel, onOpenArtifacts: () -> Unit, menuItems: @Composable (CloudSession, () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
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
            if (session != null && state.canControl && ControlSessionUseCase.canEnd(session)) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.session_menu_end), color = TeamCode.colors.danger) },
                    leadingIcon = { Icon(PhosphorIcons.Regular.Stop, null, Modifier.size(18.dp), tint = TeamCode.colors.danger) },
                    enabled = !state.acting, onClick = { open = false; confirmEnd = true }, modifier = Modifier.testTag("end"))
            }
        }
    }
    if (confirmEnd) EndSessionDialog(onConfirm = { confirmEnd = false; viewModel.end() }, onDismiss = { confirmEnd = false })
}

/** Ending a session deletes its workspace and cannot be undone: asked first. */
@Composable
private fun EndSessionDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
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
            if (session.remote) KindChips(session)
            LiveLabel(state)
            Spacer(Modifier.weight(1f))
            Text(stringResource(Res.string.session_details), style = MetaStyle.copy(fontSize = 11.5.sp), color = colors.muted)
            Icon(if (expanded) PhosphorIcons.Regular.CaretUp else PhosphorIcons.Regular.CaretDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
        }
        if (!expanded) {
            val line = listOfNotNull(session.agentKind, session.model, session.effort, session.host?.directoryName, session.repositories.firstOrNull()?.let { if (session.repositories.size > 1) "$it +${session.repositories.size - 1}" else it })
            Text(line.joinToString(" · "), style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Detail(stringResource(Res.string.session_detail_runs_on), if (session.remote) hostName(session.host) else stringResource(Res.string.session_kind_cloud))
            session.host?.let { host ->
                systemOf(host)?.let { Detail(stringResource(Res.string.session_detail_system), it) }
                host.directoryName?.let { Detail(stringResource(Res.string.session_detail_folder), it) }
                when (host.permissionMode) {
                    SessionHost.ASK -> Detail(stringResource(Res.string.session_detail_permissions), stringResource(Res.string.session_permissions_ask))
                    SessionHost.BYPASS -> Detail(stringResource(Res.string.session_detail_permissions), stringResource(Res.string.session_permissions_bypass))
                }
            }
            Detail(stringResource(Res.string.session_detail_agent), session.agentKind)
            session.model?.let { Detail(stringResource(Res.string.session_detail_model), it) }
            session.effort?.let { Detail(stringResource(Res.string.session_detail_effort), it) }
            phaseLabel(session)?.let { Detail(stringResource(Res.string.session_detail_phase), it) }
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
    // The transcript opens on its last message and follows the newest one while the member stays at the bottom; scrolled
    // up to read older ones, the position is left alone until they come back down. Decided by the member's own scrolling,
    // not by the last layout: items that grow after their first measure (rendered Markdown, a tool's result, a folded run)
    // would otherwise leave the list above the bottom, at its top when the session opens.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        // Only when a scroll ends: the first value is no gesture, and a transcript measured before this ran must still be pinned.
        snapshotFlow { listState.isScrollInProgress }.drop(1).collect { scrolling -> if (!scrolling) follow = !listState.canScrollForward }
    }
    // Older events arrive above the ones being read: the list keeps the member on the same event, whatever the items'
    // keys became. Compose keeps the first visible item by its key, which is not enough here: the "top" row is the first
    // visible item of a member who reached the top, and it stays at index 0 while the page lands under it.
    var shownItems by remember { mutableStateOf<List<TranscriptItem>?>(null) }
    SideEffect {
        if (shownItems !== items) {
            // Following the newest message is decided by the effect above; nothing is read yet when the first page lands.
            if (!follow) listState.keepReading(items)
            shownItems = items
        }
    }
    // Hidden until the first page is pinned to the bottom: the transcript never shows its top, then jumps.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.layoutInfo.totalItemsCount, listState.layoutInfo.visibleItemsInfo.lastOrNull()?.let { it.offset + it.size }, listState.canScrollForward) }
            .collect { (count, _, below) ->
                // Never while the member scrolls: their gesture wins, and decides whether to follow when it ends.
                if (follow && below && count > 0 && !listState.isScrollInProgress) {
                    // At the next measure, without an animation: the last item, past its end so a bottom item taller than
                    // the window (pending messages) shows its end; the list stops at its bottom.
                    listState.requestScrollToItem(count - 1, 1_000_000)
                }
            }
    }
    LaunchedEffect(state.loaded, items.isEmpty()) {
        if (!state.loaded) return@LaunchedEffect
        // One frame for the pinning above to run on the loaded page.
        withFrameNanos { }
        withFrameNanos { }
        settled = true
    }
    LazyColumn(modifier.widthIn(max = ContentWidth).fillMaxWidth().alpha(if (settled || !state.loaded) 1f else 0f).testTag("transcript"), state = listState,
        contentPadding = PaddingValues(vertical = 8.dp)) {
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

/**
 * Called as [items] replace the ones the list was laid out with (before the next measure): puts the list back on the
 * event its first visible row shows, at the same distance from the top, when that row moved to another index.
 */
private fun LazyListState.keepReading(items: List<TranscriptItem>) {
    val info = layoutInfo
    // The first row that is an event: the "top" and "bottom" rows have no sequence.
    val reading = info.visibleItemsInfo.firstOrNull { it.key is Long } ?: return
    val index = items.indexOfEvent(reading.key as Long)
    if (index < 0 || index + 1 == reading.index) return
    // The row stays the first one laid out, at the top edge: a row that started below it (the "top" button above it was
    // visible) moves up by that button's height at most. A negative offset would anchor the list on rows above it, whose
    // heights are only known once measured, and it would drift as they settle.
    requestScrollToItem(index + 1, 0)
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
                // The agent waits on it: shown above everything else, scrolled within a bounded height.
                state.permission?.let { card ->
                    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { PermissionCardView(card, viewModel) }
                }
                val paused = session.state == SessionState.PAUSED || session.state == SessionState.PAUSING
                when {
                    state.running -> DockStatus(stringResource(Res.string.session_working), colors.accentText, dot = true, tag = "running") {
                        TcButton(stringResource(Res.string.action_interrupt), onClick = viewModel::interrupt, enabled = !state.acting, busy = state.acting, kind = ButtonKind.SECONDARY, small = true,
                            icon = PhosphorIcons.Regular.Stop, modifier = Modifier.testTag("interrupt"))
                    }
                    paused -> DockStatus(
                        if (session.remote) stringResource(Res.string.session_paused_remote_dock, hostName(session.host)) else stringResource(Res.string.session_paused_dock),
                        colors.warn, dot = false, tag = "paused",
                    ) {
                        if (state.canControl && ControlSessionUseCase.canResume(session)) {
                            TcButton(stringResource(Res.string.action_resume), onClick = viewModel::resume, enabled = !state.acting, busy = state.acting, kind = ButtonKind.SECONDARY, small = true,
                                icon = PhosphorIcons.Regular.Play, modifier = Modifier.testTag("dock-resume"))
                        }
                    }
                }
                state.files.notice?.let { problem ->
                    Banner(fileProblemText(problem), Tone.WARN, Modifier.testTag("file-notice"), action = {
                        TcButton(stringResource(Res.string.files_dismiss), onClick = viewModel::dismissFileNotice, kind = ButtonKind.GHOST, small = true)
                    })
                }
                PendingFileChips(state.files.files, onRetry = viewModel::retryFile, onRemove = viewModel::removeFile)
                // The microphone where the platform transcribes natively: the words fill the draft, never sent by themselves.
                val dictation = rememberDictation()
                val field = remember { FocusRequester() }
                dictation?.let { DictationNotice(it) }
                val send = { dictation?.cancel(); viewModel.send() }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Only once the server announced `cloud.files.v1`: an older server shows the composer as before.
                    if (state.files.supported) {
                        val picker = rememberFilePicker(onPicked = viewModel::attach, onFailed = viewModel::pickFailed)
                        AttachButton(state.files, picker, onUnavailable = viewModel::explainFiles)
                    }
                    TcTextField(
                        state.draft, viewModel::onDraftChange, singleLine = false, maxLines = 6,
                        placeholder = if (dictation?.listening() == true) listeningHint() else stringResource(Res.string.composer_hint),
                        modifier = Modifier.weight(1f).focusRequester(field), tag = "composer", shape = RoundedCornerShape(22.dp), minHeight = 44.dp,
                        onSubmit = { if (state.canSend) send() },
                        trailing = dictation?.let { { DictationButton(it, { viewModel.uiState.value.draft }, viewModel::onDraftChange, field) } },
                    )
                    TcIconButton(PhosphorIcons.Regular.ArrowUp, stringResource(Res.string.composer_send), onClick = send, kind = IconButtonKind.FILLED,
                        enabled = state.canSend, modifier = Modifier.testTag("send"))
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

/** A message's refusal: the files' codes in words, any other reason as the server's code. */
@Composable
private fun messageRefusal(reason: String?): String = when (reason) {
    "file_unavailable" -> stringResource(Res.string.message_files_unavailable)
    "files_too_large" -> stringResource(Res.string.message_files_too_large)
    "files_unsupported" -> stringResource(Res.string.message_files_unsupported)
    "invalid_files" -> stringResource(Res.string.message_files_invalid)
    else -> reason.orEmpty()
}

@Composable
private fun PendingBubble(message: PendingMessage, viewModel: SessionViewModel) {
    val colors = TeamCode.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("pending-${message.clientMessageId}"), horizontalAlignment = Alignment.End) {
        if (message.text.isNotEmpty()) Text(
            message.text,
            modifier = Modifier.widthIn(max = 560.dp).alpha(0.7f).clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)).background(colors.bubble).padding(horizontal = 12.dp, vertical = 9.dp),
            color = colors.onBubble,
            fontSize = 14.5.sp,
        )
        MessageFiles(message.files, Modifier.padding(top = 4.dp))
        val failed = message.state == PendingMessage.State.FAILED || message.state == PendingMessage.State.REFUSED
        val status = when (message.state) {
            PendingMessage.State.SENDING -> stringResource(Res.string.pending_sending)
            PendingMessage.State.SENT -> stringResource(if (message.woke) Res.string.pending_woke else Res.string.pending_sent)
            PendingMessage.State.FAILED -> stringResource(Res.string.pending_failed, message.reason ?: "")
            PendingMessage.State.REFUSED -> stringResource(Res.string.pending_refused, messageRefusal(message.reason))
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
