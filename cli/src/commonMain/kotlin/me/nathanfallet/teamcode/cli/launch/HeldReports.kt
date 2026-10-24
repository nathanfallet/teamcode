package me.nathanfallet.teamcode.cli.launch

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The terminal of `teamcode run`. While the agent runs ([holding]) it owns the terminal (Claude Code's and Codex's
 * full-screen interfaces): a line written to stderr then lands inside its prompt box. So every line reported meanwhile
 * (a release notice, the proxy's errors, the launch heartbeat, the environment file's refresh) is kept, each distinct
 * line once in the order it was first reported, the first [limit], and printed through [print] once the agent has
 * exited, even when it failed or was cancelled: a later warning never hides an earlier notice. Outside [holding],
 * before the launch and after the exit, lines are printed at once. TeamCode Desktop's update banner covers the live
 * release notice. [report] is safe from any thread (the proxy reports from its own).
 */
@OptIn(ExperimentalAtomicApi::class)
internal class HeldTerminal(private val print: (String) -> Unit, private val limit: Int = MAX_HELD_LINES) {
    // Null while nothing holds the terminal.
    private val held = AtomicReference<List<String>?>(null)

    fun report(line: String) {
        while (true) {
            val current = held.load() ?: return print(line)
            if (line in current || current.size >= limit) return
            if (held.compareAndSet(current, current + line)) return
        }
    }

    suspend fun <T> holding(agent: suspend () -> T): T {
        held.store(emptyList())
        try {
            return agent()
        } finally {
            held.exchange(null)?.forEach(print)
        }
    }

    private companion object {
        /** The hourly watcher and a failing proxy repeat themselves over a long run: a few distinct lines say it all. */
        const val MAX_HELD_LINES = 8
    }
}
