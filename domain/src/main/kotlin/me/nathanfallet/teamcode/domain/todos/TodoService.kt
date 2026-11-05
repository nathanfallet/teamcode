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
/** What [TodoService.remind] did with a turn end. */
enum class TodoReminderOutcome { NONE, SKIPPED, REMINDED, ESCALATED }

enum class TodoStopPhase { BEGIN, VERIFY, SETTLE }
enum class TodoStopAction(val wire: String) { ALLOW("allow"), VERIFY("verify"), SETTLE("settle"), BLOCK("block") }

/** The outcome of the checks the hook ran for one item, by position in the item's stored checks. */
data class TodoStopItemReport(val itemId: String, val checks: List<TodoCheckReport>)

/** What the Stop hook tells the server: [background] a background agent of the session still runs, [orphans] the PRs the session opened. */
data class TodoStopInput(
    val phase: TodoStopPhase, val background: Boolean = false,
    val items: List<TodoStopItemReport> = emptyList(), val orphans: List<String> = emptyList(), val lastMessage: String? = null,
)

/** [why] says why a stop passes (`no_list`, `finished`, `background_task`, `wait_open`, `escalated`, `settled`). [reason] is the message of a block. */
data class TodoStopDecision(
    val action: TodoStopAction, val list: TodoList?, val reason: String? = null, val why: String? = null,
    val escalated: Boolean = false,
)

private const val MAX_ORPHANS = 5

