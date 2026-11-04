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
        // What the server can see itself (an artifact, a wait of the session) it verifies: the caller's report on those checks is ignored.
        val observed = current(actor.teamId, id, itemId)?.let { serverChecks(actor.teamId, id, it) }.orEmpty()
        val list = store.mutate(actor.teamId, id) { current ->
            val item = current.item(itemId) ?: throw TeamFailure("unknown_item", 404, "No item '$itemId' in this todo.")
            val checks = item.doneWhen.checks
            if (reports.any { it.index !in checks.indices } || reports.map { it.index }.distinct().size != reports.size)
                throw TodoRules.invalid("invalid_proof", "Report each check of the item once, by its position 0 to ${checks.size - 1}.")
            val results = reports.filter { r -> checks[r.index] !in observed.keys }.map { r ->
                if (r.observed.length > TodoRules.MAX_PROOF || r.urls.size > 10 || r.urls.any { !it.startsWith("https://") || it.length > 2_000 })
                    throw TodoRules.invalid("invalid_proof", "Keep observed under ${TodoRules.MAX_PROOF} characters and give at most 10 https URLs.")
                TodoCheckResult(checks[r.index], r.passed, r.observed, r.urls)
            }
            when (val outcome = TodoRules.complete(current, itemId, results + observed.map { (check, result) -> result }, now)) {
                is TodoCompletion.Done -> outcome.list.touch(now) to outcome.list
                is TodoCompletion.Refused -> { refusal[0] = outcome.failure; outcome.list.touch(now) to outcome.list }
            }
        }
        refusal[0]?.let { throw it }
        return list
    }

    private suspend fun current(teamId: String, sessionId: String, itemId: String) = store.find(teamId, sessionId)?.item(itemId)

    /** The outcome of the checks the server verifies by itself: `artifact_published` and `wait_answered`, through what the item is linked to. */
    private suspend fun serverChecks(teamId: String, sessionId: String, item: TodoItem): Map<TodoCheck, TodoCheckResult> = buildMap {
        for (check in item.doneWhen.checks) when (check) {
            is TodoCheck.ArtifactPublished -> {
                val id = check.id ?: item.links.firstOrNull { it.kind == "artifact" }?.ref
                val result = if (id == null) TodoCheckResult(check, false, "No artifact is linked to this item: publish it, then link it with todo_link.")
                else store.artifactVersion(teamId, id).let { version ->
                    when {
                        version == null -> TodoCheckResult(check, false, "Artifact $id is not published.")
                        check.minVersion != null && version < check.minVersion -> TodoCheckResult(check, false, "Artifact $id is at version $version, version ${check.minVersion} is needed.")
                        else -> TodoCheckResult(check, true, "Artifact $id is published (version $version).")
                    }
                }
                put(check, result)
            }
            is TodoCheck.WaitAnswered -> {
                val id = item.links.firstOrNull { it.kind == "wait" }?.ref
                val state = id?.let { store.waitState(teamId, sessionId, it) }
                put(check, when {
                    id == null -> TodoCheckResult(check, false, "No wait is linked to this item: ask with ask_human, then link the wait with todo_link.")
                    state == null -> TodoCheckResult(check, false, "Wait $id is not one of this session's.")
                    state.status != "resolved" -> TodoCheckResult(check, false, "Wait $id is ${state.status}: nobody answered it yet.")
                    check.action != null && state.action != check.action -> TodoCheckResult(check, false, "Wait $id was answered with '${state.action}', not '${check.action}'.")
                    else -> TodoCheckResult(check, true, "Wait $id was answered with '${state.action}'.")
                })
            }
            else -> {}
        }
    }

    /** Binds a PR, a release, an artifact or a wait to an item, once (see [TodoRules.link]). */
    suspend fun link(actor: Principal, sessionId: String?, itemId: String, link: TodoLink, replaceClosed: Boolean): TodoList {
        val id = session(actor, sessionId, write = true)
        val now = store.now()
        return store.mutate(actor.teamId, id) { val changed = TodoRules.link(it, itemId, link, replaceClosed, now).copy(updatedAt = now); changed to changed }
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
