package me.nathanfallet.teamcode.cli.launch

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs [agent] with [watch] alongside it, writing nothing while the agent runs. The agent owns the terminal (Claude
 * Code's and Codex's full-screen interfaces): a line written to stderr then lands inside its prompt box. So what
 * [watch] reports (a release notice, a check that failed) is kept, each distinct line once in the order it was first
 * reported, the first [MAX_HELD_LINES], and printed through [print] once the agent has exited, even when it failed or
 * was cancelled: a later warning never hides an earlier notice. TeamCode Desktop's update banner covers the live notice.
 */
internal suspend fun <T> withHeldReports(print: (String) -> Unit, watch: suspend (report: (String) -> Unit) -> Unit, agent: suspend () -> T): T {
    // Written by the watcher's coroutine only, and read once it has been joined.
    val held = LinkedHashSet<String>()
    return coroutineScope {
        val watcher = launch { watch { line -> if (held.size < MAX_HELD_LINES) held += line } }
        try {
            agent()
        } finally {
            withContext(NonCancellable) { watcher.cancelAndJoin() }
            held.forEach(print)
        }
    }
}

/** The hourly watcher repeats itself over a long run: a few distinct lines say all it has to say. */
private const val MAX_HELD_LINES = 4

