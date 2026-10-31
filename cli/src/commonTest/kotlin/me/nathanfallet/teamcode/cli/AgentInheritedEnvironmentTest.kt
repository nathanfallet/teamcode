package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.cli.platform.REMOTE_AGENT_SCRUBBED_PREFIXES
import me.nathanfallet.teamcode.cli.platform.agentInheritedEnvironment
import kotlin.test.*

class AgentInheritedEnvironmentTest {
    private val inherited = mapOf(
        "PATH" to "/usr/bin", "HOME" to "/home/m", "XDG_CONFIG_HOME" to "/home/m/.config", "SSH_AUTH_SOCK" to "/tmp/agent", "GITHUB_TOKEN" to "gh",
        "TEAMCODE_LOCAL_TOKEN" to "local", "TEAMCODE_MCP_TOKEN" to "outer", "TEAMCODE_ARTIFACTS_TOKEN" to "outer", "TEAMCODE_GITHUB_TOKEN" to "x",
        "TEAMCODE_HOOK_TOKEN" to "h", "TEAMCODE_ARTIFACTS_URL" to "http://127.0.0.1:1", "teamcode_lower" to "windows-style",
    )

    @Test fun aRemoteAgentKeepsTheMembersShellAndLosesEveryTeamCodeVariable() {
        val kept = agentInheritedEnvironment(inherited, REMOTE_AGENT_SCRUBBED_PREFIXES)
        assertEquals(setOf("PATH", "HOME", "XDG_CONFIG_HOME", "SSH_AUTH_SOCK", "GITHUB_TOKEN"), kept.keys)
        assertEquals(inherited, agentInheritedEnvironment(inherited, emptyList()), "Pods and local runs are unchanged")
    }
}
