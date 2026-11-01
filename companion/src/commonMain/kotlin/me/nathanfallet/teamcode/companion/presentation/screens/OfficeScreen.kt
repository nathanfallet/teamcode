package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.X
import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.SessionWaiting
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.domain.usecases.office.ActivityPeeks
import me.nathanfallet.teamcode.companion.domain.usecases.office.OfficeAgent
import me.nathanfallet.teamcode.companion.domain.usecases.office.OfficeMood
import me.nathanfallet.teamcode.companion.domain.usecases.office.OfficeScene
import me.nathanfallet.teamcode.companion.domain.usecases.office.OfficeZone
import me.nathanfallet.teamcode.companion.domain.usecases.office.SessionActivity
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.LoadTranscriptUseCase
import me.nathanfallet.teamcode.companion.presentation.components.BackLink
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.IconButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.handCursor
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactsRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionDestination
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionRoute
import me.nathanfallet.teamcode.companion.presentation.office.Frame
import me.nathanfallet.teamcode.companion.presentation.office.Grid
import me.nathanfallet.teamcode.companion.presentation.office.OfficeBoard
import me.nathanfallet.teamcode.companion.presentation.office.OfficePlan
import me.nathanfallet.teamcode.companion.presentation.office.OfficeSeater
import me.nathanfallet.teamcode.companion.presentation.office.Seating
import me.nathanfallet.teamcode.companion.presentation.office.Spot
import me.nathanfallet.teamcode.companion.presentation.office.Walkers
import me.nathanfallet.teamcode.companion.presentation.office.hitArea
import me.nathanfallet.teamcode.companion.presentation.office.moodColor
import me.nathanfallet.teamcode.companion.presentation.office.paintOffice
import me.nathanfallet.teamcode.companion.presentation.theme.ChipStyle
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionsViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.new_session_title
import me.nathanfallet.teamcode.companion.resources.office_card_agent
import me.nathanfallet.teamcode.companion.resources.office_card_doing
import me.nathanfallet.teamcode.companion.resources.office_card_last_activity
import me.nathanfallet.teamcode.companion.resources.office_card_started
import me.nathanfallet.teamcode.companion.resources.office_card_steps
import me.nathanfallet.teamcode.companion.resources.office_card_subagents
import me.nathanfallet.teamcode.companion.resources.office_card_where
import me.nathanfallet.teamcode.companion.resources.office_close_panel
import me.nathanfallet.teamcode.companion.resources.office_empty
import me.nathanfallet.teamcode.companion.resources.office_filter_all
import me.nathanfallet.teamcode.companion.resources.office_hint_open
import me.nathanfallet.teamcode.companion.resources.office_mood_done
import me.nathanfallet.teamcode.companion.resources.office_mood_error
import me.nathanfallet.teamcode.companion.resources.office_mood_idle
import me.nathanfallet.teamcode.companion.resources.office_mood_paused
import me.nathanfallet.teamcode.companion.resources.office_mood_starting
import me.nathanfallet.teamcode.companion.resources.office_mood_waiting
import me.nathanfallet.teamcode.companion.resources.office_mood_working
import me.nathanfallet.teamcode.companion.resources.office_more
import me.nathanfallet.teamcode.companion.resources.office_open_session
import me.nathanfallet.teamcode.companion.resources.office_steps_value
import me.nathanfallet.teamcode.companion.resources.office_summary
import me.nathanfallet.teamcode.companion.resources.office_title
import me.nathanfallet.teamcode.companion.resources.office_waiting_permission
import me.nathanfallet.teamcode.companion.resources.office_waiting_question
import me.nathanfallet.teamcode.companion.resources.office_where_cloud
import me.nathanfallet.teamcode.companion.resources.office_where_offline
import me.nathanfallet.teamcode.companion.resources.office_zone_cloud
import me.nathanfallet.teamcode.companion.resources.office_zone_pantry
import me.nathanfallet.teamcode.companion.resources.office_zone_remote
import me.nathanfallet.teamcode.companion.resources.automations_title
import me.nathanfallet.teamcode.companion.resources.silkscreen_bold
import me.nathanfallet.teamcode.companion.resources.silkscreen_regular
import me.nathanfallet.teamcode.companion.resources.workflows_title
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.math.max

