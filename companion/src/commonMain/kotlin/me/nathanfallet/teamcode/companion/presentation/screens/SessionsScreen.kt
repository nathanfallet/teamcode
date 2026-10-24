package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Files
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.TerminalWindow
import com.adamglin.phosphoricons.regular.Warning
import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.KindFilter
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionScope
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.presentation.components.phaseLabel
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BrandGlyph
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.ContextMenuBox
import me.nathanfallet.teamcode.companion.presentation.components.arrowKeyNavigation
import me.nathanfallet.teamcode.companion.presentation.components.Dot
import me.nathanfallet.teamcode.companion.presentation.components.EmptyState
import me.nathanfallet.teamcode.companion.presentation.components.IconButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.KindChips
import me.nathanfallet.teamcode.companion.presentation.components.LargeTitle
import me.nathanfallet.teamcode.companion.presentation.components.LocalDense
import me.nathanfallet.teamcode.companion.presentation.components.LocalWideLayout
import me.nathanfallet.teamcode.companion.presentation.components.Segmented
import me.nathanfallet.teamcode.companion.presentation.components.Skeleton
import me.nathanfallet.teamcode.companion.presentation.components.StateChip
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.components.hostOf
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.HomeViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionsViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.artifacts_session_title
import me.nathanfallet.teamcode.companion.resources.sidebar_mark_read
import me.nathanfallet.teamcode.companion.resources.sidebar_open
import me.nathanfallet.teamcode.companion.resources.home_sign_in_again
import me.nathanfallet.teamcode.companion.resources.home_signed_out
import me.nathanfallet.teamcode.companion.resources.new_session_title
import me.nathanfallet.teamcode.companion.resources.sessions_active
import me.nathanfallet.teamcode.companion.resources.sessions_empty_active
import me.nathanfallet.teamcode.companion.resources.sessions_empty_active_body
import me.nathanfallet.teamcode.companion.resources.sessions_empty_ended
import me.nathanfallet.teamcode.companion.resources.sessions_empty_ended_body
import me.nathanfallet.teamcode.companion.resources.sessions_empty_paused
import me.nathanfallet.teamcode.companion.resources.sessions_empty_paused_body
import me.nathanfallet.teamcode.companion.resources.sessions_ended
import me.nathanfallet.teamcode.companion.resources.sessions_kind_all
import me.nathanfallet.teamcode.companion.resources.sessions_kind_cloud
import me.nathanfallet.teamcode.companion.resources.sessions_kind_remote
import me.nathanfallet.teamcode.companion.resources.sessions_load_more
import me.nathanfallet.teamcode.companion.resources.sessions_loading
import me.nathanfallet.teamcode.companion.resources.sessions_mine
import me.nathanfallet.teamcode.companion.resources.sessions_paused
import me.nathanfallet.teamcode.companion.resources.sessions_reconnecting
import me.nathanfallet.teamcode.companion.resources.sessions_scope
import me.nathanfallet.teamcode.companion.resources.sessions_signed_out_title
import me.nathanfallet.teamcode.companion.resources.sessions_switch_account
import me.nathanfallet.teamcode.companion.resources.sessions_team
import me.nathanfallet.teamcode.companion.resources.sessions_title
import me.nathanfallet.teamcode.companion.resources.sessions_turn_finished
import me.nathanfallet.teamcode.companion.resources.time_days
import me.nathanfallet.teamcode.companion.resources.time_hours
import me.nathanfallet.teamcode.companion.resources.time_just_now
import me.nathanfallet.teamcode.companion.resources.time_minutes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Sessions tab: the active account's cloud sessions, kept current by the team feed while the tab is visible.
 * [onNewSession] opens the new-session sheet, [onSwitchAccount] the account switcher. A right click on a card opens
 * its menu; Up and Down move the keyboard focus between cards, Enter opens the focused one. [follow]: the screen starts
 * and stops the list's feed with its lifecycle; false when the list is shared with a caller that does (the wide layout's
 * sidebar shows it too).
 */
