package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleStartEffect
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
import com.adamglin.phosphoricons.regular.Clock
import com.adamglin.phosphoricons.regular.Plus
import me.nathanfallet.teamcode.companion.domain.models.AutomationScope
import me.nathanfallet.teamcode.companion.domain.models.AutomationSummary
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.EmptyState
import me.nathanfallet.teamcode.companion.presentation.components.IconButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.LargeTitle
import me.nathanfallet.teamcode.companion.presentation.components.NavBar
import me.nathanfallet.teamcode.companion.presentation.components.Segmented
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The automations of the member (or, for an administrator, of the team): state, next trigger, last run, consecutive
 * failures, and a switch to arm or pause. A row opens the automation's page; the plus creates one.
 */
@Composable
fun AutomationsScreen(
    viewModel: AutomationsViewModel,
    onOpen: (AutomationSummary) -> Unit,
    onCreate: () -> Unit,
    /** Set when the list is pushed over Settings (the phone): a bar with the way back replaces the large title. */
    onBack: (() -> Unit)? = null,
    backLabel: String = stringResource(Res.string.account_title),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val now = rememberNow()
    val colors = TeamCode.colors
    // Back on the list (from a page, the form, another tab): what was created, deleted or paused meanwhile shows.
    LaunchedEffect(Unit) { if (viewModel.uiState.value.automations != null) viewModel.reload() }
    var started by remember { mutableStateOf(false) }
    LifecycleStartEffect(viewModel) {
        if (started) viewModel.reload()
        started = true
        onStopOrDispose { }
    }
    val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        TcIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.automations_refresh), onClick = viewModel::reload, enabled = !state.loading, modifier = Modifier.testTag("automations-refresh"))
        TcIconButton(PhosphorIcons.Regular.Plus, stringResource(Res.string.automations_new), onClick = onCreate, kind = IconButtonKind.OUTLINED, modifier = Modifier.testTag("automations-new"))
    }
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        if (onBack != null) NavBar(backLabel, onBack, stringResource(Res.string.automations_title), actions = actions)
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().then(if (onBack == null) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
                .padding(start = 16.dp, end = 16.dp, top = if (onBack == null) 8.dp else 0.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onBack == null) LargeTitle(stringResource(Res.string.automations_title), actions = actions)
            if (state.administrator) Segmented(
                listOf(AutomationScope.MINE to stringResource(Res.string.automations_mine), AutomationScope.TEAM to stringResource(Res.string.automations_team)),
                selected = state.scope, onSelect = viewModel::setScope, modifier = Modifier.fillMaxWidth(), tag = { "automations-${it.name.lowercase()}" },
            )
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("automations-error"), action = {
                    TcButton(stringResource(Res.string.action_retry), onClick = viewModel::reload, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("automations-retry"))
                })
            }
            state.actionError?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("automations-action-error")) }
        }
        val listState = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
        LaunchedEffect(nearEnd, state.nextBefore) { if (nearEnd) viewModel.loadMore() }
        LazyColumn(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).testTag("automations"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.automations.orEmpty(), key = { it.id }) { automation ->
                AutomationCard(automation, now, team = state.scope == AutomationScope.TEAM, canToggle = state.canToggle(automation) && automation.id !in state.busy,
                    onOpen = { onOpen(automation) }, onToggle = { viewModel.toggle(automation) })
            }
            item(key = "end") {
                BusyRow(state.loading || state.loadingMore)
                if (state.empty && state.error == null) {
                    EmptyState(PhosphorIcons.Regular.Clock, stringResource(Res.string.automations_empty_title),
                        stringResource(if (state.scope == AutomationScope.TEAM) Res.string.automations_empty_team_body else Res.string.automations_empty_body), Modifier.testTag("automations-empty")) {
                        if (state.scope == AutomationScope.MINE) TcButton(stringResource(Res.string.automations_new), onClick = onCreate, modifier = Modifier.testTag("automations-empty-new"))
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomationCard(automation: AutomationSummary, now: Long, team: Boolean, canToggle: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    val colors = TeamCode.colors
    TcCard(Modifier.testTag("automation-${automation.id}"), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(automation.name, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(triggerLine(automation.trigger), style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("automation-trigger-${automation.id}"))
            }
            Switch(automation.enabled, { onToggle() }, enabled = canToggle, modifier = Modifier.testTag("automation-toggle-${automation.id}"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            AutomationStateChip(automation.enabled, automation.disabledReason, Modifier.testTag("automation-state-${automation.id}"))
            automation.lastRun?.let { run ->
                Chip(runStatusWords(run.status), tone = runStatusTone(run.status), modifier = Modifier.testTag("automation-last-${automation.id}"))
            }
        }
        automation.nextRunAt?.takeIf { automation.enabled }?.let { next ->
            Text(stringResource(Res.string.automation_next_run, untilTime(next, now), formatUtc(next)), style = MetaStyle, color = colors.muted, modifier = Modifier.testTag("automation-next-${automation.id}"))
        }
        failuresLine(automation)?.let { Text(it, color = colors.danger, fontSize = 13.sp, modifier = Modifier.testTag("automation-failures-${automation.id}")) }
        if (team) Text(stringResource(Res.string.automation_owner, automation.ownerName), style = MetaStyle, color = colors.muted)
    }
}
