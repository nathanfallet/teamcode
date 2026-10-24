package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsLeftRight
import com.adamglin.phosphoricons.regular.FileText
import com.adamglin.phosphoricons.regular.Files
import com.adamglin.phosphoricons.regular.GearSix
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.SignIn
import com.adamglin.phosphoricons.regular.TerminalWindow
import com.adamglin.phosphoricons.regular.UserPlus
import com.adamglin.phosphoricons.regular.UserSwitch
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.Artifact
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.presentation.components.handCursor
import me.nathanfallet.teamcode.companion.presentation.components.hostOf
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.artifacts_title
import me.nathanfallet.teamcode.companion.resources.new_session_title
import me.nathanfallet.teamcode.companion.resources.palette_actions
import me.nathanfallet.teamcode.companion.resources.palette_all_artifacts
import me.nathanfallet.teamcode.companion.resources.palette_all_sessions
import me.nathanfallet.teamcode.companion.resources.palette_hint
import me.nathanfallet.teamcode.companion.resources.palette_no_match
import me.nathanfallet.teamcode.companion.resources.palette_placeholder
import me.nathanfallet.teamcode.companion.resources.palette_switch_to
import me.nathanfallet.teamcode.companion.resources.sessions_title
import me.nathanfallet.teamcode.companion.resources.settings_add_account
import me.nathanfallet.teamcode.companion.resources.settings_title
import me.nathanfallet.teamcode.companion.resources.sidebar_signed_out
import me.nathanfallet.teamcode.companion.resources.transfers_title
import org.jetbrains.compose.resources.stringResource

/** The groups of the command palette, in the order it lists them. */
enum class PaletteSection { ACTIONS, SESSIONS, ARTIFACTS }

/** One entry of the command palette: what it shows, the words it is found by, and what it does. */
class PaletteItem(
    val id: String,
    val section: PaletteSection,
    val title: String,
    val icon: ImageVector,
    val detail: String = "",
    /** More words it is found by (a session's agent and repositories, an artifact's owner). */
    val keywords: String = "",
    val run: () -> Unit,
)

/** How many sessions and artifacts the palette lists before anything is typed. */
const val PALETTE_RECENT = 6

private val Spaces = Regex("\\s+")
private val WordBreaks = Regex("[^\\p{L}\\p{N}]+")

/**
 * The entries for [query], grouped by section ([PaletteSection] order) and best first in each: every word typed must
 * appear in the title, the detail or the keywords, ignoring case; a title that starts with the query comes first,
 * then a title with a word that starts with one, then the others. Nothing typed: the actions, then the
 * [PALETTE_RECENT] first sessions and artifacts (the lists are newest first).
 */
fun paletteMatches(items: List<PaletteItem>, query: String): List<PaletteItem> {
    val words = query.trim().lowercase().split(Spaces).filter { it.isNotEmpty() }
    if (words.isEmpty()) return PaletteSection.entries.flatMap { section ->
        items.filter { it.section == section }.let { rows -> if (section == PaletteSection.ACTIONS) rows else rows.take(PALETTE_RECENT) }
    }
    val phrase = words.joinToString(" ")
    fun score(item: PaletteItem): Int? {
        val title = item.title.lowercase()
        val haystack = "$title ${item.detail.lowercase()} ${item.keywords.lowercase()}"
        if (words.any { it !in haystack }) return null
        val titleWords = title.split(WordBreaks)
        return when {
            title.startsWith(phrase) -> 0
            words.all { word -> titleWords.any { it.startsWith(word) } } -> 1
            words.all { it in title } -> 2
            else -> 3
        }
    }
    return items.withIndex().mapNotNull { (index, item) -> score(item)?.let { Triple(item, it, index) } }
        .sortedWith(compareBy({ it.first.section }, { it.second }, { it.third }))
        .map { it.first }
}

/** What the palette's entries do; the layout that opens it decides where each one goes. */
class PaletteActions(
    val onNewSession: (Account) -> Unit,
    val onAllSessions: () -> Unit,
    val onAllArtifacts: () -> Unit,
    val onTransfers: (Account) -> Unit,
    val onSettings: () -> Unit,
    val onAddAccount: () -> Unit,
    val onSwitchAccount: (Account) -> Unit,
    val onSignInAgain: (Account) -> Unit,
    val onOpenSession: (CloudSession) -> Unit,
    val onOpenArtifact: (Artifact) -> Unit,
)

/**
 * The palette's entries: the app's actions (a new session, the full lists, transfers, settings, the accounts), then
 * the [sessions] and [artifacts] the app has loaded (the shared lists, newest first).
 */
