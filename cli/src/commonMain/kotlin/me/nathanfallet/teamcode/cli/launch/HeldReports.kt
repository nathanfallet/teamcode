package me.nathanfallet.teamcode.cli.launch

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The terminal of `teamcode run`. While the agent runs ([holding]) it owns the terminal (Claude Code's and Codex's
 * full-screen interfaces): a line written to stderr then lands inside its prompt box. So every line reported meanwhile
 * (a release notice, the proxy's errors, the launch heartbeat, the environment file's refresh) is kept, each distinct
 * line once in the order it was first reported, the first [limit], and printed through [print] once the agent has
 * exited, even when it failed or was cancelled: a later warning never hides an earlier notice. Past [limit], the
 * newest distinct line is kept too, after a count of the lines left out: a release published late in a long run is still
 * said. Outside [holding], before the launch and after the exit, lines are printed at once. Printing is best-effort: a
 * closed stderr never replaces the agent's result. TeamCode Desktop's update banner covers the live release notice.
 * [report] is safe from any thread (the proxy reports from its own).
 */
@OptIn(ExperimentalAtomicApi::class)
internal class HeldTerminal(private val print: (String) -> Unit, private val limit: Int = MAX_HELD_LINES) {
    private class Held(val lines: List<String>, val left: Int = 0, val newest: String? = null)

    // Null while nothing holds the terminal.
    private val held = AtomicReference<Held?>(null)

    fun report(line: String) {
        while (true) {
            val current = held.load() ?: return print(line)
            if (line in current.lines || line == current.newest) return
            val next = if (current.lines.size < limit) Held(current.lines + line)
            else Held(current.lines, current.left + (if (current.newest != null) 1 else 0), line)
            if (held.compareAndSet(current, next)) return
        }
    }

    suspend fun <T> holding(agent: suspend () -> T): T {
        held.store(Held(emptyList()))
        try {
            return agent()
        } finally {
            held.exchange(null)?.let { last ->
                val lines = last.lines + listOfNotNull(
                    last.left.takeIf { it > 0 }?.let { "($it more lines were left out while the agent ran.)" },
                    last.newest,
                )
                for (line in lines) try { print(line) } catch (_: Exception) { break }
            }
        }
    }

    private companion object {
        /** The hourly watcher and a failing proxy repeat themselves over a long run: a few distinct lines say it all. */
        const val MAX_HELD_LINES = 8
    }
}
