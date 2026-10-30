package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Bell
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.CheckCircle
import com.adamglin.phosphoricons.regular.Warning
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.EmptyState
import me.nathanfallet.teamcode.companion.presentation.components.LargeTitle
import me.nathanfallet.teamcode.companion.presentation.components.Segmented
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WaitDraft
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WaitFilter
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WaitsUiState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WaitsViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.sessions_load_more
import me.nathanfallet.teamcode.companion.resources.waits_badge_description
import me.nathanfallet.teamcode.companion.resources.waits_banner_count
import me.nathanfallet.teamcode.companion.resources.waits_closed_elsewhere
import me.nathanfallet.teamcode.companion.resources.waits_empty_answered_body
import me.nathanfallet.teamcode.companion.resources.waits_empty_answered_title
import me.nathanfallet.teamcode.companion.resources.waits_empty_body
import me.nathanfallet.teamcode.companion.resources.waits_empty_cancelled_body
import me.nathanfallet.teamcode.companion.resources.waits_empty_cancelled_title
import me.nathanfallet.teamcode.companion.resources.waits_empty_title
import me.nathanfallet.teamcode.companion.resources.waits_error_body
import me.nathanfallet.teamcode.companion.resources.waits_error_title
import me.nathanfallet.teamcode.companion.resources.waits_filter_answered
import me.nathanfallet.teamcode.companion.resources.waits_filter_cancelled
import me.nathanfallet.teamcode.companion.resources.waits_filter_open
import me.nathanfallet.teamcode.companion.resources.waits_load_failed
import me.nathanfallet.teamcode.companion.resources.waits_reconnecting
import me.nathanfallet.teamcode.companion.resources.waits_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * "Waiting for you": everything that waits for the member, all sessions mixed, newest first, on a screen of its own.
 * Open requests are answered in place (the same cards as everywhere), a session's permission request or question has a
 * button to open the session. Answered and withdrawn ones are read from the filter, with what was given. The list is
 * kept current by the signed-in shell's one feed ([WaitsViewModel.start]); the screen itself starts nothing, so it
 * stays live, and its tab's badge too, while the app is open on it or elsewhere.
 */
@Composable
fun WaitsScreen(
    viewModel: WaitsViewModel,
    onOpenSession: (accountId: String, sessionId: String) -> Unit,
    clock: Clock = koinInject(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val now = rememberNow(clock)
    val colors = TeamCode.colors
    val account = state.account
    val shown = state.shown
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LargeTitle(stringResource(Res.string.waits_title)) {
                if (state.openCount > 0) WaitsBadge(state.openCount, state.moreOpen, Modifier.testTag("waits-badge"))
            }
            Segmented(
                listOf(
                    WaitFilter.OPEN to stringResource(Res.string.waits_filter_open), WaitFilter.ANSWERED to stringResource(Res.string.waits_filter_answered),
                    WaitFilter.CANCELLED to stringResource(Res.string.waits_filter_cancelled),
                ),
                state.filter, viewModel::setFilter, Modifier.fillMaxWidth(), tag = { "waits-filter-${it.name.lowercase()}" },
            )
            if (state.reconnecting) Banner(stringResource(Res.string.waits_reconnecting), Tone.WARN, Modifier.testTag("waits-reconnecting"))
            if (state.closedElsewhere) Banner(stringResource(Res.string.waits_closed_elsewhere), Tone.NEUTRAL, Modifier.testTag("waits-closed"), icon = null)
            // A failed load with something to show: the list stays, the banner offers the reload.
            if (state.error && shown.isNotEmpty()) Banner(stringResource(Res.string.waits_load_failed), Tone.DANGER, Modifier.testTag("waits-error"), action = {
                TcButton(stringResource(Res.string.action_retry), onClick = viewModel::retry, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("waits-retry"))
            })
        }
        val listState = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
        LaunchedEffect(nearEnd, state.shownNext) { if (nearEnd) viewModel.loadMore() }
        LazyColumn(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).testTag("waits"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown, key = { "wait-${it.id}" }) { wait ->
                WaitCard(
                    wait, state.drafts[wait.id] ?: WaitDraft(), now, onInput = viewModel::input, onAnswer = viewModel::answer,
                    onOpenSession = { id -> account?.let { onOpenSession(it.id, id) } },
                )
            }
            item(key = "end") {
                BusyRow(state.loadingMore || (!state.shownLoaded && !state.error))
                if (state.shownLoaded && shown.isEmpty() && !state.error) WaitsEmpty(state.filter)
                if (state.error && shown.isEmpty()) EmptyState(
                    PhosphorIcons.Regular.Warning, stringResource(Res.string.waits_error_title), stringResource(Res.string.waits_error_body), Modifier.testTag("waits-error-state"),
                    dashed = false, iconTint = colors.warn,
                ) {
                    TcButton(stringResource(Res.string.action_retry), onClick = viewModel::retry, modifier = Modifier.testTag("waits-retry"))
                }
                if (state.shownNext != null && !state.loadingMore) {
                    TcButton(stringResource(Res.string.sessions_load_more), onClick = viewModel::loadMore, kind = ButtonKind.GHOST, modifier = Modifier.testTag("waits-more"))
                }
            }
        }
    }
}

@Composable
private fun WaitsEmpty(filter: WaitFilter) {
    val (title, body) = when (filter) {
        WaitFilter.OPEN -> Res.string.waits_empty_title to Res.string.waits_empty_body
        WaitFilter.ANSWERED -> Res.string.waits_empty_answered_title to Res.string.waits_empty_answered_body
        WaitFilter.CANCELLED -> Res.string.waits_empty_cancelled_title to Res.string.waits_empty_cancelled_body
    }
    EmptyState(PhosphorIcons.Regular.CheckCircle, stringResource(title), stringResource(body), Modifier.testTag("waits-empty"))
}

/** The number of open waits in a pill; "12+" when more pages remain, "99+" at most. */
@Composable
fun WaitsBadge(count: Int, more: Boolean, modifier: Modifier = Modifier) {
    val colors = TeamCode.colors
    val text = if (count > 99) "99+" else if (more) "$count+" else "$count"
    val description = stringResource(Res.string.waits_badge_description, text)
    Box(
        modifier.heightIn(min = 18.dp).clip(Radii.pill).background(colors.accent).padding(horizontal = 6.dp).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = colors.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * The Sessions tab's reminder: how many waits there are and the newest one's title, a tap opens the waits screen.
 * Nothing is shown when the server has no waits or none is open.
 */
fun LazyListScope.waitsBanner(state: WaitsUiState, onOpen: () -> Unit) {
    if (!state.available || state.waits.isEmpty()) return
    item(key = "waits-banner") { WaitsBannerRow(state, onOpen) }
}

@Composable
private fun WaitsBannerRow(state: WaitsUiState, onOpen: () -> Unit) {
    val colors = TeamCode.colors
    val count = if (state.moreOpen) "${state.openCount}+" else "${state.openCount}"
    TcCard(Modifier.testTag("waits-banner"), borderColor = colors.accentDim, onClick = onOpen, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(PhosphorIcons.Regular.Bell, contentDescription = null, tint = colors.accentText, modifier = Modifier.padding(2.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(Res.string.waits_banner_count, count), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("waits-banner-count"))
                Text(state.waits.first().title, style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("waits-banner-title"))
            }
            Icon(PhosphorIcons.Regular.CaretRight, contentDescription = null, tint = colors.muted, modifier = Modifier.padding(2.dp))
        }
    }
}
