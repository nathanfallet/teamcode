package me.nathanfallet.teamcode.companion.presentation.screens

import me.nathanfallet.teamcode.companion.presentation.viewmodels.Failure
import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Bell
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Copy
import com.adamglin.phosphoricons.regular.FileText
import com.adamglin.phosphoricons.regular.Files
import com.adamglin.phosphoricons.regular.GearSix
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.SignIn
import com.adamglin.phosphoricons.regular.UserSwitch
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.Artifact
import me.nathanfallet.teamcode.companion.domain.models.AutomationSummary
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.presentation.components.Avatar
import me.nathanfallet.teamcode.companion.presentation.components.BrandGlyph
import me.nathanfallet.teamcode.companion.presentation.components.ContextMenuBox
import me.nathanfallet.teamcode.companion.presentation.components.Dot
import me.nathanfallet.teamcode.companion.presentation.components.IconButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.LocalWindowTitleBar
import me.nathanfallet.teamcode.companion.presentation.components.Skeleton
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.arrowKeyNavigation
import me.nathanfallet.teamcode.companion.presentation.components.focusRing
import me.nathanfallet.teamcode.companion.presentation.components.handCursor
import me.nathanfallet.teamcode.companion.presentation.components.hostOf
import me.nathanfallet.teamcode.companion.presentation.components.hoverHighlight
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.accounts_title
import me.nathanfallet.teamcode.companion.resources.artifact_copy_link
import me.nathanfallet.teamcode.companion.resources.artifacts_session_title
import me.nathanfallet.teamcode.companion.resources.artifacts_title
import me.nathanfallet.teamcode.companion.resources.automations_title
import me.nathanfallet.teamcode.companion.resources.sidebar_automations_empty
import me.nathanfallet.teamcode.companion.resources.new_session_title
import me.nathanfallet.teamcode.companion.resources.sessions_empty_active
import me.nathanfallet.teamcode.companion.resources.sessions_title
import me.nathanfallet.teamcode.companion.resources.settings_add_account
import me.nathanfallet.teamcode.companion.resources.settings_title
import me.nathanfallet.teamcode.companion.resources.sidebar_artifacts_empty
import me.nathanfallet.teamcode.companion.resources.waits_title
import me.nathanfallet.teamcode.companion.resources.palette_search
import me.nathanfallet.teamcode.companion.resources.sidebar_filtered
import me.nathanfallet.teamcode.companion.resources.sidebar_mark_read
import me.nathanfallet.teamcode.companion.resources.sidebar_open
import me.nathanfallet.teamcode.companion.resources.sidebar_see_more
import me.nathanfallet.teamcode.companion.resources.sidebar_signed_out
import me.nathanfallet.teamcode.companion.resources.sidebar_switch_to
import org.jetbrains.compose.resources.stringResource

/** How many sessions and artifacts the sidebar lists before "See more". */
const val SIDEBAR_ROWS = 5

/** How many automations the sidebar lists before "See more". */
const val SIDEBAR_AUTOMATIONS = 3

/** Which part of the content panel the sidebar marks as open. */
sealed interface SidebarSelection {
    data object None : SidebarSelection
    data object AllSessions : SidebarSelection
    data object AllArtifacts : SidebarSelection
    data object AllAutomations : SidebarSelection
    data object AllWaits : SidebarSelection
    data object Settings : SidebarSelection
    data class Session(val id: String) : SidebarSelection
    data class Artifact(val id: String) : SidebarSelection
    data class Automation(val id: String) : SidebarSelection
}

/** What the sidebar shows; the lists hold at most [SIDEBAR_ROWS] rows each. */
data class SidebarState(
    val account: Account?,
    val accounts: List<Account>,
    val sessions: List<CloudSession>,
    val sessionsLoading: Boolean,
    val finishedTurns: Set<String>,
    val artifacts: List<Artifact>,
    val artifactsLoading: Boolean,
    /** Why the artifacts did not load (a server without them, offline), shown in place of the list. */
    val artifactsError: Failure? = null,
    /** The latest automations; null while the server has not said it offers them (`automations.v1`): the section is hidden. */
    val automations: List<AutomationSummary>? = null,
    /** The number of open waits; null while the server has not said it offers them (`waits.v1`): the entry is hidden. */
    val waitsOpen: Int? = null,
    /** More open waits than were read: the badge says "N+". */
    val waitsMore: Boolean = false,
    val selection: SidebarSelection,
    val now: Long,
    /** The filter the shared lists are on when it is not their default (Paused, Team...): shown by the section titles. */
    val sessionsFilter: String? = null,
    val artifactsFilter: String? = null,
)

