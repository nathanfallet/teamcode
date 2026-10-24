package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.cli.platform.processRunning
import me.nathanfallet.teamcode.cli.platform.waitForProcessExit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessExitTest {
    @Test fun theHandOffWaitsForTheAppToQuit() = runBlocking {
        var checks = 0
        assertTrue(waitForProcessExit(42, 10.seconds) { checks++ < 2 })
        assertTrue(checks >= 3)
        assertFalse(waitForProcessExit(42, 300.milliseconds) { true })
    }

    @Test fun aRealProcessIsSeenUntilItExits() = runBlocking {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val child = ProcessBuilder(if (windows) listOf("cmd", "/c", "ping -n 2 127.0.0.1 > NUL") else listOf("sleep", "1")).start()
        assertTrue(processRunning(child.pid()))
        assertTrue(waitForProcessExit(child.pid(), 10.seconds))
        child.waitFor()
        assertFalse(processRunning(child.pid()))
        assertFalse(processRunning(0))
        assertFalse(processRunning(-1))
    }
}