@Composable
fun paletteItems(account: Account?, accounts: List<Account>, sessions: List<CloudSession>, artifacts: List<Artifact>, actions: PaletteActions): List<PaletteItem> {
    val signedIn = account?.takeIf { it.signedIn }
    val newSession = stringResource(Res.string.new_session_title)
    val allSessions = stringResource(Res.string.palette_all_sessions)
    val allArtifacts = stringResource(Res.string.palette_all_artifacts)
    val transfers = stringResource(Res.string.transfers_title)
    val settings = stringResource(Res.string.settings_title)
    val addAccount = stringResource(Res.string.settings_add_account)
    val signedOut = stringResource(Res.string.sidebar_signed_out)
    val switchTo = accounts.filter { it.id != account?.id }.associate { it.id to stringResource(Res.string.palette_switch_to, "${it.username} · ${hostOf(it.origin)}") }
    return buildList {
        if (signedIn != null) {
            add(PaletteItem("action-new-session", PaletteSection.ACTIONS, newSession, PhosphorIcons.Regular.Plus) { actions.onNewSession(signedIn) })
            add(PaletteItem("action-all-sessions", PaletteSection.ACTIONS, allSessions, PhosphorIcons.Regular.TerminalWindow, run = actions.onAllSessions))
            add(PaletteItem("action-all-artifacts", PaletteSection.ACTIONS, allArtifacts, PhosphorIcons.Regular.Files, run = actions.onAllArtifacts))
            add(PaletteItem("action-transfers", PaletteSection.ACTIONS, transfers, PhosphorIcons.Regular.ArrowsLeftRight) { actions.onTransfers(signedIn) })
        }
        add(PaletteItem("action-settings", PaletteSection.ACTIONS, settings, PhosphorIcons.Regular.GearSix, keywords = "account preferences theme", run = actions.onSettings))
        for (other in accounts) {
            if (other.id == account?.id && other.signedIn) continue
            if (other.signedIn) add(PaletteItem("action-switch-${other.id}", PaletteSection.ACTIONS, switchTo.getValue(other.id), PhosphorIcons.Regular.UserSwitch, keywords = "account") {
                actions.onSwitchAccount(other)
            })
            else add(PaletteItem("action-sign-in-${other.id}", PaletteSection.ACTIONS, signedOut, PhosphorIcons.Regular.SignIn, detail = "${other.username} · ${hostOf(other.origin)}", keywords = "account") {
                actions.onSignInAgain(other)
            })
        }
        add(PaletteItem("action-add-account", PaletteSection.ACTIONS, addAccount, PhosphorIcons.Regular.UserPlus, keywords = "server sign in", run = actions.onAddAccount))
        if (signedIn != null) {
            for (session in sessions) {
                val where = if (session.remote) session.host?.directoryName.orEmpty() else session.repositories.joinToString(", ")
                add(PaletteItem("session-${session.id}", PaletteSection.SESSIONS, session.name, PhosphorIcons.Regular.TerminalWindow,
                    detail = listOfNotNull(session.agentKind, where.takeIf { it.isNotEmpty() }).joinToString(" · "), keywords = session.model.orEmpty()) { actions.onOpenSession(session) })
            }
            for (artifact in artifacts) {
                add(PaletteItem("artifact-${artifact.id}", PaletteSection.ARTIFACTS, artifact.title, PhosphorIcons.Regular.FileText, detail = artifact.ownerName,
                    keywords = artifact.description) { actions.onOpenArtifact(artifact) })
            }
        }
    }
}

/**
 * The command palette (Cmd+K, Ctrl+K): a field over the window that finds an action, a session or an artifact as one
 * types. Up and Down move the selection, Enter runs it, Escape or a click outside closes. A click runs an entry too.
 * Compose has no command palette or combobox of its own: a [Dialog] holds a [BasicTextField] and a [LazyColumn].
 */
