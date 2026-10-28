package me.nathanfallet.teamcode.domain.automations

import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.automations.Fixtures.ada
import me.nathanfallet.teamcode.domain.automations.Fixtures.principal
import kotlin.test.*

class AutomationSchedulerTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(paris).toInstant().toEpochMilli()

    private fun armed(catchup: CatchupPolicy = CatchupPolicy.ONCE, grace: Int = 360, now: String = "2026-10-28T12:00"): Pair<Fixtures.World, String> = runBlocking {
        val world = Fixtures.World()
        world.store.clock = at(now)
        val definition = Fixtures.cron.copy(trigger = AutomationTrigger(TriggerKind.CRON, "0 21 * * *", "Europe/Paris", catchup, grace))
        val id = world.service.create(principal(ada), definition).automation.id
        world.service.enable(principal(ada), id)
        world to id
    }

    @Test fun anOnTimeTickRecordsTheSlotAndMovesToTheNext() = runBlocking {
        val (world, id) = armed()
        world.store.clock = at("2026-10-28T21:00:10")
        assertEquals(1, world.scheduler.run())
        val run = world.store.runs.values.single()
        assertEquals(RunSource.CRON, run.source); assertEquals(at("2026-10-28T21:00"), run.scheduledFor)
        assertEquals("Summarize 2026-10-28 (Europe/Paris)", world.store.prompts.getValue(run.id))
        assertEquals(at("2026-10-29T21:00"), world.store.automations.getValue(id).nextRunAt)
        assertEquals(0, world.scheduler.run(), "nothing is due any more")
    }

    @Test fun nothingIsDueBeforeTheSlot() = runBlocking {
        val (world, _) = armed()
        world.store.clock = at("2026-10-28T20:59")
        assertEquals(0, world.scheduler.run())
        assertTrue(world.store.runs.isEmpty())
    }

    @Test fun catchUpOnceRunsTheLatestMissedSlotWithinTheGrace() = runBlocking {
        val (world, id) = armed()
        world.store.clock = at("2026-10-28T21:40")
        assertEquals(1, world.scheduler.run())
        assertEquals(at("2026-10-28T21:00"), world.store.runs.values.single().scheduledFor)
        assertEquals(at("2026-10-29T21:00"), world.store.automations.getValue(id).nextRunAt)
    }

    @Test fun aSlotOlderThanTheGraceIsNeverRun() = runBlocking {
        val (world, id) = armed()
        world.store.clock = at("2026-10-29T12:00")
        assertEquals(0, world.scheduler.run())
        assertTrue(world.store.runs.isEmpty())
        assertEquals(at("2026-10-29T21:00"), world.store.automations.getValue(id).nextRunAt)
    }

    @Test fun aLongOutageCatchesUpOnceNotInABurst() = runBlocking {
        val world = Fixtures.World()
        world.store.clock = at("2026-10-28T12:00")
        val hourly = Fixtures.cron.copy(trigger = AutomationTrigger(TriggerKind.CRON, "0 * * * *", "Europe/Paris", CatchupPolicy.ONCE, 360), overlap = OverlapPolicy.ALLOW)
        val id = world.service.create(principal(ada), hourly).automation.id
        world.service.enable(principal(ada), id)
        world.store.clock = at("2026-10-28T17:30")
        assertEquals(1, world.scheduler.run())
        assertEquals(at("2026-10-28T17:00"), world.store.runs.values.single().scheduledFor)
    }

    @Test fun skipRunsNothingAfterAnOutage() = runBlocking {
        val (world, id) = armed(CatchupPolicy.SKIP)
        world.store.clock = at("2026-10-28T21:40")
        assertEquals(0, world.scheduler.run())
        assertEquals(at("2026-10-29T21:00"), world.store.automations.getValue(id).nextRunAt)
        world.store.clock = at("2026-10-29T21:01")
        assertEquals(1, world.scheduler.run(), "an ordinary late tick still runs")
    }

    @Test fun twoReplicasNeverRecordTheSameSlot() = runBlocking {
        val (world, id) = armed()
        world.store.clock = at("2026-10-28T21:00:05")
        val expected = world.store.automations.getValue(id).nextRunAt!!
        assertTrue(world.store.advance(id, expected, at("2026-10-29T21:00"), null))
        assertFalse(world.store.advance(id, expected, at("2026-10-29T21:00"), null), "the second replica read a slot that moved")
    }
}
