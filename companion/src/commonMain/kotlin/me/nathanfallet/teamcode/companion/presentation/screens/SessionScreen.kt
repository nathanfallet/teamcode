package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import me.nathanfallet.teamcode.companion.domain.models.PendingMessage
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase
import me.nathanfallet.teamcode.companion.resources.action_interrupt
import me.nathanfallet.teamcode.companion.resources.action_pause
import me.nathanfallet.teamcode.companion.resources.action_resume
import me.nathanfallet.teamcode.companion.resources.composer_ended
import me.nathanfallet.teamcode.companion.resources.composer_hint
import me.nathanfallet.teamcode.companion.resources.composer_send
import me.nathanfallet.teamcode.companion.resources.pending_discard
import me.nathanfallet.teamcode.companion.resources.pending_failed
import me.nathanfallet.teamcode.companion.resources.pending_refused
import me.nathanfallet.teamcode.companion.resources.pending_retry
import me.nathanfallet.teamcode.companion.resources.pending_sending
import me.nathanfallet.teamcode.companion.resources.pending_sent
import me.nathanfallet.teamcode.companion.resources.notice_nothing_running
import me.nathanfallet.teamcode.companion.resources.pending_woke
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretLeft
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ConfirmingLinks
import me.nathanfallet.teamcode.companion.presentation.components.ErrorText
import me.nathanfallet.teamcode.companion.presentation.components.StateChip
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptItemView
import me.nathanfallet.teamcode.companion.presentation.transcript.transcriptItems
import me.nathanfallet.teamcode.companion.presentation.viewmodels.LiveState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionNotice
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionUiState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_back
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.session_connecting
import me.nathanfallet.teamcode.companion.resources.session_empty
import me.nathanfallet.teamcode.companion.resources.session_ended
import me.nathanfallet.teamcode.companion.resources.session_live
import me.nathanfallet.teamcode.companion.resources.session_load_older
import me.nathanfallet.teamcode.companion.resources.session_nothing_older
import me.nathanfallet.teamcode.companion.resources.session_paused_stream
import me.nathanfallet.teamcode.companion.resources.session_problem
import me.nathanfallet.teamcode.companion.resources.session_reconnecting
import me.nathanfallet.teamcode.companion.resources.session_working
import me.nathanfallet.teamcode.companion.resources.sessions_title
import org.jetbrains.compose.resources.stringResource

