package me.nathanfallet.teamcode.companion.presentation.office

import me.nathanfallet.teamcode.companion.domain.usecases.office.OfficeAgent
import me.nathanfallet.teamcode.companion.domain.usecases.office.OfficeMood
import me.nathanfallet.teamcode.companion.domain.usecases.office.OfficeZone
import me.nathanfallet.teamcode.companion.domain.usecases.office.assignSeats
import kotlin.math.abs

/** A point of the office, in its own pixels (the scene is [OfficePlan.WIDTH] pixels wide, whatever the screen). */
data class Spot(val x: Int, val y: Int)

/** A rectangle of the office, in its pixels. */
data class Area(val x: Int, val y: Int, val w: Int, val h: Int) {
    operator fun contains(spot: Spot): Boolean = spot.x >= x && spot.x < x + w && spot.y >= y && spot.y < y + h
}

/**
 * The floor plan: the member devices' room on the left (three desks a row), the cloud's glass room on the right (two a
 * row), the pantry and the boards below. The rows grow with the sessions, up to [MAX_ROWS]; the width never changes.
 */
class OfficePlan(remote: Int, cloud: Int) {
    val rows: Int = maxOf(MIN_ROWS, ceilDiv(remote, REMOTE_COLUMNS), ceilDiv(cloud, CLOUD_COLUMNS)).coerceAtMost(MAX_ROWS)

    /** Where the floor of the lower part (pantry, boards) starts. */
    val lowerTop: Int = FIRST_DESK_Y + (rows - 1) * ROW_PITCH + 34

    val height: Int = lowerTop + 60

    fun capacity(zone: OfficeZone): Int = rows * columns(zone).size

    /** The top left of the desk at [seat] of [zone]. */
    fun desk(zone: OfficeZone, seat: Int): Spot {
        val columns = columns(zone)
        return Spot(columns[seat % columns.size], FIRST_DESK_Y + (seat / columns.size) * ROW_PITCH)
    }

    /** Where the agent at [desk] sits: above the desk's left half. */
    fun chair(desk: Spot): Spot = Spot(desk.x + 20, desk.y - 13)

    /** Where the agent at [slot] of the pantry stands. */
    fun pantry(slot: Int): Spot = Spot(PANTRY_X + slot * PANTRY_PITCH, lowerTop + 22)

    /** The glass room of the cloud, the racks along its right wall. */
    val cloudRoom: Area = Area(196, 36, 116, lowerTop - 8 - 36)

    /** The pantry counter. */
    val pantryCounter: Area = Area(8, lowerTop + 4, 64, 14)

    /** The whiteboard of the workflows and the board of the automations: places the member can click. */
    val workflowsBoard: Area = Area(142, lowerTop + 2, 76, 40)
    val automationsBoard: Area = Area(230, lowerTop - 4, 68, 38)

    companion object {
        const val WIDTH = 320
        const val MIN_ROWS = 2
        const val MAX_ROWS = 8
        const val ROW_PITCH = 60
        const val FIRST_DESK_Y = 70
        const val PANTRY_SLOTS = 5
        private const val PANTRY_X = 12
        private const val PANTRY_PITCH = 14
        const val REMOTE_COLUMNS = 3
        const val CLOUD_COLUMNS = 2
        private val REMOTE_X = intArrayOf(14, 62, 110)
        private val CLOUD_X = intArrayOf(204, 256)

        fun columns(zone: OfficeZone): IntArray = if (zone == OfficeZone.REMOTE) REMOTE_X else CLOUD_X
        private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b
    }
}

/** Where one agent is: at a desk of [zone], or standing at the pantry. */
data class Placement(val agent: OfficeAgent, val spot: Spot, val desk: Spot?)

/** The agents placed on a plan; [hidden] are the ones that found no desk. */
data class Seating(val plan: OfficePlan, val placements: List<Placement>, val hidden: Int)

/**
 * Decides who sits where, and remembers it: a desk stays its agent's while the agent is there. An idle agent goes to the
 * pantry (while it has room) and comes back to its desk when it works again; its desk stays its own meanwhile.
 */
class OfficeSeater {
    private var remote = emptyMap<String, Int>()
    private var cloud = emptyMap<String, Int>()
    private var pantry = emptyMap<String, Int>()

    fun seat(agents: List<OfficeAgent>): Seating {
        val plan = OfficePlan(agents.count { it.zone == OfficeZone.REMOTE }, agents.count { it.zone == OfficeZone.CLOUD })
        remote = assignSeats(remote, agents.filter { it.zone == OfficeZone.REMOTE }.map { it.id }, plan.capacity(OfficeZone.REMOTE))
        cloud = assignSeats(cloud, agents.filter { it.zone == OfficeZone.CLOUD }.map { it.id }, plan.capacity(OfficeZone.CLOUD))
        val idle = agents.filter { it.mood == OfficeMood.IDLE && (remote[it.id] ?: cloud[it.id]) != null }
        pantry = assignSeats(pantry, idle.map { it.id }, OfficePlan.PANTRY_SLOTS)
        var hidden = 0
        val placements = agents.mapNotNull { agent ->
            val seat = (if (agent.zone == OfficeZone.REMOTE) remote else cloud)[agent.id]
            if (seat == null) { hidden++; return@mapNotNull null }
            val desk = plan.desk(agent.zone, seat)
            val slot = pantry[agent.id]
            if (slot != null) Placement(agent, plan.pantry(slot), desk) else Placement(agent, plan.chair(desk), desk)
        }
        return Seating(plan, placements, hidden)
    }
}

/**
 * The agents' positions between two frames: each walks toward its spot at [speed] pixels a frame and sits down on arrival.
 * An agent seen for the first time appears where it belongs.
 */
class Walkers(private val speed: Float = 3f) {
    private val x = HashMap<String, Float>()
    private val y = HashMap<String, Float>()

    /** Moves everyone one frame toward [targets]; returns the ids still walking. */
    fun step(targets: Map<String, Spot>): Set<String> {
        x.keys.retainAll(targets.keys)
        y.keys.retainAll(targets.keys)
        val walking = HashSet<String>()
        for ((id, target) in targets) {
            val cx = x[id]
            val cy = y[id]
            if (cx == null || cy == null) { x[id] = target.x.toFloat(); y[id] = target.y.toFloat(); continue }
            val dx = target.x - cx
            val dy = target.y - cy
            val distance = maxOf(abs(dx), abs(dy))
            if (distance <= speed) { x[id] = target.x.toFloat(); y[id] = target.y.toFloat(); continue }
            // Straight toward the target, the longer axis at full speed.
            x[id] = cx + dx / distance * speed
            y[id] = cy + dy / distance * speed
            walking += id
        }
        return walking
    }

    fun at(id: String): Spot? {
        val px = x[id] ?: return null
        val py = y[id] ?: return null
        return Spot(px.toInt(), py.toInt())
    }
}

/** The clickable box of an agent: its desk and chair when it sits, a standing figure when it does not. */
fun hitArea(placement: Placement, at: Spot, walking: Boolean): Area {
    val desk = placement.desk
    return if (!walking && desk != null && placement.spot.y < desk.y) Area(desk.x - 2, desk.y - 16, 40, 34) else Area(at.x - 4, at.y - 3, 18, 26)
}
