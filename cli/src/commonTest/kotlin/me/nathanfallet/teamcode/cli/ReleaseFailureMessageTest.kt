package me.nathanfallet.teamcode.cli

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ReleaseFailureMessageTest {
    @Test
    fun installationBusyTellsUserToCloseAgentSessions() {
        val message = releaseFailureMessage("release_installation_busy")
        assertContains(message, "agent session started by TeamCode is still running")
        assertContains(message, "Close all agent sessions")
        assertContains(message, "release_installation_busy")
        assertFalse(message.contains("connectivity"))
    }

    @Test
    fun writerBusyTellsUserAnotherUpdateIsRunning() {
        val message = releaseFailureMessage("release_writer_busy")
        assertContains(message, "another TeamCode update")
        assertContains(message, "release_writer_busy")
        assertFalse(message.contains("connectivity"))
    }

    @Test
    fun otherReleaseFailuresKeepGenericMessage() {
        val message = releaseFailureMessage("release_download_failed")
        assertContains(message, "Check connectivity")
        assertContains(message, "release_download_failed")
    }
}
