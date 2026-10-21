package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.cli.platform.*
import kotlin.test.*

class AgentExecutableTest {
    @Test
    fun claudePrefersNativeBinaryAndKeepsLegacyCliJs() {
        val paths = npmLaunchCandidates("claude", "x64")
        assertEquals("node_modules/@anthropic-ai/claude-code/bin/claude.exe", paths.first { it.relativePath.endsWith("/bin/claude.exe") }.relativePath)
        assertTrue(paths.filter { !it.needsNode }.all { it.relativePath.endsWith("claude.exe") })
        assertEquals(NpmLaunchCandidate("node_modules/@anthropic-ai/claude-code/cli.js", true), paths.last())
        assertTrue(paths.any { it.relativePath.contains("claude-code-win32-x64") })
        assertEquals("x64", paths.first().relativePath.substringAfter("claude-code-win32-").substringBefore("/"))
    }

    @Test
    fun claudeOnArm64TriesArm64PackageFirst() {
        assertContains(npmLaunchCandidates("claude", "arm64").first().relativePath, "claude-code-win32-arm64")
    }

    @Test
    fun codexUsesNodeLauncherAndUnknownCommandsHaveNone() {
        assertEquals(listOf(NpmLaunchCandidate("node_modules/@openai/codex/bin/codex.js", true)), npmLaunchCandidates("codex", "x64"))
        assertTrue(npmLaunchCandidates("other", "x64").isEmpty())
    }

    @Test
    fun messagesAreActionable() {
        assertContains(agentNotFoundMessage("claude"), "%USERPROFILE%")
        assertContains(agentNotFoundMessage("claude"), "PATH")
        assertContains(agentNotFoundMessage("codex"), "@openai/codex")
        assertContains(nodeMissingMessage("codex"), "Node.js")
        assertContains(ProcessStartException(ProcessStartException.Reason.CannotLaunch, "claude").toUserError().message, "claude")
    }

    @Test
    fun userExceptionCarriesItsMessage() {
        assertEquals("No configuration found. Run teamcode login first.", CliUserException("No configuration found. Run teamcode login first.").message)
    }
}
