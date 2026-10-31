package me.nathanfallet.teamcode.companion.presentation

import me.nathanfallet.teamcode.companion.cloudSession
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.usecases.office.OfficeMood
import me.nathanfallet.teamcode.companion.domain.usecases.office.OfficeScene
import me.nathanfallet.teamcode.companion.domain.usecases.office.OfficeZone
import me.nathanfallet.teamcode.companion.laptop
import me.nathanfallet.teamcode.companion.presentation.office.OfficePlan
import me.nathanfallet.teamcode.companion.presentation.office.OfficeSeater
import me.nathanfallet.teamcode.companion.presentation.office.Spot
import me.nathanfallet.teamcode.companion.presentation.office.Walkers
import me.nathanfallet.teamcode.companion.presentation.office.hitArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The floor plan, who sits where, how agents walk between two spots, and what a click on one hits. */
class OfficeLayoutTest {
    private fun agents(vararg sessions: me.nathanfallet.teamcode.companion.domain.models.CloudSession, finished: Set<String> = emptySet()) =
        OfficeScene.agents(sessions.toList(), finished)

    @Test fun theOfficeAlwaysHasAtLeastTwoRowsOfDesks() {
        val plan = OfficePlan(0, 0)
        assertEquals(2, plan.rows)
        assertEquals(6, plan.capacity(OfficeZone.REMOTE))
        assertEquals(4, plan.capacity(OfficeZone.CLOUD))
    }

    @Test fun rowsGrowWithTheBusierRoomUpToTheLimit() {
        assertEquals(3, OfficePlan(7, 0).rows)
        assertEquals(4, OfficePlan(0, 7).rows)
        assertEquals(OfficePlan.MAX_ROWS, OfficePlan(500, 500).rows)
    }

    @Test fun theOfficeIsTallerWithMoreRowsAndNeverWider() {
        assertTrue(OfficePlan(0, 9).height > OfficePlan(0, 1).height)
        for (zone in OfficeZone.entries) for (seat in 0 until OfficePlan(40, 40).capacity(zone)) assertTrue(OfficePlan(40, 40).desk(zone, seat).x + 36 <= OfficePlan.WIDTH)
    }

    @Test fun deskPlacesDoNotOverlapInAZone() {
        val plan = OfficePlan(12, 12)
        for (zone in OfficeZone.entries) {
            val desks = (0 until plan.capacity(zone)).map { plan.desk(zone, it) }
            assertEquals(desks.size, desks.toSet().size)
        }
    }

    @Test fun aWorkingAgentSitsAtItsDesk() {
        val seating = OfficeSeater().seat(agents(cloudSession("a", SessionState.ACTIVE)))
        val placement = seating.placements.single()
        assertEquals(seating.plan.chair(placement.desk!!), placement.spot)
        assertEquals(0, seating.hidden)
    }

    @Test fun anIdleAgentGoesToThePantryAndKeepsItsDeskForLater() {
        val seater = OfficeSeater()
        val idle = seater.seat(agents(cloudSession("a"))).placements.single()
        assertNotEquals(OfficePlan(0, 1).chair(idle.desk!!), idle.spot)
        assertTrue(idle.spot.y > idle.desk!!.y)
        val working = seater.seat(agents(cloudSession("a", SessionState.ACTIVE))).placements.single()
        assertEquals(idle.desk, working.desk)
    }

    @Test fun whenThePantryIsFullTheOthersStayAtTheirDesks() {
        val sessions = (1..7).map { cloudSession("s$it") }
        val seating = OfficeSeater().seat(agents(*sessions.toTypedArray()))
        assertEquals(OfficePlan.PANTRY_SLOTS, seating.placements.count { it.spot.y > it.desk!!.y })
        assertEquals(2, seating.placements.count { it.spot == seating.plan.chair(it.desk!!) })
    }

    @Test fun twoAgentsNeverShareADesk() {
        val sessions = (1..9).map { cloudSession("s$it", SessionState.ACTIVE) } + (1..9).map { cloudSession("r$it", SessionState.ACTIVE, host = laptop()) }
        val seating = OfficeSeater().seat(agents(*sessions.toTypedArray()))
        val keys = seating.placements.map { it.agent.zone to it.desk }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun agentsBeyondTheOfficesCapacityAreCountedNotDrawn() {
        val sessions = (1..30).map { cloudSession("s$it", SessionState.ACTIVE) }
        val seating = OfficeSeater().seat(agents(*sessions.toTypedArray()))
        assertEquals(seating.plan.capacity(OfficeZone.CLOUD), seating.placements.size)
        assertEquals(30 - seating.plan.capacity(OfficeZone.CLOUD), seating.hidden)
    }

    @Test fun aSeatedAgentIsFoundOnItsDeskAndAPantryAgentWhereItStands() {
        val seating = OfficeSeater().seat(agents(cloudSession("a", SessionState.ACTIVE), cloudSession("b")))
        val sitting = seating.placements[0]
        val standing = seating.placements[1]
        val onDesk = hitArea(sitting, sitting.spot, walking = false)
        assertTrue(Spot(sitting.desk!!.x + 10, sitting.desk!!.y + 5) in onDesk)
        val onFoot = hitArea(standing, standing.spot, walking = false)
        assertTrue(Spot(standing.spot.x + 3, standing.spot.y + 8) in onFoot)
        assertFalse(Spot(sitting.desk!!.x + 10, sitting.desk!!.y + 5) in onFoot)
    }

    @Test fun anAgentOnItsWayIsFoundWhereItIsNotAtItsDesk() {
        val placement = OfficeSeater().seat(agents(cloudSession("a", SessionState.ACTIVE))).placements.single()
        val here = Spot(40, 200)
        val area = hitArea(placement, here, walking = true)
        assertTrue(Spot(43, 208) in area)
        assertFalse(Spot(placement.desk!!.x + 10, placement.desk!!.y + 5) in area)
    }

    @Test fun anAgentAppearsWhereItBelongsThenWalksAtASetSpeed() {
        val walkers = Walkers(speed = 3f)
        assertEquals(emptySet(), walkers.step(mapOf("a" to Spot(10, 10))))
        assertEquals(Spot(10, 10), walkers.at("a"))
        val walking = walkers.step(mapOf("a" to Spot(40, 10)))
        assertEquals(setOf("a"), walking)
        assertEquals(Spot(13, 10), walkers.at("a"))
        var frames = 0
        while (walkers.step(mapOf("a" to Spot(40, 10))).isNotEmpty() && frames < 100) frames++
        assertEquals(Spot(40, 10), walkers.at("a"))
        // Eight more frames in motion, the ninth is the arrival.
        assertEquals(8, frames)
    }

    @Test fun anAgentThatLeavesIsForgotten() {
        val walkers = Walkers()
        walkers.step(mapOf("a" to Spot(10, 10), "b" to Spot(20, 20)))
        walkers.step(mapOf("b" to Spot(20, 20)))
        assertNull(walkers.at("a"))
        assertNotNull(walkers.at("b"))
    }

    @Test fun anAgentWhoseSessionWakesUpWalksBackToItsDesk() {
        val seater = OfficeSeater()
        val walkers = Walkers()
        val idle = seater.seat(agents(cloudSession("a"))).placements.single()
        walkers.step(mapOf("a" to idle.spot))
        val working = seater.seat(agents(cloudSession("a", SessionState.ACTIVE))).placements.single()
        assertEquals(OfficeMood.WORKING, working.agent.mood)
        assertTrue("a" in walkers.step(mapOf("a" to working.spot)))
    }
}
