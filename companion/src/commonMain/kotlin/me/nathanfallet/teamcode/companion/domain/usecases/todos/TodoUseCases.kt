package me.nathanfallet.teamcode.companion.domain.usecases.todos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionTodo
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TodoRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed

/** What a screen shows of a session's todo: the list read last (null: none yet, or not readable), and whether the live feed is cut. */
data class TodoUpdate(val todo: SessionTodo?, val reconnecting: Boolean = false)

/** The message the "Verify against the plan" shortcut sends: an ordinary message, with the same right as a typed one. */
object TodoShortcuts {
    const val VERIFY = "Re-read the plan's reference and compare it with your todo: add with todo_add every point of the plan that is missing, then say what you added."

    /** The "Add an item…" shortcut: the member's [text] as the agent is asked to write it. */
    const val ADD_PREFIX = "Add to your todo: "
    fun add(text: String) = "$ADD_PREFIX${text.trim()}. Write it as a complete item (name, description, done-when) with todo_add."
}

/** One read of a session's todo, for a screen that does not follow it (the office card); null without `todos.v1` or without a list. */
class ReadTodoUseCase(private val todos: TodoRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, sessionId: String): SessionTodo? = guard(accountId) {
        if (Features.TODOS !in features.features(accountId)) null else todos.todo(accountId, sessionId)
    }
}

/**
 * Keeps a session's todo current. A server with `todos.live.v1` is followed through the app's one feed ([SessionFeed]):
 * the list is read when the feed opens (so nothing is missed between loading and following), after a gap, and at each
 * `cloud.todo` event of this session; the list is never rebuilt from the transcript. Without it (an older server) the
 * list is read once, and on return to the screen. A server without `todos.v1` yields nothing.
 */
class FollowTodoUseCase(
    private val todos: TodoRepository,
    private val feed: SessionFeed,
    private val features: FeaturesRepository,
    private val guard: AccountGuard,
) {
    operator fun invoke(accountId: String, sessionId: String): Flow<TodoUpdate> = guard.flow(accountId, flow {
        val announced = features.features(accountId)
        if (Features.TODOS !in announced) return@flow
        suspend fun load(reconnecting: Boolean = false): TodoUpdate? = try {
            TodoUpdate(todos.todo(accountId, sessionId), reconnecting)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (denied: CompanionException.Unauthorized) {
            throw denied
        } catch (_: Exception) {
            // Not readable now (offline, a 5xx, the credential cannot read it): the list on screen stays as it is.
            null
        }
        if (Features.TODOS_LIVE !in announced || Features.EVENTS !in announced) { load()?.let { emit(it) }; return@flow }
        feed.follow(accountId).collect { update ->
            when (update) {
                is LiveUpdate.Opened -> if (!update.resumed) load()?.let { emit(it) }
                LiveUpdate.Gap -> load()?.let { emit(it) }
                is LiveUpdate.Item -> if (update.value.type == SessionChange.TODO && update.value.sessionId == sessionId) load()?.let { emit(it) }
                is LiveUpdate.Reconnecting -> emit(TodoUpdate(todo = null, reconnecting = true))
                LiveUpdate.Ended -> Unit
            }
        }
    })
}
