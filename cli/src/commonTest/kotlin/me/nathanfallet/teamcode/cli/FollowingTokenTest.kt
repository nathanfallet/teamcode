package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.config.CliConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class FollowingTokenTest {
    private val launched = CliConfig("https://team.example", "token-at-launch")

    @Test
    fun aSignInAfterLaunchReplacesTheTokenOnTheNextRequest() {
        var saved: CliConfig? = launched
        val token = followingToken(launched) { saved }
        assertEquals("token-at-launch", token())
        saved = CliConfig("https://team.example", "token-after-login")
        assertEquals("token-after-login", token())
    }

    @Test
    fun aMissingOrUnreadableFileKeepsTheTokenReadAtLaunch() {
        assertEquals("token-at-launch", followingToken(launched) { null }())
        assertEquals("token-at-launch", followingToken(launched) { error("unreadable") }())
    }

    @Test
    fun aFileNamingAnotherServerIsNeverUsed() {
        assertEquals("token-at-launch", followingToken(launched) { CliConfig("https://other.example", "foreign") }())
    }
}