/** What the sidebar's rows do. */
class SidebarActions(
    val onNewSession: (Account) -> Unit,
    val onOpenSession: (CloudSession) -> Unit,
    val onOpenSessionArtifacts: (CloudSession) -> Unit,
    val onAllSessions: () -> Unit,
    val onOpenArtifact: (Artifact) -> Unit,
    val onAllArtifacts: () -> Unit,
    val onSwitchAccount: (Account) -> Unit,
    val onSignInAgain: (Account) -> Unit,
    val onAddAccount: () -> Unit,
    val onSettings: () -> Unit,
    /** Clears a session's finished-turn mark without opening it. */
    val onMarkSessionRead: (CloudSession) -> Unit = {},
    /** The link "Copy link" copies: the viewer page on the account's own server; null hides the entry. */
    val artifactLink: (Artifact) -> String? = { null },
    val onOpenAutomation: (AutomationSummary) -> Unit = {},
    val onAllAutomations: () -> Unit = {},
    val onOpenWaits: () -> Unit = {},
    /** Opens the command palette; null: no search row. */
    val onSearch: (() -> Unit)? = null,
    /** The palette's shortcut as the platform writes it (⌘K, Ctrl+K), shown on the search row. */
    val searchShortcut: String = "",
)

/**
 * The wide layout's sidebar, from top to bottom: Sessions (the most recent, "See more" opens them all in the content
 * panel), Artifacts (the same), and Accounts pinned at the bottom. Rows take a hover, a hand cursor and a right-click
 * menu; the open item is marked. Up and Down move the keyboard focus from row to row, Enter opens the focused one.
 * The lists are the first rows of the content panel's own (one load and one live feed for both): when the panel's list
 * is filtered, the section title says on what.
 */
@Composable
fun Sidebar(state: SidebarState, actions: SidebarActions, modifier: Modifier = Modifier) {
    val colors = TeamCode.colors
    val account = state.account
    Column(modifier.fillMaxSize().background(colors.sidebar).windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).arrowKeyNavigation().testTag("sidebar")) {
        // Under macOS's title bar, the header sits in the bar, beside the window's buttons, and its button fits the bar's height.
        val titleBar = LocalWindowTitleBar.current
        val inBar = titleBar.height > 0.dp
        Row(
            Modifier.fillMaxWidth().testTag("sidebar-header")
                .then(if (inBar) Modifier.padding(start = titleBar.start, end = 8.dp, bottom = 6.dp).height(titleBar.height) else Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            BrandGlyph(size = 20.dp)
            Text("TeamCode", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
            if (account != null && account.signedIn) TcIconButton(PhosphorIcons.Regular.Plus, stringResource(Res.string.new_session_title), onClick = { actions.onNewSession(account) },
                kind = IconButtonKind.OUTLINED, modifier = Modifier.then(if (inBar) Modifier.size(minOf(titleBar.height, 28.dp) - 2.dp) else Modifier).testTag("sidebar-new-session"))
        }
        actions.onSearch?.let { search ->
            Box(Modifier.padding(horizontal = 8.dp)) {
                SidebarRow(PhosphorIcons.Regular.MagnifyingGlass, stringResource(Res.string.palette_search), selected = false, onClick = search, tag = "sidebar-search", tint = colors.muted,
                    trailing = { Text(actions.searchShortcut, style = MetaStyle.copy(fontSize = 11.sp), color = colors.muted, maxLines = 1) })
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
            // What waits for the member comes first: one row, with the live count of open waits.
            if (account?.signedIn == true && state.waitsOpen != null) {
                SidebarRow(PhosphorIcons.Regular.Bell, stringResource(Res.string.waits_title), selected = state.selection == SidebarSelection.AllWaits, onClick = actions.onOpenWaits,
                    tag = "sidebar-waits", bold = state.waitsOpen > 0, trailing = { if (state.waitsOpen > 0) WaitsBadge(state.waitsOpen, state.waitsMore, Modifier.testTag("sidebar-waits-badge")) })
            }
            SidebarHeader(stringResource(Res.string.sessions_title), state.sessionsFilter)
            when {
                account != null && !account.signedIn -> SidebarRow(PhosphorIcons.Regular.SignIn, stringResource(Res.string.sidebar_signed_out), selected = false, tint = colors.warn,
                    onClick = { actions.onSignInAgain(account) }, tag = "sidebar-sign-in-again")
                state.sessionsLoading && state.sessions.isEmpty() -> repeat(3) { SkeletonRow(it) }
                state.sessions.isEmpty() -> SidebarNote(stringResource(Res.string.sessions_empty_active))
                else -> state.sessions.take(SIDEBAR_ROWS).forEach { session -> SessionRow(session, state, actions) }
            }
            if (account?.signedIn == true) SeeMore(state.selection == SidebarSelection.AllSessions, actions.onAllSessions, "sidebar-all-sessions")

            Spacer(Modifier.height(14.dp))
            SidebarHeader(stringResource(Res.string.artifacts_title), state.artifactsFilter)
            when {
                account != null && !account.signedIn -> Unit
                state.artifactsLoading && state.artifacts.isEmpty() -> repeat(3) { SkeletonRow(it) }
                state.artifacts.isEmpty() && state.artifactsError != null -> SidebarNote(state.artifactsError.words())
                state.artifacts.isEmpty() -> SidebarNote(stringResource(Res.string.sidebar_artifacts_empty))
                else -> state.artifacts.take(SIDEBAR_ROWS).forEach { artifact -> ArtifactRow(artifact, state, actions) }
            }
            if (account?.signedIn == true) SeeMore(state.selection == SidebarSelection.AllArtifacts, actions.onAllArtifacts, "sidebar-all-artifacts")

            if (account?.signedIn == true && state.automations != null) {
                Spacer(Modifier.height(14.dp))
                SidebarHeader(stringResource(Res.string.automations_title))
                if (state.automations.isEmpty()) SidebarNote(stringResource(Res.string.sidebar_automations_empty))
                else state.automations.take(SIDEBAR_AUTOMATIONS).forEach { automation -> AutomationRow(automation, state, actions) }
                SeeMore(state.selection == SidebarSelection.AllAutomations, actions.onAllAutomations, "sidebar-all-automations")
            }
        }
        // Pinned at the bottom, whatever the lists above hold.
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).testTag("sidebar-accounts")) {
            SidebarHeader(stringResource(Res.string.accounts_title))
            state.accounts.forEach { item -> AccountRow(item, active = item.id == account?.id, actions) }
            SidebarRow(PhosphorIcons.Regular.Plus, stringResource(Res.string.settings_add_account), selected = false, onClick = actions.onAddAccount, tag = "sidebar-add-account")
            SidebarRow(PhosphorIcons.Regular.GearSix, stringResource(Res.string.settings_title), selected = state.selection == SidebarSelection.Settings, onClick = actions.onSettings, tag = "sidebar-settings")
        }
    }
}

