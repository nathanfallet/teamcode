package me.nathanfallet.teamcode.api

/** End-to-end inference metadata allowlist. Authentication is supplied separately at each hop. */
object InferenceHeaders {
    val names: Set<String> = setOf("anthropic-version", "anthropic-beta", "x-claude-code-session-id", "session-id", "user-agent", "x-teamcode-workload", "x-teamcode-run-id")
    private val sessions = setOf("x-claude-code-session-id", "session-id")

    fun capture(values: (String) -> List<String>?): Map<String, String> = names.mapNotNull { name ->
        val supplied = values(name) ?: return@mapNotNull null
        // Preserve an invalid specific session header as invalid, so a generic header cannot
        // override it at the next hop. Session hints are ignored later, not authentication errors.
        val value = if (name in sessions || name in setOf("x-teamcode-workload", "x-teamcode-run-id")) supplied.singleOrNull() ?: "" else supplied.firstOrNull() ?: return@mapNotNull null
        name to value
    }.toMap()
}
