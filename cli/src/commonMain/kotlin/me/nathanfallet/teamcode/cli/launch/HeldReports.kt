package me.nathanfallet.teamcode.cli.launch

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs [agent] with [watch] alongside it, writing nothing while the agent runs. The agent owns the terminal (Claude
 * Code's and Codex's full-screen interfaces): a line written to stderr then lands inside its prompt box. So what
 * [watch] reports (a release notice) is kept, only the latest line, and printed through [print] once the agent has
 * exited, even when it failed or was cancelled. TeamCode Desktop's update banner covers the live notice.
 */
internal suspend fun <T> withHeldReports(print: (String) -> Unit, watch: suspend (report: (String) -> Unit) -> Unit, agent: suspend () -> T): T {
    // Written by the watcher's coroutine only, and read once it has been joined.
    var latest: String? = null
    return coroutineScope {
        val watcher = launch { watch { line -> latest = line } }
        try {
            agent()
        } finally {
            withContext(NonCancellable) { watcher.cancelAndJoin() }
            latest?.let(print)
        }
    }
}