/** A session's header and transcript: history pages upward, live events downward while visible. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(viewModel: SessionViewModel, memberId: String?, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.session?.name ?: stringResource(Res.string.sessions_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(PhosphorIcons.Regular.CaretLeft, contentDescription = stringResource(Res.string.action_back), modifier = Modifier.size(22.dp)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.session?.let { Header(it, state) }
                state.session?.let { Actions(it, state, viewModel) }
                ErrorText(state.error)
                if (state.error != null && !state.gone) TextButton(onClick = viewModel::retry) { Text(stringResource(Res.string.action_retry)) }
                state.notice?.let { notice ->
                    val text = when (notice) {
                        is SessionNotice.Text -> notice.text
                        SessionNotice.NothingRunning -> stringResource(Res.string.notice_nothing_running)
                    }
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.testTag("notice"))
                }
            }
            ConfirmingLinks { Transcript(state, memberId, viewModel, Modifier.weight(1f)) }
            state.session?.let { Composer(it, state, viewModel) }
        }
    }
}

@Composable
private fun Header(session: CloudSession, state: SessionUiState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StateChip(session.state)
        val live = when (state.live) {
            LiveState.CONNECTING -> Res.string.session_connecting
            LiveState.LIVE -> Res.string.session_live
            LiveState.RECONNECTING -> Res.string.session_reconnecting
            LiveState.ENDED -> Res.string.session_ended
            LiveState.STOPPED -> Res.string.session_paused_stream
        }
        Text(stringResource(live), style = MaterialTheme.typography.labelMedium, color = if (state.live == LiveState.LIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("live"))
    }
    Text(listOfNotNull(session.agentKind, session.model, session.effort, session.phase).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (session.repositories.isNotEmpty()) Text(session.repositories.joinToString(", "), style = MonospaceStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
    session.problemCode?.let { Text(stringResource(Res.string.session_problem, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun Transcript(state: SessionUiState, memberId: String?, viewModel: SessionViewModel, modifier: Modifier) {
    val onLoadOlder = viewModel::loadOlder
    val items = remember(state.transcript.events) { transcriptItems(state.transcript.events) }
    val listState = rememberLazyListState()
    // Follow the newest event while the member is at the bottom; leave the position alone when reading older ones.
    val atBottom by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 2 } ?: true } }
    LaunchedEffect(items.lastOrNull()?.key, state.running, state.pending.size) {
        // The bottom item's index from the new items, not from the last layout, which has not measured them yet.
        if (atBottom) listState.scrollToItem(items.size + 1)
    }
    LazyColumn(modifier.widthIn(max = 820.dp).fillMaxWidth().testTag("transcript"), state = listState) {
        item(key = "top") {
            when {
                state.loadingOlder -> BusyRow(true)
                state.transcript.nextBefore != null -> TextButton(onClick = onLoadOlder, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.session_load_older)) }
                state.loaded && items.isNotEmpty() -> Text(stringResource(Res.string.session_nothing_older), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(8.dp))
                !state.loaded -> BusyRow(state.error == null)
            }
        }
        items(items, key = { it.key }) { TranscriptItemView(it, memberId) }
        item(key = "bottom") {
            if (state.loaded && items.isEmpty()) Text(stringResource(Res.string.session_empty), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.pending.forEach { PendingBubble(it, viewModel) }
            if (state.running) Text(stringResource(Res.string.session_working), modifier = Modifier.padding(16.dp).testTag("running"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Actions(session: CloudSession, state: SessionUiState, viewModel: SessionViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.running) OutlinedButton(onClick = viewModel::interrupt, enabled = !state.acting, modifier = Modifier.testTag("interrupt")) { Text(stringResource(Res.string.action_interrupt)) }
        if (state.canControl && ControlSessionUseCase.canPause(session)) TextButton(onClick = viewModel::pause, enabled = !state.acting, modifier = Modifier.testTag("pause")) { Text(stringResource(Res.string.action_pause)) }
        if (state.canControl && ControlSessionUseCase.canResume(session)) TextButton(onClick = viewModel::resume, enabled = !state.acting, modifier = Modifier.testTag("resume")) { Text(stringResource(Res.string.action_resume)) }
        if (state.acting) CircularProgressIndicator(Modifier.size(18.dp))
    }
}

@Composable
private fun Composer(session: CloudSession, state: SessionUiState, viewModel: SessionViewModel) {
    Row(
        Modifier.widthIn(max = 820.dp).fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!ControlSessionUseCase.canMessage(session)) {
            Text(stringResource(Res.string.composer_ended), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Row
        }
        OutlinedTextField(
            state.draft, viewModel::onDraftChange,
            placeholder = { Text(stringResource(Res.string.composer_hint)) },
            maxLines = 6,
            modifier = Modifier.weight(1f).testTag("composer"),
        )
        Button(onClick = viewModel::send, enabled = state.draft.isNotBlank(), modifier = Modifier.testTag("send")) { Text(stringResource(Res.string.composer_send)) }
    }
}

@Composable
private fun PendingBubble(message: PendingMessage, viewModel: SessionViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("pending-${message.clientMessageId}"), horizontalAlignment = Alignment.End) {
        Text(
            message.text,
            modifier = Modifier.widthIn(max = 560.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        val failed = message.state == PendingMessage.State.FAILED || message.state == PendingMessage.State.REFUSED
        val status = when (message.state) {
            PendingMessage.State.SENDING -> stringResource(Res.string.pending_sending)
            PendingMessage.State.SENT -> stringResource(if (message.woke) Res.string.pending_woke else Res.string.pending_sent)
            PendingMessage.State.FAILED -> stringResource(Res.string.pending_failed, message.reason ?: "")
            PendingMessage.State.REFUSED -> stringResource(Res.string.pending_refused, message.reason ?: "")
        }
        Text(status, style = MaterialTheme.typography.labelSmall, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (failed) Row {
            if (message.state == PendingMessage.State.FAILED) TextButton(onClick = { viewModel.retry(message.clientMessageId) }, modifier = Modifier.testTag("retry-${message.clientMessageId}")) { Text(stringResource(Res.string.pending_retry)) }
            TextButton(onClick = { viewModel.discard(message.clientMessageId) }) { Text(stringResource(Res.string.pending_discard)) }
        }
    }
}
