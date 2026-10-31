package me.nathanfallet.teamcode.companion.presentation.office

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import me.nathanfallet.teamcode.companion.domain.usecases.office.OfficeMood
import me.nathanfallet.teamcode.companion.domain.usecases.office.OfficeZone

/** Where the office's pixels land on the canvas: [scale] device pixels each (a whole number, so edges stay crisp), from ([left], [top]). */
class Grid(val scale: Float, val left: Float, val top: Float)

/** What a frame shows beyond the seating. */
class Frame(
    val seating: Seating,
    val spots: Map<String, Spot>,
    val walking: Set<String>,
    val tick: Int,
    val dark: Boolean,
    /** Only agents in this mood are bright; the others are dimmed. Null: all bright. */
    val focus: OfficeMood?,
    val hovered: String?,
    val opened: String?,
    val workflows: Boolean,
    val automations: Boolean,
    val hoveredBoard: OfficeBoard?,
)

/** The two boards of the office that open a screen. */
enum class OfficeBoard { WORKFLOWS, AUTOMATIONS }

/** The colour of each mood's LED, bubble and legend dot. */
fun moodColor(mood: OfficeMood): Color = when (mood) {
    OfficeMood.WORKING -> Color(0xFF3B82D6)
    OfficeMood.STARTING -> Color(0xFF8B5CD6)
    OfficeMood.WAITING -> Color(0xFFE8A91B)
    OfficeMood.ERROR -> Color(0xFFD64545)
    OfficeMood.DONE -> Color(0xFF2F9E6B)
    OfficeMood.IDLE, OfficeMood.PAUSED -> Color(0xFF8A87AD)
}

private class Palette(
    val floorA: Color, val floorB: Color, val wall: Color, val wallDark: Color, val sky: Color, val sun: Color,
    val deskTop: Color, val deskEdge: Color, val cloud: Color, val glass: Color, val rug: Color, val night: Boolean,
)

private val DayPalette = Palette(
    Color(0xFFD8C9A9), Color(0xFFCFBF9D), Color(0xFFB7C2D3), Color(0xFF8E9BB0), Color(0xFF9AD1EF), Color(0xFFFFF3B8),
    Color(0xFFB98557), Color(0xFF8F6139), Color(0xFF9FD0EA), Color(0xFF5DA6CF), Color(0xFFC58A8A), false,
)
private val NightPalette = Palette(
    Color(0xFF2D2A3D), Color(0xFF292638), Color(0xFF3B4256), Color(0xFF2B3042), Color(0xFF14213F), Color(0xFFE9EDFF),
    Color(0xFF8A6240), Color(0xFF6A4A30), Color(0xFF2E5A7A), Color(0xFF6FB4D8), Color(0xFF4A3A5C), true,
)

private val Ink = Color(0xFF1D1B30)
private val Metal = Color(0xFF2B2F3A)
private val Screen = Color(0xFF1D2029)
private val Grey = Color(0xFF4A4F60)
private val Skin = Color(0xFFF0C9A0)
private val Pants = Color(0xFF3B4256)
private val Mug = Color(0xFFF4F1EA)
private val Shadow = Color(0x0D000000)
private val Hairs = listOf(0xFF5A3825, 0xFFC9A227, 0xFF222222, 0xFF8A2F2F, 0xFF2B4A7A, 0xFFA05A2C, 0xFF3D6B4F, 0xFF7A3B8A).map { Color(it) }

/** The hair of an agent, stable for a session. */
internal fun hairOf(id: String): Color = Hairs[(id.hashCode() and Int.MAX_VALUE) % Hairs.size]

/** The shirt of an agent: Claude's orange, Codex's teal, another agent's grey. */
internal fun shirtOf(agentKind: String): Color = when {
    agentKind.contains("codex", ignoreCase = true) -> Color(0xFF2F9C8A)
    agentKind.contains("claude", ignoreCase = true) -> Color(0xFFD9774F)
    else -> Color(0xFF7D7A96)
}

private fun DrawScope.r(g: Grid, x: Int, y: Int, w: Int, h: Int, c: Color) {
    drawRect(c, Offset(g.left + x * g.scale, g.top + y * g.scale), Size(w * g.scale, h * g.scale))
}

