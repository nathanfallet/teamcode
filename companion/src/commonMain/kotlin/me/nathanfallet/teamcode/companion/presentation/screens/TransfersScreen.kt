package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.ArrowsLeftRight
import me.nathanfallet.teamcode.companion.domain.models.SessionTransfer
import me.nathanfallet.teamcode.companion.domain.models.TransferDirection
import me.nathanfallet.teamcode.companion.domain.models.TransferState
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.TransferAction
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.EmptyState
import me.nathanfallet.teamcode.companion.presentation.components.NavBar
import me.nathanfallet.teamcode.companion.presentation.components.Segmented
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.components.TransferStateChip
import me.nathanfallet.teamcode.companion.presentation.components.transferErrorText
import me.nathanfallet.teamcode.companion.presentation.components.transferProblem
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.TransfersUiState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.TransfersViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.sessions_title
import me.nathanfallet.teamcode.companion.resources.transfer_accept
import me.nathanfallet.teamcode.companion.resources.transfer_cancel
import me.nathanfallet.teamcode.companion.resources.transfer_decline
import me.nathanfallet.teamcode.companion.resources.transfer_expires_days
import me.nathanfallet.teamcode.companion.resources.transfer_expires_hours
import me.nathanfallet.teamcode.companion.resources.transfer_expires_soon
import me.nathanfallet.teamcode.companion.resources.transfer_failed_because
import me.nathanfallet.teamcode.companion.resources.transfer_from_to
import me.nathanfallet.teamcode.companion.resources.transfer_resumes
import me.nathanfallet.teamcode.companion.resources.transfer_session
import me.nathanfallet.teamcode.companion.resources.transfer_withdraw
import me.nathanfallet.teamcode.companion.resources.transfer_you
import me.nathanfallet.teamcode.companion.resources.transfers_empty_incoming
import me.nathanfallet.teamcode.companion.resources.transfers_empty_outgoing
import me.nathanfallet.teamcode.companion.resources.transfers_empty_title
import me.nathanfallet.teamcode.companion.resources.transfers_incoming
import me.nathanfallet.teamcode.companion.resources.transfers_outgoing
import me.nathanfallet.teamcode.companion.resources.transfers_refresh
import me.nathanfallet.teamcode.companion.resources.transfers_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Session transfers of an account: offers made to the member (accept or decline) and transfers of their sessions
 * (withdraw an offer). [onOpenSession] opens a session the member can see: theirs before it went, or once it is theirs.
 */
@Composable
fun TransfersScreen(viewModel: TransfersViewModel, onBack: () -> Unit, onOpenSession: (String) -> Unit, backLabel: String = stringResource(Res.string.sessions_title), clock: Clock = koinInject()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TeamCode.colors
    val now = rememberNow(clock)
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, stringResource(Res.string.transfers_title)) {
            TcIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.transfers_refresh), onClick = viewModel::refresh, enabled = !state.loading, modifier = Modifier.testTag("transfers-refresh"))
        }
        Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Segmented(
                listOf(TransferDirection.INCOMING to stringResource(Res.string.transfers_incoming), TransferDirection.OUTGOING to stringResource(Res.string.transfers_outgoing)),
                selected = state.direction, onSelect = viewModel::setDirection, tag = { "direction-${it.wire}" },
            )
        }
        val listState = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
        LaunchedEffect(nearEnd, state.list.nextBefore) { if (nearEnd) viewModel.loadMore() }
        LazyColumn(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).testTag("transfers"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.error?.let { error -> item(key = "error") { Banner(transferErrorText(error), Tone.DANGER, Modifier.testTag("transfers-error")) } }
            items(state.list.transfers, key = { it.id }) { transfer -> TransferCard(transfer, state, viewModel, now, onOpenSession) }
            item(key = "end") {
                BusyRow(state.loading || state.loadingMore)
                if (state.list.loaded && state.list.transfers.isEmpty() && !state.loading) {
                    EmptyState(
                        PhosphorIcons.Regular.ArrowsLeftRight, stringResource(Res.string.transfers_empty_title),
                        stringResource(if (state.direction == TransferDirection.INCOMING) Res.string.transfers_empty_incoming else Res.string.transfers_empty_outgoing),
                        modifier = Modifier.testTag("transfers-empty"),
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferCard(transfer: SessionTransfer, state: TransfersUiState, viewModel: TransfersViewModel, now: Long, onOpenSession: (String) -> Unit) {
    val colors = TeamCode.colors
    val incoming = state.direction == TransferDirection.INCOMING
    // The former owner sees the session until it changed owner; the recipient once it did.
    val visible = if (incoming) transfer.state == TransferState.COMPLETED else transfer.state != TransferState.COMPLETED
    TcCard(Modifier.testTag("transfer-${transfer.id}"), onClick = if (visible) ({ onOpenSession(transfer.sessionId) }) else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransferStateChip(transfer.state)
            Text(stringResource(Res.string.transfer_session, transfer.sessionId.take(8)), style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (transfer.state == TransferState.OFFERED) transfer.expiresAt?.let { Text(expiry(it, now), style = MetaStyle.copy(fontSize = 11.5.sp), color = colors.warn) }
        }
        Text(stringResource(Res.string.transfer_from_to, memberName(transfer.fromMemberId, state), memberName(transfer.toMemberId, state)),
            color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (transfer.resume && transfer.state.open) Text(stringResource(Res.string.transfer_resumes), color = colors.muted, fontSize = 13.sp)
        transfer.problemCode?.takeIf { transfer.state == TransferState.FAILED || transfer.state == TransferState.CANCELLED }?.let {
            Text(stringResource(Res.string.transfer_failed_because, transferProblem(it)), color = if (transfer.state == TransferState.FAILED) colors.danger else colors.muted, fontSize = 13.sp)
        }
        val actions = viewModel.actions(transfer)
        if (actions.isNotEmpty()) {
            val acting = transfer.id in state.acting
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (TransferAction.ACCEPT in actions) TcButton(stringResource(Res.string.transfer_accept), onClick = { viewModel.accept(transfer) }, small = true, enabled = !acting, busy = acting,
                    modifier = Modifier.weight(1f).testTag("accept-${transfer.id}"))
                if (TransferAction.DECLINE in actions) TcButton(stringResource(Res.string.transfer_decline), onClick = { viewModel.decline(transfer) }, small = true, kind = ButtonKind.SECONDARY, enabled = !acting,
                    modifier = Modifier.weight(1f).testTag("decline-${transfer.id}"))
                if (TransferAction.CANCEL in actions) {
                    val label = if (transfer.state == TransferState.OFFERED && transfer.fromMemberId == state.memberId) Res.string.transfer_withdraw else Res.string.transfer_cancel
                    TcButton(stringResource(label), onClick = { viewModel.cancel(transfer) }, small = true, kind = ButtonKind.DANGER, enabled = !acting,
                        modifier = Modifier.weight(1f).testTag("cancel-${transfer.id}"))
                }
                if (actions.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun memberName(id: String, state: TransfersUiState): String =
    if (id == state.memberId) stringResource(Res.string.transfer_you) else state.names[id] ?: id.take(8)

@Composable
private fun expiry(at: Long, now: Long): String {
    val hours = (at - now) / 3_600_000
    return when {
        hours < 1 -> stringResource(Res.string.transfer_expires_soon)
        hours < 48 -> stringResource(Res.string.transfer_expires_hours, hours.toInt())
        else -> stringResource(Res.string.transfer_expires_days, (hours / 24).toInt())
    }
}