@Composable
fun SessionsScreen(
    onSignInAgain: (Account) -> Unit,
    onOpenSession: (Account, CloudSession) -> Unit,
    onNewSession: (Account) -> Unit = {},
    onSwitchAccount: () -> Unit = {},
    /** More buttons of the title for a signed-in account (the transfers list). */
    titleActions: @Composable (Account) -> Unit = {},
    /** The session's artifacts, from the card's menu; null: no such entry. */
    onOpenSessionArtifacts: ((Account, CloudSession) -> Unit)? = null,
    viewModel: HomeViewModel = koinViewModel(),
    sessionsViewModel: SessionsViewModel = koinViewModel(),
    follow: Boolean = true,
    clock: Clock = koinInject(),
) {
    val home by viewModel.uiState.collectAsStateWithLifecycle()
    val state by sessionsViewModel.uiState.collectAsStateWithLifecycle()
    // Streams are open only while the screen is visible: closed in the background, resumed on return.
    if (follow) LifecycleStartEffect(sessionsViewModel) {
        sessionsViewModel.start()
        onStopOrDispose { sessionsViewModel.stop() }
    }
    val now = rememberNow(clock)
    val account = home.account
    val colors = TeamCode.colors

    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LargeTitle(stringResource(Res.string.sessions_title)) {
                if (account != null && account.signedIn) titleActions(account)
                if (account != null && account.signedIn) TcIconButton(PhosphorIcons.Regular.Plus, stringResource(Res.string.new_session_title), onClick = { onNewSession(account) },
                    kind = IconButtonKind.FILLED, modifier = Modifier.testTag("new-session"))
            }
            // The sidebar lists the accounts on a wide window.
            if (!LocalWideLayout.current) account?.let { AccountPill(it, onSwitchAccount) }
            if (account != null && account.signedIn) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Segmented(
                    listOf(SessionGroup.ACTIVE to stringResource(Res.string.sessions_active), SessionGroup.PAUSED to stringResource(Res.string.sessions_paused), SessionGroup.ENDED to stringResource(Res.string.sessions_ended)),
                    selected = state.group, onSelect = sessionsViewModel::setGroup, modifier = Modifier.weight(1f), tag = { "group-${it.name.lowercase()}" },
                )
                if (account.isAdministrator) ScopePill(state.scope, sessionsViewModel::setScope)
            }
            // Only from a server with remote sessions: an older one has cloud sessions only.
            if (account != null && account.signedIn && state.remoteSessions) Segmented(
                listOf(KindFilter.ALL to stringResource(Res.string.sessions_kind_all), KindFilter.CLOUD to stringResource(Res.string.sessions_kind_cloud),
                    KindFilter.REMOTE to stringResource(Res.string.sessions_kind_remote)),
                selected = state.kind, onSelect = sessionsViewModel::setKind, modifier = Modifier.fillMaxWidth(), tag = { "kind-${it.name.lowercase()}" },
            )
        }

        val list = state.list
        val listState = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
        LaunchedEffect(nearEnd, list?.nextBefore) { if (nearEnd) sessionsViewModel.loadMore() }
        val error = state.error ?: home.error
        LazyColumn(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).arrowKeyNavigation().testTag("sessions"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (account != null && !account.signedIn) {
                item(key = "signed-out") {
                    EmptyState(
                        PhosphorIcons.Regular.Warning, stringResource(Res.string.sessions_signed_out_title), stringResource(Res.string.home_signed_out, hostOf(account.origin)),
                        dashed = false, iconTint = colors.warn,
                    ) {
                        TcButton(stringResource(Res.string.home_sign_in_again), onClick = { onSignInAgain(account) }, modifier = Modifier.testTag("sign-in-again"))
                    }
                }
                return@LazyColumn
            }
            if (error != null) item(key = "error") {
                Banner(error, Tone.DANGER, Modifier.testTag("sessions-error"), action = if (state.error != null) {
                    { TcButton(stringResource(Res.string.action_retry), onClick = sessionsViewModel::retry, kind = ButtonKind.SECONDARY, small = true) }
                } else null)
            }
            if (state.reconnecting) item(key = "reconnecting") { Banner(stringResource(Res.string.sessions_reconnecting), Tone.WARN) }
            val sessions = list?.sessions.orEmpty()
            if (state.loading && sessions.isEmpty()) {
                items(3, key = { "skeleton-$it" }) { index -> SkeletonCard(index) }
            }
            items(sessions, key = { it.id }) { session ->
                val finished = list?.finishedTurns?.contains(session.id) == true
                val open = { sessionsViewModel.opened(session.id); account?.let { onOpenSession(it, session) }; Unit }
                ContextMenuBox(menu = { close ->
                    DropdownMenuItem(text = { Text(stringResource(Res.string.sidebar_open)) }, onClick = { close(); open() }, modifier = Modifier.testTag("menu-open"))
                    if (onOpenSessionArtifacts != null && account != null) DropdownMenuItem(text = { Text(stringResource(Res.string.artifacts_session_title)) },
                        leadingIcon = { Icon(PhosphorIcons.Regular.Files, null, Modifier.size(16.dp)) }, onClick = { close(); onOpenSessionArtifacts(account, session) },
                        modifier = Modifier.testTag("menu-artifacts"))
                    if (finished) DropdownMenuItem(text = { Text(stringResource(Res.string.sidebar_mark_read)) }, leadingIcon = { Icon(PhosphorIcons.Regular.Check, null, Modifier.size(16.dp)) },
                        onClick = { close(); sessionsViewModel.opened(session.id) }, modifier = Modifier.testTag("menu-mark-read"))
                }) {
                    SessionCard(session, finished = finished, now = now, dimmed = error != null, showKind = state.remoteSessions, onClick = open)
                }
            }
            item(key = "end") {
                BusyRow(state.loadingMore)
                if (!state.loading && list != null && sessions.isEmpty() && state.error == null && account != null && account.signedIn) {
                    val (title, body) = when (list.group) {
                        SessionGroup.ACTIVE -> Res.string.sessions_empty_active to Res.string.sessions_empty_active_body
                        SessionGroup.PAUSED -> Res.string.sessions_empty_paused to Res.string.sessions_empty_paused_body
                        SessionGroup.ENDED -> Res.string.sessions_empty_ended to Res.string.sessions_empty_ended_body
                    }
                    EmptyState(PhosphorIcons.Regular.TerminalWindow, stringResource(title), stringResource(body), Modifier.testTag("sessions-empty")) {
                        if (list.group == SessionGroup.ACTIVE) TcButton(stringResource(Res.string.new_session_title), onClick = { onNewSession(account) }, icon = PhosphorIcons.Regular.Plus)
                    }
                }
                if (list?.nextBefore != null && !state.loadingMore) {
                    TcButton(stringResource(Res.string.sessions_load_more), onClick = sessionsViewModel::loadMore, kind = ButtonKind.GHOST)
                }
            }
        }
    }
}