/** Draws one frame of the office on the canvas, in [grid]. */
fun DrawScope.paintOffice(g: Grid, f: Frame) {
    val p = if (f.dark) NightPalette else DayPalette
    val plan = f.seating.plan
    val h = plan.height
    val w = OfficePlan.WIDTH
    val byId = f.seating.placements.associateBy { it.agent.id }

    // The floor and the wall go on past the office's own width, to the edges of the canvas.
    drawRect(p.floorB, Offset(0f, g.top + 34 * g.scale), Size(size.width, size.height))
    drawRect(p.wall, Offset(0f, 0f), Size(size.width, g.top + 32 * g.scale))
    drawRect(p.wallDark, Offset(0f, g.top + 32 * g.scale), Size(size.width, 3 * g.scale))
    // Floor and wall.
    r(g, 0, 34, w, h - 34, p.floorB)
    // The tiles go on to the canvas's edges too: a wide window is not framed by a flat colour.
    val beyond = (g.left / g.scale).toInt() / 8 * 8 + 8
    var ty = 34
    while (ty < h) { var tx = -beyond; while (tx < w + beyond) { if (((tx shr 3) + (ty shr 3)) and 1 == 0) r(g, tx, ty, 8, 8, p.floorA); tx += 8 }; ty += 8 }
    r(g, 0, 0, w, 32, p.wall); r(g, 0, 32, w, 3, p.wallDark)
    for (x in 0 until w step 16) r(g, x, 0, 1, 32, Shadow)
    for (x in intArrayOf(14, 60, 106, 206, 262)) window(g, p, x, 6, f.tick)
    poster(g, 150, 4)

    // The cloud's glass room, with its racks.
    drawRect(p.cloud.copy(alpha = .3f), Offset(g.left + plan.cloudRoom.x * g.scale, g.top + plan.cloudRoom.y * g.scale), Size(plan.cloudRoom.w * g.scale, plan.cloudRoom.h * g.scale))
    val room = plan.cloudRoom
    r(g, room.x, room.y, room.w, 2, p.glass); r(g, room.x, room.y + room.h - 2, room.w, 2, p.glass); r(g, room.x + room.w - 2, room.y, 2, room.h, p.glass)
    r(g, room.x, room.y, 2, 70, p.glass); r(g, room.x, room.y + 90, 2, room.h - 90, p.glass)
    var rackY = room.y + 8; var rackIndex = 0
    while (rackY + 14 < room.y + room.h - 4) { rack(g, 296, rackY, f.tick + rackIndex * 3); rackY += 15; rackIndex++ }
    // A dashed line between the two rows of the member devices' room.
    for (x in 4 until 190 step 6) r(g, x, plan.desk(OfficeZone.REMOTE, 0).y + 25, 3, 1, Color(0x33000000))

    // Desks, the agents sitting at them.
    for (zone in OfficeZone.entries) for (seat in 0 until plan.capacity(zone)) {
        val desk = plan.desk(zone, seat)
        val placement = f.seating.placements.firstOrNull { it.agent.zone == zone && it.desk == desk && seated(it, plan, f) }
        deskScene(g, p, desk, placement, f)
    }

    pantry(g, plan, f)
    lounge(g, p, plan, f)

    // The agents standing: at the pantry, or on their way.
    for (placement in f.seating.placements) {
        if (seated(placement, plan, f)) continue
        val at = f.spots[placement.agent.id] ?: placement.spot
        val agent = placement.agent
        sprite(g, at.x, at.y, agent.mood, shirtOf(agent.session.agentKind), hairOf(agent.id), f.tick, stand = true, stride = if (agent.id in f.walking) (f.tick shr 1) and 1 else -1)
        if (agent.id !in f.walking) { mug(g, at.x + 10, at.y + 9, Mug); steam(g, at.x + 11, at.y + 6, f.tick) }
    }

    // Dimming, then hover and selection brackets.
    if (f.focus != null) for (placement in f.seating.placements) if (placement.agent.mood != f.focus) {
        val area = hitArea(placement, f.spots[placement.agent.id] ?: placement.spot, placement.agent.id in f.walking)
        drawRect((if (f.dark) Color(0xFF13121F) else Color(0xFFD9D6EA)).copy(alpha = .55f), Offset(g.left + area.x * g.scale, g.top + area.y * g.scale), Size(area.w * g.scale, area.h * g.scale))
    }
    val bracket = if (f.dark) Color.White else Ink
    f.hovered?.let { id -> byId[id]?.let { brackets(g, hitArea(it, f.spots[id] ?: it.spot, id in f.walking), bracket) } }
    f.opened?.let { id -> byId[id]?.let {
        val area = hitArea(it, f.spots[id] ?: it.spot, id in f.walking)
        brackets(g, Area(area.x - 1, area.y - 1, area.w + 2, area.h + 2), if ((f.tick shr 4) and 1 == 0) moodColor(it.agent.mood) else Color.White)
    } }
    if (f.workflows && f.hoveredBoard == OfficeBoard.WORKFLOWS) brackets(g, plan.workflowsBoard, bracket)
    if (f.automations && f.hoveredBoard == OfficeBoard.AUTOMATIONS) brackets(g, plan.automationsBoard, bracket)
}

