package me.nathanfallet.teamcode.cli.platform

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Whether a process with this id still exists, as far as the current user can tell. It is only observed: nothing here
 * signals or opens the process for anything but waiting.
 */
expect fun processRunning(pid: Long): Boolean

/** Waits until the process [pid] has exited, checking every 250 ms; false when it still runs after [timeout]. */
internal suspend fun waitForProcessExit(pid: Long, timeout: Duration, running: (Long) -> Boolean = ::processRunning): Boolean {
    val start = TimeSource.Monotonic.markNow()
    while (running(pid)) {
        if (start.elapsedNow() >= timeout) return false
        delay(250)
    }
    return true
}
