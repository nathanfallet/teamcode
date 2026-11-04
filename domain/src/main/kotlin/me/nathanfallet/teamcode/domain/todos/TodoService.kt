package me.nathanfallet.teamcode.domain.todos

import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.waits.WaitRef
import java.util.UUID

/** One check's outcome as the process that ran it reports it: [index] is the position in the item's stored checks. */
data class TodoCheckReport(val index: Int, val passed: Boolean?, val observed: String, val urls: List<String> = emptyList())

/**
 * A session's todo: authorization, then the rules of [TodoRules] applied atomically by the [TodoStore]. The agent of a session
 * acts with its execution credential (`todo:session`), confined to its own session; the session's owner, or an administrator
 * for a read, names the session. No method deletes an item, rewrites a condition or marks an item done without its checks.
 */
class TodoService(private val store: TodoStore, private val drops: TodoDropWaits? = null) {
    private fun missing() = TeamFailure("not_found", 404, "Session not found.")

    private suspend fun session(actor: Principal, sessionId: String?, write: Boolean): String {
        if (write) actor.requireTodoWrite() else actor.requireTodoRead()
        store.revalidate(actor)
        if (actor.todoSessionOnly) {
            return store.executionSession(actor.tokenId) ?: throw TeamFailure("credential_scope", 403, "This credential is not bound to a cloud session.")
        }
        val id = sessionId ?: throw TeamFailure("invalid_request", 400, "Name the session.")
        val owner = store.sessionOwner(actor.teamId, id) ?: throw missing()
        if (owner != actor.member.id && (write || !actor.canReadTeam)) throw missing()
        return id
    }

    /** The session's list with its drop requests brought up to date, or null when it has none. */
    suspend fun get(actor: Principal, sessionId: String?): TodoList? {
        val id = session(actor, sessionId, write = false)
        return reconcile(actor.teamId, id)
    }

    suspend fun create(actor: Principal, sessionId: String?, reference: String, refs: List<WaitRef>, items: List<TodoItemDefinition>): TodoList {
        val id = session(actor, sessionId, write = true)
        val list = TodoRules.create(UUID.randomUUID().toString(), id, actor.teamId, reference, refs, items, store.now())
        store.create(list)
        return list
    }

    suspend fun add(actor: Principal, sessionId: String?, item: TodoItemDefinition, reason: String): TodoList {
        val id = session(actor, sessionId, write = true)
        val now = store.now()
        return store.mutate(actor.teamId, id) { val changed = TodoRules.add(it, item, reason).touch(now); changed to changed }
    }

    /** Puts an item in progress and returns the list with the marker to put in the item's PR. */
    suspend fun start(actor: Principal, sessionId: String?, itemId: String, background: Boolean, agent: String?): Pair<TodoList, String> {
        val id = session(actor, sessionId, write = true)
        val now = store.now()
        val list = store.mutate(actor.teamId, id) { val changed = TodoRules.start(it, itemId, background, agent, now).touch(now); changed to changed }
        return list to TodoRules.marker(list.id, itemId)
    }

    suspend fun note(actor: Principal, sessionId: String?, itemId: String, text: String): TodoList {
        val id = session(actor, sessionId, write = true)
        val now = store.now()
        return store.mutate(actor.teamId, id) { val changed = TodoRules.note(it, itemId, text, now).touch(now); changed to changed }
    }

    /**
     * Records what the checks of [itemId] observed. The item is done only when every check of its stored condition passed; the
     * caller names none of them, only reports each one's outcome by position. A refusal keeps the proof of what was seen and tells the
     * agent what is missing (`409 not_done`).
     */
    suspend fun complete(actor: Principal, sessionId: String?, itemId: String, reports: List<TodoCheckReport>): TodoList {
        val id = session(actor, sessionId, write = true)
        val now = store.now()
        val refusal = arrayOfNulls<TeamFailure>(1)
        val list = store.mutate(actor.teamId, id) { current ->
            val item = current.item(itemId) ?: throw TeamFailure("unknown_item", 404, "No item '$itemId' in this todo.")
            val checks = item.doneWhen.checks
            if (reports.any { it.index !in checks.indices } || reports.map { it.index }.distinct().size != reports.size)
                throw TodoRules.invalid("invalid_proof", "Report each check of the item once, by its position 0 to ${checks.size - 1}.")
            val results = reports.map { r ->
                if (r.observed.length > TodoRules.MAX_PROOF || r.urls.size > 10 || r.urls.any { !it.startsWith("https://") || it.length > 2_000 })
                    throw TodoRules.invalid("invalid_proof", "Keep observed under ${TodoRules.MAX_PROOF} characters and give at most 10 https URLs.")
                TodoCheckResult(checks[r.index], r.passed, r.observed, r.urls)
            }
            when (val outcome = TodoRules.complete(current, itemId, results, now)) {
                is TodoCompletion.Done -> outcome.list.touch(now) to outcome.list
                is TodoCompletion.Refused -> { refusal[0] = outcome.failure; outcome.list.touch(now) to outcome.list }
            }
        }
        refusal[0]?.let { throw it }
        return list
    }

    /** Asks the session's owner to drop an item. Nothing is deleted: the item waits in `drop_requested` for the answer. */
    suspend fun requestDrop(actor: Principal, sessionId: String?, itemId: String, reason: String): TodoList {
        val id = session(actor, sessionId, write = true)
        val waits = drops ?: throw TeamFailure("waits_unavailable", 503, "Waits are not available on this server.")
        val current = store.find(actor.teamId, id) ?: throw TeamFailure("todo_not_found", 404, "This session has no todo.")
        TodoRules.requestDrop(current, itemId, reason) // refuses before a wait is opened
        val waitId = waits.open(actor, id, current.item(itemId)!!, reason)
        val now = store.now()
        try {
            return store.mutate(actor.teamId, id) { val changed = TodoRules.requestDrop(it, itemId, reason, waitId).touch(now); changed to changed }
        } catch (e: Throwable) {
            runCatching { waits.cancel(actor, waitId) }
            throw e
        }
    }

    /** Applies the owner's answers to drop requests, in one read of the waits for all of the list's items. */
    private suspend fun reconcile(teamId: String, sessionId: String): TodoList? {
        val list = store.find(teamId, sessionId) ?: return null
        val pending = list.items.filter { it.status == TodoItemStatus.DROP_REQUESTED }.mapNotNull { it.dropWaitId }
        if (pending.isEmpty()) return list
        val outcomes = store.dropOutcomes(teamId, pending)
        if (outcomes.isEmpty()) return list
        val now = store.now()
        return store.mutate(teamId, sessionId) { current ->
            var changed = current
            current.items.filter { it.status == TodoItemStatus.DROP_REQUESTED }.forEach { item ->
                val outcome = item.dropWaitId?.let(outcomes::get) ?: return@forEach
                changed = TodoRules.resolveDrop(changed, item.id, outcome == DropOutcome.DROP)
            }
            changed.touch(now) to changed
        }
    }

    private fun TodoList.touch(now: Long) = copy(updatedAt = now)
}