/** Which account the list is; opens the switcher. */
@Composable
fun AccountPill(account: Account, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TeamCode.colors
    val label = stringResource(Res.string.sessions_switch_account)
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(Radii.pill)
            .background(colors.panel)
            .border(1.dp, colors.line, Radii.pill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(start = 10.dp, end = 12.dp)
            .testTag("account-line"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BrandGlyph()
        Text(account.username, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(hostOf(account.origin), style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(PhosphorIcons.Regular.CaretDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
    }
}

/** Administrators choose between their own sessions and the whole team's. */
@Composable
private fun ScopePill(scope: SessionScope, onScope: (SessionScope) -> Unit) {
    val colors = TeamCode.colors
    var open by remember { mutableStateOf(false) }
    val options = listOf(SessionScope.MINE to stringResource(Res.string.sessions_mine), SessionScope.TEAM to stringResource(Res.string.sessions_team))
    val description = stringResource(Res.string.sessions_scope)
    Box {
        Row(
            Modifier
                .heightIn(min = 44.dp)
                .clip(Radii.pill)
                .background(colors.panel)
                .border(1.dp, colors.line, Radii.pill)
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = description }
                .padding(horizontal = 12.dp)
                .testTag("scope"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(options.first { it.first == scope }.second, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Icon(PhosphorIcons.Regular.CaretDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.panel) {
            for ((value, label) in options) {
                DropdownMenuItem(text = { Text(label, fontWeight = if (value == scope) FontWeight.SemiBold else FontWeight.Normal) }, onClick = { open = false; onScope(value) },
                    modifier = Modifier.testTag("scope-${value.name.lowercase()}"))
            }
        }
    }
}

/**
 * A session: name and state, agent and model, where it runs ([showKind], once the server has remote sessions),
 * repositories or folder and age; a finished turn not seen yet is marked.
 */
@Composable
private fun SessionCard(session: CloudSession, finished: Boolean, now: Long, dimmed: Boolean, showKind: Boolean, onClick: () -> Unit) {
    val colors = TeamCode.colors
    val dense = LocalDense.current && LocalWideLayout.current
    TcCard(Modifier.alpha(if (dimmed) 0.7f else 1f).testTag("session-${session.id}"), borderColor = if (finished) colors.accentDim else colors.line, onClick = onClick,
        padding = if (dense) 12.dp else 14.dp, spacing = if (dense) 5.dp else 7.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (finished) Dot(colors.accentText, Modifier.testTag("finished-${session.id}"))
            Text(session.name, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            StateChip(session.state)
        }
        val details = listOfNotNull(session.agentKind, session.model, session.effort, phaseLabel(session)).joinToString(" · ")
        // A remote session has no repository to clone: the folder it runs in tells it apart instead.
        val where = if (session.remote) session.host?.directoryName.orEmpty() else session.repositories.joinToString(", ")
        if (dense) {
            // A wide pointer-first window: one line of details under the name, the card is a list row.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showKind || session.remote) KindChips(session)
                Text(listOf(details, where).filter { it.isNotEmpty() }.joinToString("  ·  "), style = MetaStyle, color = colors.muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (finished) Text(stringResource(Res.string.sessions_turn_finished), color = colors.accentText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(relativeTime(session.lastActivityAt, now), style = MetaStyle, color = colors.muted, maxLines = 1)
            }
            return@TcCard
        }
        if (details.isNotEmpty()) Text(details, style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (showKind || session.remote) KindChips(session)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(where, style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (finished) Text(stringResource(Res.string.sessions_turn_finished), color = colors.accentText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(relativeTime(session.lastActivityAt, now), style = MetaStyle, color = colors.muted, maxLines = 1)
        }
    }
}

@Composable
private fun SkeletonCard(index: Int) {
    val description = stringResource(Res.string.sessions_loading)
    val widths = listOf(listOf(0.62f, 0.44f, 0.30f), listOf(0.74f, 0.38f, 0.26f), listOf(0.55f, 0.48f, 0.34f))[index % 3]
    TcCard(Modifier.semantics { contentDescription = description }, spacing = 9.dp) {
        Skeleton(widths[0], 15.dp)
        Skeleton(widths[1])
        Skeleton(widths[2])
    }
}

/** The current time, refreshed every 30 seconds for relative dates. */
@Composable
fun rememberNow(clock: Clock = koinInject()): Long {
    val now by produceState(clock.nowMillis(), clock) {
        while (true) {
            delay(30_000)
            value = clock.nowMillis()
        }
    }
    return now
}

@Composable
fun relativeTime(at: Long, now: Long): String {
    val minutes = ((now - at) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(Res.string.time_just_now)
        minutes < 60 -> stringResource(Res.string.time_minutes, minutes.toInt())
        minutes < 48 * 60 -> stringResource(Res.string.time_hours, (minutes / 60).toInt())
        else -> stringResource(Res.string.time_days, (minutes / 1440).toInt())
    }
}
