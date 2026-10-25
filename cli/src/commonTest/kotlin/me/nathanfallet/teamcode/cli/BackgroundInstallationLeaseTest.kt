package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.cli.update.BackgroundInstallationLease
import kotlin.test.*

/**
 * A remote session hosted by the device's daemon holds no installation lease, so `teamcode update` restarted the local
 * service at once: its daemon paused the session and the agent's subagents and background commands were killed without
 * a word. While such work runs, the host now holds the lease, so the restart waits for it, for a bounded time.
 */
class BackgroundInstallationLeaseTest {
    private class Lease(val log: MutableList<String>) : AutoCloseable { override fun close() { log += "closed" } }

    @Test fun theLeaseIsHeldWhileBackgroundWorkRunsThenTheDeferredRestartIsApplied() = runTest {
        val log = mutableListOf<String>()
        var inFlight = false
        var busy = 1 // A restart in progress holds the lock once: the host takes the lease at the next poll.
        val job = launch {
            BackgroundInstallationLease({ inFlight }, { if (busy-- > 0) null else Lease(log).also { log += "acquired" } }, { log += "restart-check" },
                now = { testScheduler.currentTime }).run()
        }
        delay(22_000)
        assertEquals(emptyList(), log, "No background work, no lease: the update restarts the service at once")
        inFlight = true
        delay(5_000)
        assertEquals(emptyList(), log, "The lock was busy: retried, never waited for")
        delay(5_000)
        assertEquals(listOf("acquired"), log)
        delay(60_000)
        assertEquals(listOf("acquired"), log, "Held while the work runs")
        inFlight = false
        delay(6_000)
        assertEquals(listOf("acquired", "closed", "restart-check"), log, "Released at the end, then a deferred restart may run")
        job.cancel()
    }

    @Test fun backgroundWorkThatNeverEndsHoldsTheLeaseForTheCapOnly() = runTest {
        val log = mutableListOf<String>()
        val job = launch {
            BackgroundInstallationLease({ true }, { Lease(log).also { log += "acquired" } }, { log += "restart-check" }, maxMillis = 60 * 60_000L,
                now = { testScheduler.currentTime }).run()
        }
        delay(59 * 60_000L)
        assertEquals(listOf("acquired"), log)
        delay(2 * 60_000L)
        assertEquals(listOf("acquired", "closed", "restart-check"), log, "After the cap the restart goes ahead and pauses the session")
        delay(60 * 60_000L)
        assertEquals(3, log.size, "Not taken again for the same unbroken stretch")
        job.cancel()
    }

    @Test fun theLeaseIsReleasedWhenTheHostStops() = runTest {
        val log = mutableListOf<String>()
        val job = launch { BackgroundInstallationLease({ true }, { Lease(log).also { log += "acquired" } }, { log += "restart-check" }, now = { testScheduler.currentTime }).run() }
        delay(10_000)
        job.cancel(); job.join()
        assertEquals(listOf("acquired", "closed"), log, "The host's own exit applies any deferred restart afterwards")
    }
}
