package me.nathanfallet.teamcode.domain.todos

import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.waits.WaitService
import me.nathanfallet.teamcode.domain.waits.WaitSessions

/**
 * The server-side reminder (docs/todos.md, "The server-side reminder"): at the end of a turn of a session whose todo still has open
 * items, with no wait open and no Stop hook having judged that turn, it sends the same resume message as the hook, with the same
 * counter. It is the way Codex, which runs no hook TeamCode controls, is held to its list, and the safety net for a Claude Code hook
 * that did not run (a failure, a hook not installed). Nothing is kept in memory: a pass reads the candidates and their turns in two
 * batched reads and [TodoService.remind] marks each turn end under the list's lock, so a second replica, or a restart, judges a turn once.
 */
class TodoReminderEngine(
    private val store: TodoReminderStore,
    private val todos: TodoService,
    private val sessions: WaitSessions,
) {
    /** One pass; the number of turn ends that were reminded or escalated. */
    suspend fun run(): Int {
        val now = todos.now()
        val candidates = store.candidates(now - ACTIVE_WINDOW_MILLIS, BATCH)
        if (candidates.isEmpty()) return 0
        val ends = store.turnEnds(candidates.map { it.sessionId })
        var acted = 0
        for (candidate in candidates) {
            val turn = ends[candidate.sessionId] ?: continue
            try {
                val member = store.member(candidate.teamId, candidate.ownerId)?.takeUnless { it.disabled } ?: continue
                val owner = Principal(member, WaitService.SYSTEM_CREDENTIAL, candidate.teamId)
                val outcome = todos.remind(candidate.teamId, owner, candidate.sessionId, turn) { text, key -> sessions.deliver(owner, candidate.sessionId, text, key) }
                if (outcome == TodoReminderOutcome.REMINDED || outcome == TodoReminderOutcome.ESCALATED) acted++
            } catch (error: CancellationException) { throw error } catch (_: Exception) { /* the next pass tries this turn again */ }
        }
        return acted
    }

    companion object {
        const val BATCH = 200
        /** A list nobody touched for this long is not looked at: its session is long gone or idle (the hook still applies when it wakes). */
        const val ACTIVE_WINDOW_MILLIS = 30L * 24 * 3_600_000
    }
}
