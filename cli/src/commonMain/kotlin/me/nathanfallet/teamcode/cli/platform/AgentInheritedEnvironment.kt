package me.nathanfallet.teamcode.cli.platform

/**
 * The prefixes of the inherited variables the agent of a remote session never gets: every `TEAMCODE_` variable of the host
 * process (a local token, an outer launch's relay token, a GitHub or hook token, an address), since TeamCode sets the ones the
 * agent needs itself, as overrides. The member's own login is not in the environment but in the configuration file, which
 * this does not hide (docs/waits.md, "What a remote session's agent can still do").
 */
val REMOTE_AGENT_SCRUBBED_PREFIXES: List<String> = listOf("TEAMCODE_")

/** [inherited] without the variables whose name starts with one of [scrubPrefixes] (compared case-insensitively). */
fun agentInheritedEnvironment(inherited: Map<String, String>, scrubPrefixes: List<String>): Map<String, String> =
    if (scrubPrefixes.isEmpty()) inherited else inherited.filterKeys { name -> scrubPrefixes.none { name.uppercase().startsWith(it.uppercase()) } }