@Composable
fun CommandPalette(items: List<PaletteItem>, onDismiss: () -> Unit) {
    val colors = TeamCode.colors
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    val matches = remember(items, query) { paletteMatches(items, query) }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(query) { selected = 0 }
    // The selection moved by the keyboard stays in view; before the first layout, the list starts at its top anyway.
    // Keyed on the entries' ids: the parent rebuilds the entries (new lambdas) at each recomposition, which must not
    // pull the list back to the selection the member scrolled away from.
    val ids = matches.map { it.id }
    LaunchedEffect(selected, ids) {
        if (matches.isEmpty()) return@LaunchedEffect
        val row = rowIndex(matches, selected.coerceIn(0, matches.lastIndex))
        val visible = list.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return@LaunchedEffect
        val first = visible.first().index
        val last = visible.last().index
        // Its section title too, when it is the section's first entry.
        if (row <= first) list.scrollToItem((row - 1).coerceAtLeast(0))
        else if (row >= last) list.scrollToItem((row - visible.size + 2).coerceAtLeast(0))
    }
    fun run(item: PaletteItem) { onDismiss(); item.run() }
    // Whether the pointer event being handled moved the pointer over the window. A row scrolled under a resting pointer
    // gets a hover event too (Compose sends one when the layout moves under it), which must not take the selection.
    val pointer = remember { object { var last: Offset? = null; var moved = false } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // From inside the dialog: its own window and focus owner exist only once its content is composed.
        LaunchedEffect(Unit) { focus.requestFocus() }
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val position = event.changes.firstOrNull()?.position
                    pointer.moved = event.type == PointerEventType.Move && pointer.last != null && position != pointer.last
                    pointer.last = position
                }
            }
        }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.padding(top = 72.dp, start = 24.dp, end = 24.dp).widthIn(max = 620.dp).fillMaxWidth().clip(Radii.card).background(colors.panel)
                    .border(1.dp, colors.line, Radii.card)
                    // A click inside the palette stays there.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .testTag("palette"),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(PhosphorIcons.Regular.MagnifyingGlass, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
                    val placeholder = stringResource(Res.string.palette_placeholder)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = colors.text, fontSize = 16.sp),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.weight(1f).focusRequester(focus).testTag("palette-field").onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> { if (matches.isNotEmpty()) selected = (selected + 1) % matches.size; true }
                                Key.DirectionUp -> { if (matches.isNotEmpty()) selected = (selected - 1 + matches.size) % matches.size; true }
                                Key.Enter, Key.NumPadEnter -> { matches.getOrNull(selected)?.let(::run); true }
                                Key.Escape -> { onDismiss(); true }
                                else -> false
                            }
                        },
                        decorationBox = { field ->
                            Box {
                                if (query.isEmpty()) Text(placeholder, color = colors.muted, fontSize = 16.sp, maxLines = 1)
                                field()
                            }
                        },
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                if (matches.isEmpty()) Text(stringResource(Res.string.palette_no_match), color = colors.muted, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp).testTag("palette-empty"))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), state = list) {
                    var previous: PaletteSection? = null
                    matches.forEachIndexed { index, item ->
                        if (item.section != previous) {
                            val section = item.section
                            item(key = "section-$section") { PaletteSectionTitle(section) }
                            previous = section
                        }
                        item(key = item.id) { PaletteRow(item, index == selected, onHover = { if (pointer.moved) selected = index }, onClick = { run(item) }) }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                Text(stringResource(Res.string.palette_hint), style = MetaStyle.copy(fontSize = 11.sp), color = colors.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

/** The position in the palette's list (with its section titles) of the [selected] match. */
private fun rowIndex(matches: List<PaletteItem>, selected: Int): Int {
    var row = 0
    var previous: PaletteSection? = null
    matches.forEachIndexed { index, item ->
        if (item.section != previous) { row++; previous = item.section }
        if (index == selected) return row
        row++
    }
    return row
}

@Composable
private fun PaletteSectionTitle(section: PaletteSection) {
    val title = when (section) {
        PaletteSection.ACTIONS -> stringResource(Res.string.palette_actions)
        PaletteSection.SESSIONS -> stringResource(Res.string.sessions_title)
        PaletteSection.ARTIFACTS -> stringResource(Res.string.artifacts_title)
    }
    Text(title.uppercase(), style = MetaStyle.copy(fontSize = 11.sp, letterSpacing = 0.08.em), color = TeamCode.colors.muted, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp))
}

private val PaletteRowShape = RoundedCornerShape(7.dp)

@Composable
private fun PaletteRow(item: PaletteItem, selected: Boolean, onHover: () -> Unit, onClick: () -> Unit) {
    val colors = TeamCode.colors
    val source = remember { MutableInteractionSource() }
    val hover by rememberUpdatedState(onHover)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 36.dp).clip(PaletteRowShape)
            .background(if (selected) colors.chip else Color.Transparent)
            // Selected by the pointer only when it moves (see `pointer`): a pointer resting where the palette opens, or a
            // row scrolled under it by the arrows, would otherwise take the keyboard's selection.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) if (awaitPointerEvent().type == PointerEventType.Move) hover()
                }
            }
            .handCursor()
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .testTag("palette-${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(item.icon, contentDescription = null, tint = if (selected) colors.text else colors.muted, modifier = Modifier.size(16.dp))
        Text(item.title, color = colors.text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        if (item.detail.isNotEmpty()) Text(item.detail, style = MetaStyle.copy(fontSize = 12.sp), color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}
