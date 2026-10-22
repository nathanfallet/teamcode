package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.produceState
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
import com.adamglin.phosphoricons.regular.Gear
import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionScope
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.Dot
import me.nathanfallet.teamcode.companion.presentation.components.ErrorText
import me.nathanfallet.teamcode.companion.presentation.components.StateChip
import me.nathanfallet.teamcode.companion.presentation.viewmodels.HomeViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionsViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.new_session_title
import me.nathanfallet.teamcode.companion.resources.home_sign_in_again
import me.nathanfallet.teamcode.companion.resources.home_signed_in_as
import me.nathanfallet.teamcode.companion.resources.home_signed_out
import me.nathanfallet.teamcode.companion.resources.sessions_active
import me.nathanfallet.teamcode.companion.resources.sessions_empty_active
import me.nathanfallet.teamcode.companion.resources.sessions_empty_ended
import me.nathanfallet.teamcode.companion.resources.sessions_empty_paused
import me.nathanfallet.teamcode.companion.resources.sessions_ended
import me.nathanfallet.teamcode.companion.resources.sessions_load_more
import me.nathanfallet.teamcode.companion.resources.sessions_mine
import me.nathanfallet.teamcode.companion.resources.sessions_paused
import me.nathanfallet.teamcode.companion.resources.sessions_reconnecting
import me.nathanfallet.teamcode.companion.resources.sessions_team
import me.nathanfallet.teamcode.companion.resources.sessions_title
import me.nathanfallet.teamcode.companion.resources.sessions_turn_finished
import me.nathanfallet.teamcode.companion.resources.settings_title
import me.nathanfallet.teamcode.companion.resources.time_days
import me.nathanfallet.teamcode.companion.resources.time_hours
import me.nathanfallet.teamcode.companion.resources.time_just_now
import me.nathanfallet.teamcode.companion.resources.time_minutes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** The active account's cloud sessions, kept current by the team feed while the screen is visible. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onSignInAgain: (Account) -> Unit,
    onOpenSession: (Account, CloudSession) -> Unit,
    onNewSession: (Account) -> Unit = {},
    viewModel: HomeViewModel = koinViewModel(),
    sessionsViewModel: SessionsViewModel = koinViewModel(),
    clock: Clock = koinInject(),
) {
    val home by viewModel.uiState.collectAsStateWithLifecycle()
    val state by sessionsViewModel.uiState.collectAsStateWithLifecycle()
    // Streams are open only while the screen is visible: closed in the background, resumed on return.
    LifecycleStartEffect(sessionsViewModel) {
        sessionsViewModel.start()
        onStopOrDispose { sessionsViewModel.stop() }
    }
    val now = rememberNow(clock)
    val account = home.account

    Scaffold(
        floatingActionButton = {
            if (account?.signedIn == true) ExtendedFloatingActionButton(onClick = { onNewSession(account) }, modifier = Modifier.testTag("new-session")) {
                Text(stringResource(Res.string.new_session_title))
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(Res.string.sessions_title))
                        // With several accounts, which one the list is.
                        account?.let {
                            Text(stringResource(Res.string.home_signed_in_as, it.username, it.origin.substringAfter("://")),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("account-line"))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("settings")) {
                        Icon(PhosphorIcons.Regular.Gear, contentDescription = stringResource(Res.string.settings_title), modifier = Modifier.size(22.dp))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (account != null && !account.signedIn) {
                    Text(stringResource(Res.string.home_signed_out, account.origin), color = MaterialTheme.colorScheme.tertiary)
                    Button(onClick = { onSignInAgain(account) }, modifier = Modifier.fillMaxWidth().testTag("sign-in-again")) {
                        Text(stringResource(Res.string.home_sign_in_again))
                    }
                }
                Filters(state.group, state.scope, account?.isAdministrator == true, sessionsViewModel::setGroup, sessionsViewModel::setScope)
                if (state.reconnecting) Text(stringResource(Res.string.sessions_reconnecting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                ErrorText(state.error ?: home.error)
                if (state.error != null) TextButton(onClick = sessionsViewModel::retry) { Text(stringResource(Res.string.action_retry)) }
            }
            val list = state.list
            val listState = rememberLazyListState()
            val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
            LaunchedEffect(nearEnd, list?.nextBefore) { if (nearEnd) sessionsViewModel.loadMore() }
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f).testTag("sessions"), state = listState) {
                items(list?.sessions.orEmpty(), key = { it.id }) { session ->
                    SessionRow(session, finished = list?.finishedTurns?.contains(session.id) == true, now = now) {
                        sessionsViewModel.opened(session.id)
                        account?.let { onOpenSession(it, session) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item {
                    BusyRow(state.loading || state.loadingMore)
                    if (!state.loading && list != null && list.sessions.isEmpty() && state.error == null && account?.signedIn == true) {
                        Text(
                            stringResource(when (list.group) {
                                SessionGroup.ACTIVE -> Res.string.sessions_empty_active
                                SessionGroup.PAUSED -> Res.string.sessions_empty_paused
                                SessionGroup.ENDED -> Res.string.sessions_empty_ended
                            }),
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (list?.nextBefore != null && !state.loadingMore) {
                        TextButton(onClick = sessionsViewModel::loadMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.sessions_load_more)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Filters(group: SessionGroup, scope: SessionScope, administrator: Boolean, onGroup: (SessionGroup) -> Unit, onScope: (SessionScope) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((value, label) in listOf(SessionGroup.ACTIVE to Res.string.sessions_active, SessionGroup.PAUSED to Res.string.sessions_paused, SessionGroup.ENDED to Res.string.sessions_ended)) {
            FilterChip(selected = group == value, onClick = { onGroup(value) }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("group-${value.name.lowercase()}"))
        }
    }
    if (administrator) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in listOf(SessionScope.MINE to Res.string.sessions_mine, SessionScope.TEAM to Res.string.sessions_team)) {
            FilterChip(selected = scope == value, onClick = { onScope(value) }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("scope-${value.name.lowercase()}"))
        }
    }
}

@Composable
private fun SessionRow(session: CloudSession, finished: Boolean, now: Long, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).testTag("session-${session.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (finished) Dot(MaterialTheme.colorScheme.primary, Modifier.testTag("finished-${session.id}"))
            Text(session.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            StateChip(session.state)
        }
        val details = listOfNotNull(session.agentKind, session.model, session.phase).joinToString(" · ")
        Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(relativeTime(session.lastActivityAt, now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (finished) Text(stringResource(Res.string.sessions_turn_finished), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
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