/** The agent sits at its desk: that is where it belongs, and it is not on its way there. */
private fun seated(placement: Placement, plan: OfficePlan, f: Frame): Boolean =
    placement.desk != null && placement.spot == plan.chair(placement.desk) && placement.agent.id !in f.walking

private fun DrawScope.deskScene(g: Grid, p: Palette, desk: Spot, placement: Placement?, f: Frame) {
    val dx = desk.x; val dy = desk.y
    val mood = placement?.agent?.mood
    // Chair back, then the agent behind the desk, then the desk and what is on it.
    r(g, dx + 18, dy - 7, 14, 12, Color(0xFF3B3F4C))
    if (placement != null) {
        val agent = placement.agent
        val at = f.spots[agent.id] ?: placement.spot
        sprite(g, at.x, at.y, agent.mood, shirtOf(agent.session.agentKind), hairOf(agent.id), f.tick, stand = false, stride = -1)
    }
    r(g, dx, dy, 36, 14, p.deskTop); r(g, dx, dy + 14, 36, 2, p.deskEdge); r(g, dx, dy, 36, 1, Color(0x40FFFFFF))
    r(g, dx + 1, dy + 16, 3, 3, p.deskEdge); r(g, dx + 32, dy + 16, 3, 3, p.deskEdge)
    // Monitor: its LED says the mood.
    r(g, dx + 3, dy - 5, 14, 9, Metal); r(g, dx + 4, dy - 4, 12, 7, Screen); r(g, dx + 8, dy + 4, 4, 2, Metal); r(g, dx + 6, dy + 5, 8, 1, Metal)
    val led = when (mood) {
        null, OfficeMood.IDLE, OfficeMood.PAUSED -> Grey
        OfficeMood.ERROR -> if ((f.tick shr 4) and 1 == 1) moodColor(mood) else Color(0xFF6B2A2A)
        else -> moodColor(mood)
    }
    r(g, dx + 9, dy + 2, 2, 1, led)
    if (mood == OfficeMood.WORKING) for (i in 0 until 3) if (((f.tick shr 2) + i) % 3 != 0) r(g, dx + 5, dy - 3 + i * 2, 4 + ((i + (f.tick shr 3)) % 3) * 2, 1, Color(0xFF6AA4E0))
    r(g, dx + 19, dy + 5, 13, 3, Color(0xFFE9E9F2)); r(g, dx + 20, dy + 6, 11, 1, Color(0xFFB7B8CC))
    mug(g, dx + 31, dy + 8, if (placement != null) Mug else Color(0xFF99AAAA))
    // Forearms on the desk while the agent types or waits.
    if (placement != null) {
        val shirt = shirtOf(placement.agent.session.agentKind)
        val frame = (f.tick shr 2) and 1
        when (mood) {
            OfficeMood.WORKING -> { r(g, dx + 21, dy - 1, 2, 5, shirt); r(g, dx + 27, dy - 1, 2, 5, shirt); r(g, dx + 21, dy + 4 + frame, 2, 2, Skin); r(g, dx + 27, dy + 5 - frame, 2, 2, Skin) }
            OfficeMood.STARTING, OfficeMood.WAITING -> { r(g, dx + 21, dy - 1, 2, 5, shirt); r(g, dx + 21, dy + 4, 2, 2, Skin) }
            else -> Unit
        }
        bubble(g, placement.spot.x, placement.spot.y, placement.agent.mood, f.tick)
    }
}