class TodoService(private val store: TodoStore, private val drops: TodoDropWaits? = null,
    /** Hands the answers of a wait to the Stop hook of a local session; null where local sessions do not exist (the hook then never holds). */
    private val wake: me.nathanfallet.teamcode.domain.cloud.LocalWake? = null) {
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
    private suspend fun serverChecks(teamId: String, sessionId: String, item: TodoItem): Map<TodoCheck, TodoCheckResult> = serverChecks(teamId, sessionId, listOf(item))[item.id].orEmpty()

    /** The same for several items, with one read of the artifacts and one of the waits however many items there are. */
    private suspend fun serverChecks(teamId: String, sessionId: String, items: List<TodoItem>): Map<String, Map<TodoCheck, TodoCheckResult>> {
        fun artifactOf(item: TodoItem, check: TodoCheck.ArtifactPublished) = check.id ?: item.links.firstOrNull { it.kind == "artifact" }?.ref
        val artifactIds = items.flatMap { item -> item.doneWhen.checks.filterIsInstance<TodoCheck.ArtifactPublished>().mapNotNull { artifactOf(item, it) } }.distinct()
        val waitIds = items.filter { item -> item.doneWhen.checks.any { it is TodoCheck.WaitAnswered } }.mapNotNull { item -> item.links.firstOrNull { it.kind == "wait" }?.ref }.distinct()
        val versions = if (artifactIds.isEmpty()) emptyMap() else store.artifactVersions(teamId, artifactIds)
        val states = if (waitIds.isEmpty()) emptyMap() else store.waitStates(teamId, sessionId, waitIds)
        return items.associate { item ->
            item.id to buildMap {
                for (check in item.doneWhen.checks) when (check) {
                    is TodoCheck.ArtifactPublished -> {
                        val id = artifactOf(item, check)
                        val version = id?.let { versions[it] }
                        put(check, when {
                            id == null -> TodoCheckResult(check, false, "No artifact is linked to this item: publish it, then link it with todo_link.")
                            version == null -> TodoCheckResult(check, false, "Artifact $id is not published.")
                            check.minVersion != null && version < check.minVersion -> TodoCheckResult(check, false, "Artifact $id is at version $version, version ${check.minVersion} is needed.")
                            else -> TodoCheckResult(check, true, "Artifact $id is published (version $version).")
                        })
                    }
                    is TodoCheck.WaitAnswered -> {
                        val id = item.links.firstOrNull { it.kind == "wait" }?.ref
                        val state = id?.let { states[it] }
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

    /**
     * What the Stop hook of a local session hears while it holds the agent for a wait (docs/waits.md, "A local run"): the answers that
     * arrived, and whether a wait is still open. A session that is not local, has no open wait or is gone answers `open = false` at once.
     */
    suspend fun wake(actor: Principal, sessionId: String?, waitMillis: Long): me.nathanfallet.teamcode.domain.cloud.LocalWakeResult {
        val id = session(actor, sessionId, write = true)
        return wake?.poll(actor.teamId, id, waitMillis) ?: me.nathanfallet.teamcode.domain.cloud.LocalWakeResult(false, emptyList())
    }

    /**
     * One stop of the agent (`teamcode cloud todo-stop`, docs/todos.md "The Stop hook"). It is asked up to three times, each answer
     * telling the hook what to run next: [TodoStopPhase.BEGIN] (nothing to hold back? else which checks to run), then
     * [TodoStopPhase.VERIFY] with the outcome of the cheap checks and the pull requests the session opened, then, when everything is
     * closed, [TodoStopPhase.SETTLE] with the outcome of every check of the done items. A session without a list, a list that is
     * finished, a background agent still running or any open wait of the session lets the stop pass without a question.
     */
    suspend fun stop(actor: Principal, sessionId: String?, input: TodoStopInput): TodoStopDecision {
        val id = session(actor, sessionId, write = true)
        val list = reconcile(actor.teamId, id) ?: return TodoStopDecision(TodoStopAction.ALLOW, null, why = "no_list")
        if (list.status != TodoListStatus.ACTIVE) return TodoStopDecision(TodoStopAction.ALLOW, list, why = "finished")
        if (input.background || store.hasOpenWait(actor.teamId, id)) {
            val why = if (input.background) "background_task" else "wait_open"
            val at = store.now()
            return TodoStopDecision(TodoStopAction.ALLOW, store.mutate(actor.teamId, id) { current -> current.copy(lastHookAt = at).let { it to it } }, why = why)
        }
        val now = store.now()
        // The hook asked about this turn's end: the server-side reminder does not judge it again (one small write per stop).
        if (input.phase == TodoStopPhase.BEGIN) return TodoStopDecision(TodoStopAction.VERIFY, store.mutate(actor.teamId, id) { current -> current.copy(lastHookAt = now).let { it to it } })
        validate(input)
        val wanted = input.items.mapNotNull { r -> list.item(r.itemId) }
        val observed = if (wanted.isEmpty()) emptyMap() else serverChecks(actor.teamId, id, wanted)
        var settled = false
        var counted: TodoStop? = null
        // The ticks, the final verification and the counter are one change under the list's lock, so two replicas stopping at once count once each
        // and only one of them sees the limit.
        val after = store.mutate(actor.teamId, id) { current ->
            var changed = current
            if (input.phase == TodoStopPhase.VERIFY) {
                input.orphans.forEach { changed = TodoRules.addOrphan(changed, it, now) }
                for (report in input.items) {
                    val item = changed.item(report.itemId) ?: continue
                    if (item.status != TodoItemStatus.TODO && item.status != TodoItemStatus.DOING) continue
                    val results = resultsOf(item, report, observed[item.id].orEmpty())
                    changed = when (val outcome = TodoRules.complete(changed, item.id, results, now)) { is TodoCompletion.Done -> outcome.list; is TodoCompletion.Refused -> outcome.list }
                }
            } else if (changed.allClosed) {
                val results = input.items.mapNotNull { report -> changed.item(report.itemId)?.takeIf { it.status == TodoItemStatus.DONE }?.let { it.id to resultsOf(it, report, observed[it.id].orEmpty()) } }.toMap()
                changed = TodoRules.settle(changed, results, now)
                settled = changed.status == TodoListStatus.DONE
            }
            if (!settled && !changed.allClosed) {
                val stop = TodoRules.stop(changed)
                counted = stop
                // At the limit the counter starts again right here: the replica that sees it asks the owner, another one counts from zero.
                changed = if (stop.escalate) TodoRules.resetStall(stop.list) else stop.list
            }
            changed.copy(updatedAt = now) to changed
        }
        if (settled) return TodoStopDecision(TodoStopAction.ALLOW, after, why = "settled")
        if (after.allClosed && input.phase == TodoStopPhase.VERIFY) return TodoStopDecision(TodoStopAction.SETTLE, after)
        if (counted?.escalate != true) return TodoStopDecision(TodoStopAction.BLOCK, after, reason = TodoMessages.resume(after))
        return escalate(actor, id, after, input.lastMessage, now)
    }

    /** The block a session is reminded of after a compaction or on resume ([TodoMessages.context]), or null without a list. */
    suspend fun context(actor: Principal, sessionId: String?): String? {
        val id = session(actor, sessionId, write = false)
        return wakeBlock(actor.teamId, id)
    }

    /**
     * The block that follows the message waking a session (an answer, a delay, an event): the reference, the list with the answers to
     * drop requests applied, and the next item. Null when the session has no list or its list is finished, so a session without a todo
     * gets exactly the message it got before.
     */
    suspend fun wakeBlock(teamId: String, sessionId: String): String? {
        val list = reconcile(teamId, sessionId) ?: return null
        return if (list.status == TodoListStatus.ACTIVE) TodoMessages.context(list) else null
    }

    /**
     * The third stop without progress (the hook's or the server-side reminder's): ask the owner outside any lock, mark the item that is
     * stuck as waiting on that answer, and let the stop pass.
     */
    private suspend fun escalate(actor: Principal, id: String, after: TodoList, lastMessage: String?, now: Long): TodoStopDecision {
        val waits = drops ?: throw TeamFailure("waits_unavailable", 503, "Waits are not available on this server.")
        val stuck = TodoRules.next(after) ?: after.items.firstOrNull { it.status == TodoItemStatus.DOING }
        val waitId = waits.openBlocked(actor, id, TodoMessages.blockedTitle(after), TodoMessages.blockedBody(after, lastMessage), dropItem = stuck != null)
        val saved = try {
            store.mutate(actor.teamId, id) { current ->
                var changed = current
                if (stuck != null && changed.item(stuck.id)?.status.let { it == TodoItemStatus.TODO || it == TodoItemStatus.DOING })
                    changed = TodoRules.requestDrop(changed, stuck.id, "The agent stopped three times without progress on this item.", waitId)
                changed = TodoRules.recordEvent(changed, "blocked", "Blocked on ${stuck?.id ?: "the todo"}: a wait asks the owner.", now)
                changed.copy(updatedAt = now) to changed
            }
        } catch (e: Throwable) {
            runCatching { waits.cancel(actor, waitId) }
            throw e
        }
        return TodoStopDecision(TodoStopAction.ALLOW, saved, why = "escalated", escalated = true)
    }

    /**
     * The server-side reminder (Codex, which runs no Stop hook, and the safety net for a Claude Code hook that did not run): called for a
     * session whose turn [turn] ended normally. Judges each turn end once, across replicas and restarts (the turn's sequence is marked
     * under the list's lock, first); lets it go when the hook already judged this turn, when a wait of the session is open, or when no item
     * remains open. Otherwise it counts the stop like the hook does (same counter, same limit and wait) and, below the limit, hands the
     * resume message to [deliver]; a delivery that fails puts the counter back so the next pass tries again. The server reads no GitHub: it goes by the last proof, so it ticks nothing.
     */
    suspend fun remind(teamId: String, owner: Principal, sessionId: String, turn: TodoTurnEnd, deliver: suspend (text: String, key: String) -> Unit): TodoReminderOutcome {
        val now = store.now()
        val before = store.find(teamId, sessionId) ?: return TodoReminderOutcome.NONE
        if (before.status != TodoListStatus.ACTIVE || before.allClosed || (before.reminderSeq ?: -1) >= turn.seq) return TodoReminderOutcome.NONE
        val openWait = store.hasOpenWait(teamId, sessionId)
        var outcome = TodoReminderOutcome.NONE
        var counted: TodoStop? = null
        var previous: TodoList? = null
        val after = store.mutate(teamId, sessionId) { current ->
            if (current.status != TodoListStatus.ACTIVE || current.allClosed || (current.reminderSeq ?: -1) >= turn.seq) return@mutate current to current
            previous = current
            var changed = current.copy(reminderSeq = turn.seq)
            val hookRan = current.lastHookAt != null && turn.startedAt != null && current.lastHookAt >= turn.startedAt
            if (hookRan || openWait) { outcome = TodoReminderOutcome.SKIPPED; return@mutate changed to changed }
            val stop = TodoRules.stop(changed)
            counted = stop
            outcome = if (stop.escalate) TodoReminderOutcome.ESCALATED else TodoReminderOutcome.REMINDED
            changed = if (stop.escalate) TodoRules.resetStall(stop.list) else stop.list
            changed.copy(updatedAt = now) to changed.copy(updatedAt = now)
        }
        if (outcome == TodoReminderOutcome.ESCALATED) { escalate(owner, sessionId, after, null, now); return outcome }
        if (outcome != TodoReminderOutcome.REMINDED) return outcome
        try {
            deliver(TodoMessages.resume(after), "todo-reminder-${after.id}-${turn.seq}")
        } catch (e: Throwable) {
            // Not delivered: the turn is judged again at the next pass, with the counter as it was.
            val back = previous
            if (back != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { store.mutate(teamId, sessionId) { current -> current.copy(stall = back.stall, lastStop = back.lastStop, reminderSeq = back.reminderSeq).let { it to it } } }
            }
            throw e
        }
        return outcome
    }

    /** The store's clock, for the engine that drives [remind]. */
    suspend fun now(): Long = store.now()

    /** The open items of the session's active list, for a workflow step that must not deliver before the list is done: null when there is none. */
    suspend fun openItems(teamId: String, sessionId: String): List<TodoItem>? {
        val list = store.find(teamId, sessionId) ?: return null
        if (list.status != TodoListStatus.ACTIVE || list.allClosed) return null
        return list.items.filter { it.status != TodoItemStatus.DONE && it.status != TodoItemStatus.DROPPED }
    }

    /** What the hook failed to do (relay unreachable, slow, a server too old): recorded so a broken hook shows in the companion. */
    suspend fun hookError(actor: Principal, sessionId: String?, text: String): TodoList {
        val id = session(actor, sessionId, write = true)
        val now = store.now()
        return store.mutate(actor.teamId, id) { current -> TodoRules.recordEvent(current, "hook_error", text, now).copy(updatedAt = now).let { it to it } }
    }

    private fun validate(input: TodoStopInput) {
        if (input.items.size > TodoRules.MAX_ITEMS || input.orphans.size > MAX_ORPHANS || input.items.map { it.itemId }.distinct().size != input.items.size)
            throw TodoRules.invalid("invalid_stop", "Report each item once, at most ${TodoRules.MAX_ITEMS} items and $MAX_ORPHANS pull requests.")
    }

    /** The results of one item: what the server saw for its own checks, what the hook reported (by position) for the others. */
    private fun resultsOf(item: TodoItem, report: TodoStopItemReport, observed: Map<TodoCheck, TodoCheckResult>): List<TodoCheckResult> {
        val checks = item.doneWhen.checks
        if (report.checks.any { it.index !in checks.indices } || report.checks.map { it.index }.distinct().size != report.checks.size)
            throw TodoRules.invalid("invalid_proof", "Report each check of '${item.id}' once, by its position 0 to ${checks.size - 1}.")
        val reported = report.checks.filter { r -> checks[r.index] !in observed.keys }.map { r ->
            if (r.observed.length > TodoRules.MAX_PROOF || r.urls.size > 10 || r.urls.any { !it.startsWith("https://") || it.length > 2_000 })
                throw TodoRules.invalid("invalid_proof", "Keep observed under ${TodoRules.MAX_PROOF} characters and give at most 10 https URLs.")
            TodoCheckResult(checks[r.index], r.passed, r.observed, r.urls)
        }
        return reported + observed.values
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
