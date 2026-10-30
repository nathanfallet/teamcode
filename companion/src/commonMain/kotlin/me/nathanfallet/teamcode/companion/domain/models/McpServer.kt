package me.nathanfallet.teamcode.companion.domain.models

/** How the gateway reaches an MCP server. [UNKNOWN] is a server older than `mcp.gateway.stdio.v1`, which does not say. */
enum class McpTransport { HTTP, STDIO, UNKNOWN }

/**
 * One MCP server assigned to the member. Agents see it as [agentName]. [state] and [failure] are the gateway's words for
 * a hosted command (open lists, kept as received); read them through [status].
 */
data class McpServer(
    val name: String,
    val transport: McpTransport,
    val state: String? = null,
    val failure: String? = null,
) {
    /** As the CLI names it for the agent: `.` becomes `_`. */
    val agentName: String get() = "teamcode-" + name.replace('.', '_')

    val status: McpStatus get() = McpStatus.of(this)
}

/** Why a hosted command is unavailable, for the codes the app words. */
enum class McpReason { START_FAILED, STOPPED, MEMORY, BACKOFF, CAPACITY, INVALID_SPEC }

/** What the app says of a server's state. */
sealed interface McpStatus {
    /** Nothing to say: an HTTPS server (the gateway does not track it), or a state an older server does not report. */
    data object Untracked : McpStatus
    data object Idle : McpStatus
    data object Starting : McpStatus
    data object Ready : McpStatus

    /** `failed` or `backoff`: [reason] when the failure code is one the app words (a backoff without one: [McpReason.BACKOFF]), else [code] as is. */
    data class Unavailable(val reason: McpReason?, val code: String?) : McpStatus

    /** A state the app does not know, shown as is. */
    data class Other(val state: String) : McpStatus

    companion object {
        fun of(server: McpServer): McpStatus {
            if (server.transport != McpTransport.STDIO) return Untracked
            val state = server.state?.takeIf { it.isNotBlank() } ?: return Untracked
            return when (state) {
                "idle" -> Idle
                "starting" -> Starting
                "ready" -> Ready
                // The failure that caused a backoff says more than the backoff itself.
                "failed", "backoff" -> reasonOf(server.failure)?.let { Unavailable(it, null) }
                    ?: if (state == "backoff") Unavailable(McpReason.BACKOFF, null) else Unavailable(null, server.failure?.takeIf { it.isNotBlank() } ?: state)
                else -> Other(state)
            }
        }

        private fun reasonOf(failure: String?): McpReason? = when (failure) {
            "start_failed" -> McpReason.START_FAILED
            "crashed", "stdout_closed", "stdin_closed" -> McpReason.STOPPED
            "memory" -> McpReason.MEMORY
            "capacity" -> McpReason.CAPACITY
            "invalid_spec" -> McpReason.INVALID_SPEC
            else -> null
        }
    }
}