private fun DrawScope.sprite(g: Grid, x: Int, y: Int, mood: OfficeMood, shirt: Color, hair: Color, tick: Int, stand: Boolean, stride: Int) {
    if (stand) {
        // Legs: still, or one step ahead of the other while walking.
        val a = if (stride == 0) 1 else 0
        val b = if (stride == 1) 1 else 0
        r(g, x + 2, y + 12, 3, 5 - a, Pants); r(g, x + 5, y + 12, 3, 5 - b, Pants)
        r(g, x + 1, y + 17 - a, 4, 2, Ink); r(g, x + 5, y + 17 - b, 4, 2, Ink)
    }
    // Head and hair.
    r(g, x + 1, y, 8, 3, hair); r(g, x, y + 1, 1, 4, hair); r(g, x + 9, y + 1, 1, 4, hair); r(g, x + 2, y + 3, 6, 4, Skin)
    val blink = tick % 190 > 184
    when {
        mood == OfficeMood.ERROR -> { r(g, x + 3, y + 4, 1, 1, Ink); r(g, x + 6, y + 4, 1, 1, Ink); r(g, x + 4, y + 6, 2, 1, Color(0xFFAA3333)) }
        mood == OfficeMood.PAUSED || blink -> { r(g, x + 3, y + 5, 1, 1, Ink); r(g, x + 6, y + 5, 1, 1, Ink); r(g, x + 4, y + 6, 2, 1, Color(0xFFB0553C)) }
        else -> { r(g, x + 3, y + 4, 1, 1, Ink); r(g, x + 6, y + 4, 1, 1, Ink); if (mood == OfficeMood.DONE) r(g, x + 3, y + 6, 4, 1, Color(0xFFAA3333)) else r(g, x + 4, y + 6, 2, 1, Color(0xFFB0553C)) }
    }
    // Body.
    r(g, x + 1, y + 7, 8, 6, shirt); r(g, x + 4, y + 7, 2, 1, Skin); r(g, x, y + 8, 1, 4, shirt); r(g, x + 9, y + 8, 1, 4, shirt)
    if (stand) { r(g, x, y + 12, 1, 2, Skin); r(g, x + 9, y + 12, 1, 2, Skin); return }
    val wave = (tick shr 3) and 1
    when (mood) {
        OfficeMood.WAITING -> { r(g, x + 9, y + 1, 2, 9, shirt); r(g, x + 10, y - 2 + wave, 2, 3, Skin); r(g, x - 1, y + 8, 2, 4, shirt) }
        OfficeMood.DONE -> { r(g, x - 1, y + 1, 2, 8, shirt); r(g, x - 2, y - 1, 2, 2, Skin); r(g, x + 9, y + 1, 2, 8, shirt); r(g, x + 10, y - 1, 2, 2, Skin) }
        OfficeMood.ERROR -> { r(g, x - 1, y + 3, 2, 8, shirt); r(g, x - 1, y + 2, 3, 3, Skin); r(g, x + 8, y + 3, 2, 8, shirt); r(g, x + 8, y + 2, 3, 3, Skin) }
        OfficeMood.STARTING -> { r(g, x + 8, y + 5, 2, 6, shirt); r(g, x + 7, y + 5, 3, 2, Skin); r(g, x - 1, y + 8, 2, 4, shirt) }
        else -> Unit
    }
}

private fun DrawScope.bubble(g: Grid, sx: Int, sy: Int, mood: OfficeMood, tick: Int) {
    if (mood == OfficeMood.WORKING || mood == OfficeMood.IDLE) return
    val x = sx - 1
    val y = sy - 13 + (if (tick % 28 < 14) 0 else 1)
    r(g, x - 1, y - 1, 13, 10, Ink); r(g, x, y, 11, 8, Color.White); r(g, x + 4, y + 8, 3, 2, Ink); r(g, x + 5, y + 8, 1, 1, Color.White)
    val c = moodColor(mood)
    when (mood) {
        OfficeMood.STARTING -> { val on = (tick shr 3) % 3; intArrayOf(2, 5, 8).forEachIndexed { i, d -> r(g, x + d, y + 4 - (if (i == on) 1 else 0), 2, 2, c) } }
        OfficeMood.WAITING -> { r(g, x + 5, y + 1, 2, 4, c); r(g, x + 5, y + 6, 2, 1, c) }
        OfficeMood.ERROR -> for (i in 0 until 6) { r(g, x + 3 + i, y + 1 + i, 1, 1, c); r(g, x + 8 - i, y + 1 + i, 1, 1, c) }
        OfficeMood.DONE -> listOf(2 to 4, 3 to 5, 4 to 6, 5 to 5, 6 to 4, 7 to 3, 8 to 2).forEach { (i, j) -> r(g, x + i, y + j, 1, 1, c) }
        OfficeMood.PAUSED -> { r(g, x + 3, y + 2, 5, 1, c); r(g, x + 6, y + 3, 1, 1, c); r(g, x + 5, y + 4, 1, 1, c); r(g, x + 4, y + 5, 1, 1, c); r(g, x + 3, y + 6, 5, 1, c) }
        else -> Unit
    }
}