/** What the pointer is over: an agent, or one of the boards. */
private sealed interface Hit {
    data class Agent(val id: String) : Hit
    data class Board(val board: OfficeBoard) : Hit
}

/** What changes every frame without a recomposition: where the agents are, and which of them walk. */
private class Live {
    var spots: Map<String, Spot> = emptyMap()
    var walking: Set<String> = emptySet()
}

/** Where the pantry's standing places start and how far apart they are, in the office's pixels ([OfficePlan.pantry]). */
private const val PANTRY_LEFT = 12
private const val PANTRY_STEP = 14


/**
 * The sessions as a pixel-art office: each session is an agent at a desk, in the room of where it runs (the members'
 * devices, the cloud). The desk says what the agent does, the card on hover or tap says who it is and where it stands,
 * and a click opens its session, in place of the office. [sessionsViewModel] is the app's shared
 * list, already followed by the sidebar.
 */
@Composable
fun OfficeScreen(
    sessionsViewModel: SessionsViewModel,
    onOpenSession: (accountId: String, sessionId: String) -> Unit,
    onOpenArtifacts: (ArtifactsRoute) -> Unit,
    onNewSession: (Account) -> Unit,
    onOpenWorkflows: (() -> Unit)? = null,
    onOpenAutomations: (() -> Unit)? = null,
    /** Pushed over the phone's tabs: the way back, named [backLabel]; null in the wide layout's panel (the sidebar is the way out). */
    onBack: (() -> Unit)? = null,
    backLabel: String = "",
    clock: Clock = koinInject(),
    loadTranscript: LoadTranscriptUseCase = koinInject(),
) {
    val colors = TeamCode.colors
    val state by sessionsViewModel.uiState.collectAsStateWithLifecycle()
    val account = state.account
    val sessions = state.list?.sessions.orEmpty()
    val finished = state.list?.finishedTurns.orEmpty()
    val agents = remember(sessions, finished) { OfficeScene.agents(sessions, finished) }
    val seater = remember(account?.id) { OfficeSeater() }
    val seating = remember(agents, seater) { seater.seat(agents) }
    val counts = remember(agents) { OfficeScene.counts(agents) }
    val now = rememberNow(clock)
    val font = pixelFont()

    var focus by remember { mutableStateOf<OfficeMood?>(null) }
    var hoveredId by remember { mutableStateOf<String?>(null) }
    var hoveredBoard by remember { mutableStateOf<OfficeBoard?>(null) }
    var previewId by remember { mutableStateOf<String?>(null) }
    val hoverAt = remember { mutableStateOf(Offset.Zero) }

    // The frame loop: ten frames a second while the app is in the foreground, a redraw each and no recomposition.
    var frame by remember { mutableIntStateOf(0) }
    val live = remember { Live() }
    val walkers = remember(account?.id) { Walkers() }
    var walkingIds by remember { mutableStateOf(emptySet<String>()) }
    var foreground by remember { mutableStateOf(true) }
    LifecycleStartEffect(Unit) {
        foreground = true
        onStopOrDispose { foreground = false }
    }
    val targets = remember(seating) { seating.placements.associate { it.agent.id to it.spot } }
    val targetsNow by rememberUpdatedState(targets)
    LaunchedEffect(foreground, walkers) {
        while (foreground) {
            val walking = walkers.step(targetsNow)
            live.spots = targetsNow.mapValues { (id, spot) -> walkers.at(id) ?: spot }
            live.walking = walking
            if (walking != walkingIds) walkingIds = walking
            frame++
            delay(100)
        }
    }

    // What an agent does is read when the pointer rests on it, one session at a time.
    val scope = rememberCoroutineScope()
    val peeks = remember(account?.id) { ActivityPeeks(load = { accountId, sessionId -> loadTranscript(accountId, sessionId) }, scope = scope, now = clock::nowMillis) }
    val activity by peeks.known.collectAsStateWithLifecycle()
    val peekId = hoveredId ?: previewId
    LaunchedEffect(peekId, account?.id) {
        val id = peekId ?: return@LaunchedEffect
        val accountId = account?.id ?: return@LaunchedEffect
        delay(250)
        peeks.peek(accountId, id)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background).testTag("office")) {
                fun open(id: String) {
            val signedIn = account ?: return
            sessionsViewModel.opened(id)
            previewId = null
            onOpenSession(signedIn.id, id)
        }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                OfficeHeader(font, counts, agents.size, focus, onBack, backLabel) { focus = if (it == focus) null else it }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val density = LocalDensity.current
                    val widthPx = constraints.maxWidth
                    val heightPx = constraints.maxHeight
                    // The whole office is always in view: the largest whole scale that fits both ways (a fraction when the window is tiny).
                    val fit = minOf(widthPx / OfficePlan.WIDTH.toFloat(), heightPx / seating.plan.height.toFloat())
                    val scale = if (fit >= 1f) fit.toInt().toFloat() else fit
                    val grid = Grid(scale, ((widthPx - OfficePlan.WIDTH * scale) / 2).toInt().toFloat(), ((heightPx - seating.plan.height * scale) / 2).toInt().toFloat())
                    val gridNow by rememberUpdatedState(grid)
                    val seatingNow by rememberUpdatedState(seating)
                    val focusNow by rememberUpdatedState(focus)
                    val boardsNow by rememberUpdatedState(Pair(onOpenWorkflows != null, onOpenAutomations != null))
                    fun hit(at: Offset): Hit? = pickAt(at, gridNow, seatingNow, live, focusNow, boardsNow.first, boardsNow.second)
                    val summary = stringResource(Res.string.office_summary, agents.size, counts[OfficeMood.WAITING] ?: 0)
                    run {
                        Box(Modifier.fillMaxSize()) {
                            Canvas(
                                Modifier.fillMaxSize().testTag("office-canvas").semantics { contentDescription = summary }
                                    .then(if (hoveredId != null || hoveredBoard != null) Modifier.handCursor() else Modifier)
                                    .pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            var pressedAt: Offset? = null
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.first()
                                                when (event.type) {
                                                    PointerEventType.Move, PointerEventType.Enter -> if (change.type == PointerType.Mouse) {
                                                        val over = hit(change.position)
                                                        val id = (over as? Hit.Agent)?.id
                                                        if (id != hoveredId) hoveredId = id
                                                        val board = (over as? Hit.Board)?.board
                                                        if (board != hoveredBoard) hoveredBoard = board
                                                        hoverAt.value = change.position
                                                    }
                                                    PointerEventType.Exit -> { hoveredId = null; hoveredBoard = null }
                                                    PointerEventType.Press -> pressedAt = change.position
                                                    PointerEventType.Release -> {
                                                        val start = pressedAt
                                                        pressedAt = null
                                                        // A press that stayed in place is a click; one that moved is not a click.
                                                        if (start != null && (change.position - start).getDistance() < viewConfiguration.touchSlop) {
                                                            when (val over = hit(change.position)) {
                                                                is Hit.Agent -> if (change.type == PointerType.Mouse || previewId == over.id) open(over.id) else previewId = over.id
                                                                is Hit.Board -> { previewId = null; if (over.board == OfficeBoard.WORKFLOWS) onOpenWorkflows?.invoke() else onOpenAutomations?.invoke() }
                                                                null -> previewId = null
                                                            }
                                                        }
                                                    }
                                                    else -> Unit
                                                }
                                            }
                                        }
                                    },
                            ) {
                                paintOffice(
                                    grid,
                                    Frame(seating, live.spots, live.walking, frame, colors.isDark, focus, hoveredId, previewId, onOpenWorkflows != null, onOpenAutomations != null, hoveredBoard),
                                )
                            }
                            Labels(grid, seating.plan, font, onOpenWorkflows != null, onOpenAutomations != null)
                            Nameplates(grid, seating, walkingIds, font, focus)
                            if (seating.hidden > 0) Text(
                                stringResource(Res.string.office_more, seating.hidden), color = colors.muted, style = ChipStyle,
                                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).testTag("office-more"),
                            )
                            // The card follows the mouse; it is never under it, and stays inside the office.
                            hoveredId?.let { id ->
                                agents.firstOrNull { it.id == id }?.let { agent ->
                                    Box(
                                        Modifier.layout { measurable, constraints ->
                                            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                                            val gap = 16.dp.roundToPx()
                                            val at = hoverAt.value
                                            val right = at.x.toInt() + gap
                                            val x = (if (right + placeable.width > constraints.maxWidth) at.x.toInt() - gap - placeable.width else right).coerceIn(0, max(0, constraints.maxWidth - placeable.width))
                                            val y = (at.y.toInt() + gap).coerceIn(0, max(0, constraints.maxHeight - placeable.height))
                                            layout(placeable.width, placeable.height) { placeable.place(x, y) }
                                        },
                                    ) { AgentCard(agent, activity[id], now, font, hint = true, modifier = Modifier.width(262.dp).testTag("office-card")) }
                                }
                            }
                        }
                    }
                    if (agents.isEmpty() && !state.loading) EmptyOffice(account, font, onNewSession, Modifier.align(Alignment.Center))
                    // A touch screen has no hover: a tap shows the card, here, with the way into the session.
                    previewId?.let { id ->
                        agents.firstOrNull { it.id == id }?.let { agent ->
                            AgentCard(
                                agent, activity[id], now, font, hint = false, onOpen = { open(id) }, onClose = { previewId = null },
                                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 360.dp).fillMaxWidth().testTag("office-preview"),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The pixel font of the office: Silkscreen (OFL), bundled with the app. */
@Composable
private fun pixelFont(): FontFamily = FontFamily(Font(Res.font.silkscreen_regular, FontWeight.Normal), Font(Res.font.silkscreen_bold, FontWeight.Bold))

/** What is under [at] (canvas pixels): the topmost agent, else a board. Agents out of focus cannot be picked. */
private fun pickAt(at: Offset, grid: Grid, seating: Seating, live: Live, focus: OfficeMood?, workflows: Boolean, automations: Boolean): Hit? {
    val spot = Spot(((at.x - grid.left) / grid.scale).toInt(), ((at.y - grid.top) / grid.scale).toInt())
    for (placement in seating.placements.asReversed()) {
        if (focus != null && placement.agent.mood != focus) continue
        val id = placement.agent.id
        if (spot in hitArea(placement, live.spots[id] ?: placement.spot, id in live.walking)) return Hit.Agent(id)
    }
    if (workflows && spot in seating.plan.workflowsBoard) return Hit.Board(OfficeBoard.WORKFLOWS)
    if (automations && spot in seating.plan.automationsBoard) return Hit.Board(OfficeBoard.AUTOMATIONS)
    return null
}

@Composable
private fun OfficeHeader(font: FontFamily, counts: Map<OfficeMood, Int>, total: Int, focus: OfficeMood?, onBack: (() -> Unit)?, backLabel: String, onFocus: (OfficeMood) -> Unit) {
    val colors = TeamCode.colors
    Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (onBack != null) BackLink(backLabel, onBack, Modifier.widthIn(max = 120.dp))
        Text(stringResource(Res.string.office_title), color = colors.text, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MoodPill(stringResource(Res.string.office_filter_all), total, null, focus == null, "office-filter-all") { focus?.let(onFocus) }
            OfficeMood.entries.filter { (counts[it] ?: 0) > 0 }.forEach { mood ->
                MoodPill(moodLabel(mood), counts.getValue(mood), moodColor(mood), focus == mood, "office-filter-${mood.name.lowercase()}") { onFocus(mood) }
            }
        }
    }
}

@Composable
private fun MoodPill(label: String, count: Int, color: Color?, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = TeamCode.colors
    Row(
        Modifier.clip(Radii.pill).background(if (selected) colors.text else colors.chip).border(1.dp, colors.line, Radii.pill)
            .clickable(role = Role.Button, onClick = onClick).handCursor().padding(horizontal = 10.dp, vertical = 6.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (color != null) Box(Modifier.size(8.dp).background(color))
        Text("$label $count", style = ChipStyle, color = if (selected) colors.background else colors.text, maxLines = 1)
    }
}

/** The names of the rooms, in the office's pixels. */
@Composable
private fun Labels(grid: Grid, plan: OfficePlan, font: FontFamily, workflows: Boolean, automations: Boolean) {
    val color = if (TeamCode.colors.isDark) Color(0xFFD6D2F5) else Color(0xFF3A3558)
    @Composable
    fun label(text: String, x: Int, y: Int) = PixelText(text.uppercase(), grid, x, y, font, color, bold = true, size = 4.4f)
    label(stringResource(Res.string.office_zone_remote), 14, 36)
    label(stringResource(Res.string.office_zone_cloud), 202, 39)
    label(stringResource(Res.string.office_zone_pantry), 8, plan.lowerTop - 4)
    if (workflows) label(stringResource(Res.string.workflows_title), plan.workflowsBoard.x, plan.workflowsBoard.y - 9)
    if (automations) label(stringResource(Res.string.automations_title), plan.automationsBoard.x, plan.automationsBoard.y - 14)
}

@Composable
private fun PixelText(text: String, grid: Grid, x: Int, y: Int, font: FontFamily, color: Color, bold: Boolean, size: Float) {
    val density = LocalDensity.current
    Text(
        text, color = color, fontFamily = font, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, letterSpacing = 0.1.sp,
        fontSize = with(density) { (size * grid.scale).toSp() }, maxLines = 1,
        modifier = Modifier.offset { IntOffset((grid.left + x * grid.scale).toInt(), (grid.top + y * grid.scale).toInt()) },
    )
}

/** The name above each agent, under its desk or at the pantry; hidden while it walks, dimmed when out of focus. */
@Composable
private fun Nameplates(grid: Grid, seating: Seating, walking: Set<String>, font: FontFamily, focus: OfficeMood?) {
    val density = LocalDensity.current
    val colors = TeamCode.colors
    for (placement in seating.placements) {
        val agent = placement.agent
        if (agent.id in walking) continue
        val seated = placement.desk != null && placement.spot == seating.plan.chair(placement.desk)
        // At the pantry the agents stand a few pixels apart: narrower plates, every other one a line lower, so none covers another.
        val slot = (placement.spot.x - PANTRY_LEFT) / PANTRY_STEP
        val x = if (seated) placement.desk!!.x + 18 else placement.spot.x + 5
        val y = if (seated) placement.desk!!.y + 20 else placement.spot.y + 21 + (slot and 1) * 7
        val width = if (seated) 46 else 27
        Box(
            Modifier.offset { IntOffset((grid.left + (x - width / 2) * grid.scale).toInt(), (grid.top + y * grid.scale).toInt()) }
                .width(with(density) { (width * grid.scale).toDp() }).then(if (focus != null && agent.mood != focus) Modifier.background(Color.Transparent) else Modifier),
            contentAlignment = Alignment.TopCenter,
        ) {
            Row(
                Modifier.background(if (focus != null && agent.mood != focus) colors.muted else Color(0xFF1D1B30)).padding(horizontal = with(density) { (1.2f * grid.scale).toDp() }, vertical = with(density) { (0.7f * grid.scale).toDp() }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(with(density) { (1.4f * grid.scale).toDp() }),
            ) {
                Box(Modifier.size(with(density) { (1.8f * grid.scale).toDp() }).background(moodColor(agent.mood)))
                Text(
                    agent.session.name, color = Color(0xFFF4F2FF), fontFamily = font, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = with(density) { (3.9f * grid.scale).toSp() }, modifier = Modifier.widthIn(max = with(density) { (width * grid.scale * 0.8f).toDp() }),
                )
            }
        }
    }
}

@Composable
private fun EmptyOffice(account: Account?, font: FontFamily, onNewSession: (Account) -> Unit, modifier: Modifier) {
    val colors = TeamCode.colors
    Column(
        modifier.padding(24.dp).widthIn(max = 360.dp).background(colors.panel, Radii.card).border(1.dp, colors.line, Radii.card).padding(16.dp).testTag("office-empty"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.office_empty), color = colors.text, fontSize = 14.sp)
        if (account?.signedIn == true) TcButton(stringResource(Res.string.new_session_title), onClick = { onNewSession(account) }, kind = ButtonKind.PRIMARY, small = true, fill = false)
    }
}

@Composable
private fun moodLabel(mood: OfficeMood): String = stringResource(
    when (mood) {
        OfficeMood.WORKING -> Res.string.office_mood_working
        OfficeMood.WAITING -> Res.string.office_mood_waiting
        OfficeMood.ERROR -> Res.string.office_mood_error
        OfficeMood.DONE -> Res.string.office_mood_done
        OfficeMood.IDLE -> Res.string.office_mood_idle
        OfficeMood.PAUSED -> Res.string.office_mood_paused
        OfficeMood.STARTING -> Res.string.office_mood_starting
    },
)

private val CardInk = Color(0xFF1D1B30)
private val CardText = Color(0xFFF4F2FF)
private val CardMuted = Color(0xFFB9B5D8)
private val CardLine = Color(0xFF3A3760)

/**
 * Who an agent is and what it does: the card of a hover ([hint]) or of a tap ([onOpen] and [onClose]). Dark in both themes,
 * like a screen in the office. What the agent is doing comes once the session's newest events are read; until then the
 * card shows what the session list already says.
 */
@Composable
private fun AgentCard(
    agent: OfficeAgent,
    activity: SessionActivity?,
    now: Long,
    font: FontFamily,
    hint: Boolean,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val session = agent.session
    val pill = moodColor(agent.mood)
    Column(modifier.background(CardInk).border(2.dp, CardText).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(session.name, color = CardText, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                moodLabel(agent.mood).uppercase(), color = if (agent.mood == OfficeMood.WAITING) Color(0xFF1A1200) else Color.White, fontFamily = font, fontWeight = FontWeight.Bold,
                fontSize = 9.sp, letterSpacing = 0.1.sp, maxLines = 1, modifier = Modifier.background(pill).padding(horizontal = 6.dp, vertical = 4.dp),
            )
            if (onClose != null) TcIconButton(PhosphorIcons.Regular.X, stringResource(Res.string.office_close_panel), onClose, kind = IconButtonKind.PLAIN, tint = CardText)
        }
        if (agent.mood == OfficeMood.WAITING) session.waiting?.let { waiting ->
            Text(
                stringResource(if (waiting.reason == SessionWaiting.QUESTION) Res.string.office_waiting_question else Res.string.office_waiting_permission),
                color = Color(0xFFE8A91B), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        activity?.let { read ->
            val doing = listOfNotNull(read.tool, read.summary).joinToString(" · ")
            if (doing.isNotEmpty()) CardRow(stringResource(Res.string.office_card_doing), doing)
            read.steps?.let { steps ->
                CardRow(stringResource(Res.string.office_card_steps), stringResource(Res.string.office_steps_value, steps.done, steps.total))
                Box(Modifier.fillMaxWidth().height(8.dp).border(1.dp, CardMuted)) { Box(Modifier.fillMaxHeight().fillMaxWidth(steps.done.toFloat() / steps.total).background(pill)) }
                steps.current?.let { Text(it, color = CardText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (read.subagents > 0) CardRow(stringResource(Res.string.office_card_subagents), read.subagents.toString())
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CardLine))
        CardRow(stringResource(Res.string.office_card_agent), listOfNotNull(session.agentKind, session.model, session.effort).joinToString(" · "))
        CardRow(stringResource(Res.string.office_card_where), where(agent))
        session.repositories.firstOrNull()?.let { CardRow("Git", if (session.repositories.size > 1) "$it +${session.repositories.size - 1}" else it) }
        CardRow(stringResource(Res.string.office_card_last_activity), relativeTime(session.lastActivityAt, now))
        CardRow(stringResource(Res.string.office_card_started), relativeTime(session.createdAt, now))
        activity?.lastMessage?.let { Text(it, color = CardMuted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
        if (hint) Text(stringResource(Res.string.office_hint_open).uppercase(), color = CardMuted, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 0.12.sp, modifier = Modifier.padding(top = 6.dp))
        onOpen?.let { TcButton(stringResource(Res.string.office_open_session), it, kind = ButtonKind.PRIMARY, small = true, modifier = Modifier.padding(top = 6.dp).testTag("office-open")) }
    }
}

@Composable
private fun CardRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = CardMuted, fontSize = 12.sp, maxLines = 1)
        Text(value, color = CardText, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun where(agent: OfficeAgent): String {
    val host = agent.session.host
    return when {
        agent.zone == OfficeZone.CLOUD -> stringResource(Res.string.office_where_cloud)
        host?.deviceName == null -> stringResource(Res.string.office_zone_remote)
        host.online -> host.deviceName
        else -> stringResource(Res.string.office_where_offline, host.deviceName)
    }
}