@Composable
private fun SidebarHeader(text: String, filter: String? = null) {
    val title = filter?.let { stringResource(Res.string.sidebar_filtered, text, it) } ?: text
    Text(title.uppercase(), style = MetaStyle.copy(fontSize = 11.sp, letterSpacing = 0.08.em), color = TeamCode.colors.muted, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp).semantics { heading() })
}

@Composable
private fun SidebarNote(text: String) {
    Text(text, color = TeamCode.colors.muted, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
}

private val RowShape = RoundedCornerShape(7.dp)

/** One clickable line of the sidebar, 32 dp: a leading mark, the label, trailing details. */
@Composable
private fun SidebarRow(
    icon: ImageVector?,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    bold: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = TeamCode.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clip(RowShape)
            .background(if (selected) colors.chip else Color.Transparent)
            .then(if (selected) Modifier else Modifier.hoverHighlight(RowShape))
            .focusRing(RowShape)
            .handCursor()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        when {
            leading != null -> Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { leading() }
            icon != null -> Icon(icon, contentDescription = null, tint = tint ?: if (selected) colors.text else colors.muted, modifier = Modifier.size(16.dp))
        }
        Text(label, color = tint ?: colors.text, fontSize = 13.sp, fontWeight = if (bold || selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

@Composable
private fun SessionRow(session: CloudSession, state: SidebarState, actions: SidebarActions) {
    val colors = TeamCode.colors
    val finished = session.id in state.finishedTurns
    ContextMenuBox(menu = { close ->
        DropdownMenuItem(text = { Text(stringResource(Res.string.sidebar_open)) }, onClick = { close(); actions.onOpenSession(session) })
        DropdownMenuItem(text = { Text(stringResource(Res.string.artifacts_session_title)) }, leadingIcon = { Icon(PhosphorIcons.Regular.Files, null, Modifier.size(16.dp)) },
            onClick = { close(); actions.onOpenSessionArtifacts(session) })
        if (finished) DropdownMenuItem(text = { Text(stringResource(Res.string.sidebar_mark_read)) }, leadingIcon = { Icon(PhosphorIcons.Regular.Check, null, Modifier.size(16.dp)) },
            onClick = { close(); actions.onMarkSessionRead(session) })
    }) {
        SidebarRow(
            icon = null, label = session.name, selected = state.selection == SidebarSelection.Session(session.id), onClick = { actions.onOpenSession(session) },
            tag = "sidebar-session-${session.id}", bold = finished,
            leading = { Dot(stateColor(session.state, finished), size = 7.dp) },
            trailing = { Text(relativeTime(session.lastActivityAt, state.now), style = MetaStyle.copy(fontSize = 11.sp), color = colors.muted, maxLines = 1) },
        )
    }
}

/** The dot of a session row: the accent while it works or once a turn finished, muted when idle, warn and danger. */
@Composable
private fun stateColor(state: SessionState, finished: Boolean): Color {
    val colors = TeamCode.colors
    return when {
        finished || state == SessionState.ACTIVE -> colors.accentText
        state == SessionState.PAUSED || state == SessionState.PAUSING -> colors.warn
        state == SessionState.FAILED -> colors.danger
        state == SessionState.READY -> colors.muted
        else -> colors.line
    }
}

@Suppress("DEPRECATION") // LocalClipboardManager: plain text is all the link needs
@Composable
private fun ArtifactRow(artifact: Artifact, state: SidebarState, actions: SidebarActions) {
    val clipboard = LocalClipboardManager.current
    ContextMenuBox(menu = { close ->
        DropdownMenuItem(text = { Text(stringResource(Res.string.sidebar_open)) }, onClick = { close(); actions.onOpenArtifact(artifact) })
        actions.artifactLink(artifact)?.let { link ->
            DropdownMenuItem(text = { Text(stringResource(Res.string.artifact_copy_link)) }, leadingIcon = { Icon(PhosphorIcons.Regular.Copy, null, Modifier.size(16.dp)) },
                onClick = { close(); clipboard.setText(AnnotatedString(link)) })
        }
    }) {
        SidebarRow(
            PhosphorIcons.Regular.FileText, artifact.title, selected = state.selection == SidebarSelection.Artifact(artifact.id), onClick = { actions.onOpenArtifact(artifact) },
            tag = "sidebar-artifact-${artifact.id}",
            trailing = { Text(relativeTime(artifact.updatedAt, state.now), style = MetaStyle.copy(fontSize = 11.sp), color = TeamCode.colors.muted, maxLines = 1) },
        )
    }
}

@Composable
private fun AutomationRow(automation: AutomationSummary, state: SidebarState, actions: SidebarActions) {
    val colors = TeamCode.colors
    SidebarRow(
        icon = null, label = automation.name, selected = state.selection == SidebarSelection.Automation(automation.id), onClick = { actions.onOpenAutomation(automation) },
        tag = "sidebar-automation-${automation.id}",
        leading = { Dot(if (automation.consecutiveFailures > 0) colors.danger else if (automation.enabled) colors.accentText else colors.muted, size = 7.dp) },
    )
}

@Composable
private fun SeeMore(selected: Boolean, onClick: () -> Unit, tag: String) {
    val colors = TeamCode.colors
    SidebarRow(
        null, stringResource(Res.string.sidebar_see_more), selected = selected, onClick = onClick, tag = tag, tint = colors.accentText,
        trailing = { Icon(PhosphorIcons.Regular.CaretRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(12.dp)) },
    )
}

@Composable
private fun AccountRow(account: Account, active: Boolean, actions: SidebarActions) {
    val colors = TeamCode.colors
    ContextMenuBox(menu = { close ->
        if (!active && account.signedIn) DropdownMenuItem(text = { Text(stringResource(Res.string.sidebar_switch_to)) },
            leadingIcon = { Icon(PhosphorIcons.Regular.UserSwitch, null, Modifier.size(16.dp)) }, onClick = { close(); actions.onSwitchAccount(account) })
        if (!account.signedIn) DropdownMenuItem(text = { Text(stringResource(Res.string.sidebar_signed_out)) },
            leadingIcon = { Icon(PhosphorIcons.Regular.SignIn, null, Modifier.size(16.dp)) }, onClick = { close(); actions.onSignInAgain(account) })
        DropdownMenuItem(text = { Text(stringResource(Res.string.settings_title)) }, leadingIcon = { Icon(PhosphorIcons.Regular.GearSix, null, Modifier.size(16.dp)) },
            onClick = { close(); actions.onSettings() })
    }) {
        SidebarRow(
            null, account.username, selected = false, tag = "sidebar-account-${account.username}-${hostOf(account.origin)}", bold = active,
            onClick = { if (!account.signedIn) actions.onSignInAgain(account) else if (!active) actions.onSwitchAccount(account) else actions.onSettings() },
            leading = { Avatar(account.username, size = 18.dp, dimmed = !account.signedIn) },
            trailing = {
                Text(hostOf(account.origin), style = MetaStyle.copy(fontSize = 11.sp), color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (active) Icon(PhosphorIcons.Regular.Check, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(14.dp))
            },
        )
    }
}

@Composable
private fun SkeletonRow(index: Int) {
    Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(140.dp + (index * 23 % 50).dp)) { Skeleton(1f, 10.dp) }
    }
}