private fun DrawScope.brackets(g: Grid, a: Area, c: Color) {
    val s = 4
    r(g, a.x, a.y, s, 1, c); r(g, a.x, a.y, 1, s, c)
    r(g, a.x + a.w - s, a.y, s, 1, c); r(g, a.x + a.w - 1, a.y, 1, s, c)
    r(g, a.x, a.y + a.h - 1, s, 1, c); r(g, a.x, a.y + a.h - s, 1, s, c)
    r(g, a.x + a.w - s, a.y + a.h - 1, s, 1, c); r(g, a.x + a.w - 1, a.y + a.h - s, 1, s, c)
}

private fun DrawScope.window(g: Grid, p: Palette, x: Int, y: Int, tick: Int) {
    r(g, x - 2, y - 2, 38, 24, p.wallDark); r(g, x, y, 34, 20, p.sky)
    if (p.night) {
        listOf(4 to 4, 12 to 10, 22 to 5, 28 to 12, 18 to 15).forEachIndexed { i, (a, b) -> if (((tick shr 5) + i) % 5 != 0) r(g, x + a, y + b, 1, 1, p.sun) }
        r(g, x + 24, y + 2, 5, 5, p.sun); r(g, x + 26, y + 2, 4, 4, p.sky)
    } else {
        r(g, x + 22, y + 3, 6, 6, p.sun); r(g, x + 4, y + 9, 12, 3, Color.White); r(g, x + 7, y + 7, 8, 3, Color.White); r(g, x, y + 16, 34, 4, Color(0xFF6AA86D))
    }
    r(g, x + 16, y, 2, 20, p.wallDark); r(g, x, y + 9, 34, 2, p.wallDark)
}

/** The sign on the wall between the windows. */
private fun DrawScope.poster(g: Grid, x: Int, y: Int) {
    r(g, x, y, 44, 24, Ink); r(g, x + 1, y + 1, 42, 22, Color(0xFF14131F))
    r(g, x + 6, y + 6, 8, 12, Color(0xFF3DDC97)); r(g, x + 9, y + 9, 2, 6, Color(0xFF14131F))
    r(g, x + 18, y + 8, 20, 2, Color(0xFFE6EDF3)); r(g, x + 18, y + 13, 14, 2, Color(0xFF8B98A5))
}

private fun DrawScope.rack(g: Grid, x: Int, y: Int, tick: Int) {
    r(g, x, y, 12, 14, Metal); r(g, x + 1, y + 1, 10, 12, Color(0xFF383D4B))
    for (i in 0 until 3) {
        r(g, x + 2, y + 2 + i * 4, 8, 3, Color(0xFF222632))
        val on = ((tick shr 3) + i + x) % 4
        r(g, x + 3, y + 3 + i * 4, 1, 1, if (on != 0) Color(0xFF4CD18A) else Color(0xFF2B6B4A))
        r(g, x + 5, y + 3 + i * 4, 1, 1, if (on == 2) Color(0xFFE8A91B) else Grey)
    }
}

private fun DrawScope.mug(g: Grid, x: Int, y: Int, c: Color) { r(g, x, y, 4, 4, c); r(g, x + 4, y + 1, 1, 2, c); r(g, x + 1, y, 2, 1, Color(0xFF5B3A1E)) }

private fun DrawScope.steam(g: Grid, x: Int, y: Int, tick: Int) { r(g, x, y - (tick shr 3) % 3, 1, 2, Color(0xB3FFFFFF)) }

private fun DrawScope.plant(g: Grid, x: Int, y: Int) {
    r(g, x + 2, y + 9, 7, 5, Color(0xFFA85F2A)); r(g, x + 3, y + 14, 5, 1, Color(0xFF7D4519))
    r(g, x, y + 3, 11, 6, Color(0xFF3F9A4F)); r(g, x + 2, y, 7, 4, Color(0xFF56B865)); r(g, x + 4, y + 2, 2, 4, Color(0xFF2E7A3C))
}

private fun DrawScope.pantry(g: Grid, plan: OfficePlan, f: Frame) {
    val top = plan.lowerTop
    r(g, 8, top + 4, 64, 14, Color(0xFF8B95A8)); r(g, 8, top + 4, 64, 2, Color(0xFFB2BBCA)); r(g, 8, top + 18, 64, 2, Color(0xFF5F6A7E))
    r(g, 42, top - 7, 12, 11, Metal); r(g, 44, top - 5, 8, 5, Color(0xFFD64545)); r(g, 45, top + 1, 6, 2, Color(0xFF14131F)); r(g, 46, top + 2, 4, 1, Color(0xFF6B4A2A))
    r(g, 76, top - 6, 14, 26, Color(0xFFD7DCE6)); r(g, 76, top + 6, 14, 1, Color(0xFF8E9BB0)); r(g, 87, top - 2, 1, 5, Color(0xFF8E9BB0))
    r(g, 14, top + 8, 10, 6, Mug); r(g, 30, top + 9, 6, 5, Color(0xFFE8A91B))
    plant(g, 98, top + 6)
}

private fun DrawScope.lounge(g: Grid, p: Palette, plan: OfficePlan, f: Frame) {
    val top = plan.lowerTop
    // A sofa between the boards.
    r(g, 104, top + 34, 40, 14, Color(0xFF6C5B8A)); r(g, 104, top + 32, 40, 4, Color(0xFF7F6CA0)); r(g, 106, top + 48, 3, 3, Color(0xFF4A3F63)); r(g, 139, top + 48, 3, 3, Color(0xFF4A3F63))
    // The whiteboard of the workflows: three columns of notes.
    val wf = plan.workflowsBoard
    if (f.workflows) {
        r(g, wf.x, wf.y, wf.w, wf.h, Color(0xFFF7F6FB)); r(g, wf.x - 2, wf.y - 2, wf.w + 4, 2, Color(0xFF8E9BB0)); r(g, wf.x - 2, wf.y + wf.h, wf.w + 4, 2, Color(0xFF8E9BB0))
        r(g, wf.x - 2, wf.y, 2, wf.h, Color(0xFF8E9BB0)); r(g, wf.x + wf.w, wf.y, 2, wf.h, Color(0xFF8E9BB0))
        for (c in 0 until 3) {
            r(g, wf.x + 4 + c * 24, wf.y + 4, 20, 1, Ink)
            val notes = intArrayOf(3, 2, 1)[c]
            for (n in 0 until notes) r(g, wf.x + 5 + c * 24, wf.y + 8 + n * 9, 17, 7, intArrayOf(0xFF5B5BD6.toInt(), 0xFFE8A91B.toInt(), 0xFF2F9E6B.toInt())[(c + n) % 3].let { Color(it) })
        }
    } else plant(g, wf.x + 20, wf.y + 10)
    // The board of the automations: lines, a clock.
    val au = plan.automationsBoard
    if (f.automations) {
        r(g, au.x + 2, au.y + 2, 64, 34, Color(0xFFF7F6FB)); r(g, au.x, au.y, 68, 2, Color(0xFF8E9BB0)); r(g, au.x, au.y + 36, 68, 2, Color(0xFF8E9BB0)); r(g, au.x, au.y, 2, 38, Color(0xFF8E9BB0)); r(g, au.x + 66, au.y, 2, 38, Color(0xFF8E9BB0))
        listOf(Triple(8, 10, 24), Triple(8, 17, 38), Triple(8, 24, 18)).forEachIndexed { i, (a, b, c) -> r(g, au.x + a, au.y + b, c, 2, Color(intArrayOf(0xFF5B5BD6.toInt(), 0xFF2F9E6B.toInt(), 0xFFE8A91B.toInt())[i])) }
        r(g, au.x + 44, au.y + 8, 16, 16, Color.White); r(g, au.x + 44, au.y + 8, 16, 1, Ink); r(g, au.x + 44, au.y + 23, 16, 1, Ink); r(g, au.x + 44, au.y + 8, 1, 16, Ink); r(g, au.x + 59, au.y + 8, 1, 16, Ink)
        r(g, au.x + 51, au.y + 10, 1, 6, Ink); r(g, au.x + 51, au.y + 16, 4, 1, Ink); r(g, au.x + 52 + ((f.tick shr 5) % 12 > 6).let { if (it) 1 else 0 }, au.y + 12, 1, 1, Color(0xFFD64545))
    } else plant(g, au.x + 24, au.y + 10)
    r(g, 240, top + 44, 44, 9, Color(0xFFCFC9E6)); r(g, 242, top + 42, 40, 3, Color(0xFFE9E6F7))
    plant(g, 298, top + 44)
}
