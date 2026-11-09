package me.nathanfallet.teamcode.domain.todos

import me.nathanfallet.teamcode.domain.team.TeamFailure

/** Bounds and pure rules of a session's todo (docs/todos.md): nothing here reads a store, a clock or GitHub. */
object TodoRules {
    const val MAX_REFERENCE = 4_000
    const val MAX_REFS = 10
    const val MAX_ITEMS = 500
    const val MAX_ID = 40
    const val MAX_NAME = 80
    const val MIN_DESCRIPTION = 80
    const val MAX_DESCRIPTION = 4_000
    const val MAX_DONE_TEXT = 1_000
    const val MAX_NOTE = 2_000
    const val MAX_NOTES_PER_ITEM = 100
    const val MAX_PROOF = 2_000
    const val MAX_REASON = 1_000
    const val DEFAULT_COMMAND_SECONDS = 60
    const val MAX_COMMAND_SECONDS = 120
    const val MAX_COMMAND = 2_000
    /** Stops without progress before the hook asks a person (docs/todos.md, section 5). */
    const val STALL_LIMIT = 3
    const val MAX_HOOK_EVENTS = 20
    const val MAX_HOOK_TEXT = 500

    private val slug = Regex("[a-z0-9][a-z0-9-]{0,${MAX_ID - 1}}")
    private val repo = Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")
    /** A PR or a release written in advance: `pull/12`, `pulls/12`, `PR #12`, `releases/tag/v1.2.3`. */
    private val guessed = Regex("""(?i)(pulls?/\d+|\bPR\s*#?\d+|releases/tag/v?\d)""")
    private val cited = Regex("""\$\{(?:pr|release):([^.}]+)\.""")
    private val markerPattern = Regex("""<!-- teamcode-todo: ([A-Za-z0-9]+)/([a-z0-9][a-z0-9-]*) -->""")

    fun invalid(code: String, message: String) = TeamFailure(code, 400, message)
    private fun conflict(code: String, message: String) = TeamFailure(code, 409, message)

    private fun text(value: String, max: Int, what: String) {
        if (value.isBlank() || value.length > max) throw invalid("invalid_todo", "Use $what of 1 to $max characters.")
    }

    /** The marker an agent puts in the body of an item's PR, so the PR is found whatever number it gets. */
    fun marker(listId: String, itemId: String) = "<!-- teamcode-todo: ${listId.take(8)}/$itemId -->"
    /** The (list prefix, item id) pairs of the markers found in [body]. */
    fun markers(body: String) = markerPattern.findAll(body).map { it.groupValues[1] to it.groupValues[2] }.toList()

    fun validateReference(reference: String, refs: List<*>) {
        text(reference, MAX_REFERENCE, "a reference")
        if (refs.size > MAX_REFS) throw invalid("invalid_todo", "At most $MAX_REFS references.")
    }

    /** Checks one item on its own: its bounds and its checks. Dependencies and the `of` of a check need the whole list. */
    fun validateItem(item: TodoItemDefinition) {
        if (!slug.matches(item.id)) throw invalid("invalid_todo", "Item id '${item.id}' must be a slug of at most $MAX_ID characters: lowercase letters, digits and dashes.")
        text(item.name, MAX_NAME, "a name")
        if (item.description.length < MIN_DESCRIPTION || item.description.length > MAX_DESCRIPTION) {
            throw invalid("description_too_short", "Item ${item.id}: write a complete description of $MIN_DESCRIPTION to $MAX_DESCRIPTION characters (what, where, why, what is out of scope), enough for a new agent to do it alone.")
        }
        text(item.doneWhen.text, MAX_DONE_TEXT, "a done condition text")
        if (item.doneWhen.checks.isEmpty()) {
            throw invalid("check_required", "Item ${item.id} has no typed check: add one of pr_merged, release_contains, ci_green, command, artifact_published, wait_answered, or attested if nothing can be checked.")
        }
        item.doneWhen.checks.forEach { validateCheck(item.id, it) }
    }

    private fun validateCheck(itemId: String, check: TodoCheck) {
        fun repo(value: String) {
            if (!repo.matches(value)) throw invalid("invalid_check", "Item $itemId: ${check.type} needs repo as owner/name.")
        }
        fun noGuess(value: String) {
            if (guessed.containsMatchIn(value)) {
                throw invalid("guessed_number", "Item $itemId: do not put a PR number or a version you expect: use pr_merged with the repo and put the marker of todo_start in the PR body.")
            }
        }
        when (check) {
            is TodoCheck.PrMerged -> repo(check.repo)
            is TodoCheck.PrClosed -> { repo(check.repo); text(check.reason, MAX_REASON, "a reason") }
            is TodoCheck.ReleaseContains -> {
                repo(check.repo)
                if (check.of == null && check.commit == null) throw invalid("invalid_check", "Item $itemId: release_contains needs of (the id of the item whose PR is shipped) or commit.")
            }
            is TodoCheck.CiGreen -> {
                repo(check.repo)
                if (check.of == null && check.ref == null) throw invalid("invalid_check", "Item $itemId: ci_green needs of (the id of an item) or ref.")
                check.ref?.let(::noGuess)
            }
            is TodoCheck.Command -> {
                text(check.run, MAX_COMMAND, "a command")
                if (check.timeoutSeconds !in 1..MAX_COMMAND_SECONDS) throw invalid("invalid_check", "Item $itemId: a command times out in 1 to $MAX_COMMAND_SECONDS seconds.")
                noGuess(check.run)
            }
            is TodoCheck.ArtifactPublished -> if (check.minVersion != null && check.minVersion < 1) throw invalid("invalid_check", "Item $itemId: minVersion starts at 1.")
            is TodoCheck.WaitAnswered -> {}
            is TodoCheck.Attested -> text(check.what, MAX_DONE_TEXT, "what is attested")
        }
    }

    /** Items cited by a check (`of`, `${pr:x.number}`), each of which must exist in the list. */
    private fun cites(item: TodoItemDefinition) = item.doneWhen.checks.flatMap { check ->
        when (check) {
            is TodoCheck.ReleaseContains -> listOfNotNull(check.of)
            is TodoCheck.CiGreen -> listOfNotNull(check.of)
            is TodoCheck.Command -> cited.findAll(check.run).map { it.groupValues[1] }.toList()
            else -> emptyList()
        }
    }

    private fun validateLinks(item: TodoItemDefinition, known: Set<String>) {
        item.dependsOn.forEach {
            if (it == item.id) throw invalid("dependency_cycle", "Item ${item.id} depends on itself.")
            if (it !in known) throw invalid("unknown_dependency", "Item ${item.id} depends on '$it', which is not in the list.")
        }
        cites(item).forEach {
            if (it !in known) throw invalid("unknown_dependency", "Item ${item.id} cites '$it' in a check, which is not in the list.")
        }
    }

    private fun checkAcyclic(items: List<TodoItemDefinition>) {
        val byId = items.associateBy { it.id }
        val state = mutableMapOf<String, Boolean>() // false: on the path, true: cleared
        fun visit(id: String) {
            when (state[id]) {
                true -> return
                false -> throw invalid("dependency_cycle", "The dependencies of '$id' form a cycle.")
                null -> {}
            }
            state[id] = false
            byId.getValue(id).dependsOn.forEach(::visit)
            state[id] = true
        }
        byId.keys.forEach(::visit)
    }

    private fun toItem(definition: TodoItemDefinition, origin: TodoOrigin, reason: String?) = TodoItem(
        definition.id, definition.name, definition.description, definition.doneWhen, definition.dependsOn.distinct(),
        origin, reason, TodoItemStatus.TODO, null, emptyList(),
    )

    /** A new list, active at once. One list per session: the caller refuses [create] when [TodoList] already exists. */
    fun create(
        id: String, sessionId: String, teamId: String, reference: String, refs: List<me.nathanfallet.teamcode.domain.waits.WaitRef>,
        items: List<TodoItemDefinition>, now: Long,
    ): TodoList {
        validateReference(reference, refs)
        if (items.isEmpty()) throw invalid("invalid_todo", "A todo needs at least one item.")
        if (items.size > MAX_ITEMS) throw invalid("too_many_items", "At most $MAX_ITEMS items: split the plan into several sessions, or group items.")
        val duplicate = items.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) throw invalid("duplicate_item", "Item id '${duplicate.key}' is used twice.")
        val known = items.map { it.id }.toSet()
        items.forEach { validateItem(it); validateLinks(it, known) }
        checkAcyclic(items)
        return TodoList(id, sessionId, teamId, reference, refs, TodoListStatus.ACTIVE, 0, null, items.map { toItem(it, TodoOrigin.PLAN, null) }, now, now)
    }

    /** Refuses a second list for a session. */
    fun requireNoList(existing: TodoList?) {
        if (existing != null) throw conflict("todo_exists", "This session already has a todo: use todo_add to add an item.")
    }

    private fun requireActive(list: TodoList) {
        if (list.status != TodoListStatus.ACTIVE) throw conflict("todo_closed", "This todo is ${list.status.wire}.")
    }
    private fun requireItem(list: TodoList, id: String) = list.item(id) ?: throw TeamFailure("unknown_item", 404, "No item '$id' in this todo.")

    /** A dropped dependency no longer blocks: the owner decided it makes no sense, and waiting on it would stall the list for ever. */
    private fun satisfied(list: TodoList, id: String) = list.item(id)?.status.let { it == TodoItemStatus.DONE || it == TodoItemStatus.DROPPED }

    /** The first `todo` item, in list order, whose dependencies are all finished; null when there is none. */
    fun next(list: TodoList): TodoItem? =
        list.items.firstOrNull { it.status == TodoItemStatus.TODO && it.dependsOn.all { dep -> satisfied(list, dep) } }

    /**
     * Adds an item found on the way, with a [reason]. It goes after the item in progress (or at the end), and after its own
     * dependencies, which advance it.
     */
    fun add(list: TodoList, definition: TodoItemDefinition, reason: String): TodoList {
        requireActive(list)
        text(reason, MAX_REASON, "a reason")
        if (list.items.size >= MAX_ITEMS) throw invalid("too_many_items", "At most $MAX_ITEMS items.")
        if (list.item(definition.id) != null) throw invalid("duplicate_item", "Item id '${definition.id}' is already used.")
        val known = list.items.map { it.id }.toSet() + definition.id
        validateItem(definition)
        validateLinks(definition, known)
        val doing = list.items.indexOfLast { it.status == TodoItemStatus.DOING }
        val afterDeps = definition.dependsOn.maxOfOrNull { dep -> list.items.indexOfFirst { it.id == dep } } ?: -1
        val position = maxOf(if (doing >= 0) doing + 1 else list.items.size, afterDeps + 1)
        val items = list.items.toMutableList().apply { add(position, toItem(definition, TodoOrigin.DISCOVERED, reason)) }
        return list.copy(items = items)
    }

    /**
     * Puts [id] in progress. Another item in progress goes back to `todo`, unless [background]: an item given to a background
     * agent stays in progress while the agent runs.
     */
    fun start(list: TodoList, id: String, background: Boolean = false, agent: String? = null, now: Long = 0): TodoList {
        requireActive(list)
        val item = requireItem(list, id)
        if (item.status == TodoItemStatus.DONE || item.status == TodoItemStatus.DROPPED) throw conflict("item_closed", "Item $id is ${item.status.wire}.")
        if (item.status == TodoItemStatus.DROP_REQUESTED) throw conflict("drop_pending", "Item $id waits for the owner to answer its drop request.")
        val missing = item.dependsOn.filterNot { satisfied(list, it) }
        if (missing.isNotEmpty()) throw conflict("dependency_open", "Item $id needs ${missing.joinToString()} done first.")
        val items = list.items.map {
            when {
                it.id == id -> it.copy(status = TodoItemStatus.DOING, notes = if (background && agent != null) it.notes + TodoNote(now, "Given to background agent $agent.") else it.notes)
                !background && it.status == TodoItemStatus.DOING -> it.copy(status = TodoItemStatus.TODO)
                else -> it
            }
        }
        return list.copy(items = items)
    }

    /** Appends a dated note. Notes are never rewritten. */
    fun note(list: TodoList, id: String, text: String, now: Long): TodoList {
        requireActive(list)
        text(text, MAX_NOTE, "a note")
        val item = requireItem(list, id)
        if (item.notes.size >= MAX_NOTES_PER_ITEM) throw invalid("too_many_notes", "An item holds at most $MAX_NOTES_PER_ITEM notes.")
        return list.copy(items = list.items.map { if (it.id == id) it.copy(notes = it.notes + TodoNote(now, text)) else it })
    }

    /**
     * Applies the outcome of running the end condition of [id]: every check must pass for the item to be done, with the observed
     * proof. A check that did not answer is "unknown", never done. The agent cannot force it: it has no way to give a verdict.
     */
    fun complete(list: TodoList, id: String, results: List<TodoCheckResult>, now: Long): TodoCompletion {
        requireActive(list)
        val item = requireItem(list, id)
        if (item.status == TodoItemStatus.DROP_REQUESTED || item.status == TodoItemStatus.DROPPED) throw conflict("item_closed", "Item $id is ${item.status.wire}.")
        val failed = results.filter { it.passed != true }
        val missing = item.doneWhen.checks.filterNot { check -> results.any { it.check == check } }
        val summary = results.joinToString("; ") { it.observed }.take(MAX_PROOF)
        val urls = results.flatMap { it.urls }.distinct().take(10)
        if (failed.isNotEmpty() || missing.isNotEmpty()) {
            val why = if (missing.isNotEmpty()) "not every check ran: ${missing.joinToString { it.type }}" else failed.joinToString("; ") { if (it.passed == null) "unknown: ${it.observed}" else it.observed }
            val proof = TodoProof(now, if (failed.any { it.passed == null }) null else false, summary.ifBlank { why }.take(MAX_PROOF), urls)
            return TodoCompletion.Refused(list.copy(items = list.items.map { if (it.id == id) it.copy(proof = proof) else it }), TeamFailure("not_done", 409, "Item $id is not done: $why"))
        }
        val proof = TodoProof(now, true, summary.take(MAX_PROOF), urls)
        return TodoCompletion.Done(list.copy(items = list.items.map { if (it.id == id) it.copy(status = TodoItemStatus.DONE, proof = proof) else it }))
    }

    /**
     * Binds [link] to [id]. A link is frozen: the same address may be completed (a PR learns its number and merge commit, a
     * release its tag), but another one of the same kind is refused with `already_linked`, so the agent cannot swap in an easier
     * PR. The one exception is a PR that was closed without merge ([replaceClosed], which the caller has just observed): the new
     * one replaces it and a note records the change.
     */
    fun link(list: TodoList, id: String, link: TodoLink, replaceClosed: Boolean, now: Long): TodoList {
        requireActive(list)
        val item = requireItem(list, id)
        if (link.kind !in TodoLink.kinds) throw invalid("invalid_link", "Use a link of kind ${TodoLink.kinds.joinToString(", ")}.")
        if (link.ref.isBlank() || link.ref.length > 2_000 || link.ref.any { it.isISOControl() }) throw invalid("invalid_link", "Use a link reference of 1 to 2000 characters.")
        if (link.kind in setOf("pr", "release") && !link.ref.startsWith("https://")) throw invalid("invalid_link", "A ${link.kind} link is an https address.")
        val existing = item.links.firstOrNull { it.kind == link.kind }
        val stamped = link.copy(at = now)
        val links = when {
            existing == null -> item.links + stamped
            existing.ref == link.ref -> item.links.map { if (it === existing) existing.copy(number = link.number ?: existing.number, sha = link.sha ?: existing.sha, tag = link.tag ?: existing.tag) else it }
            link.kind == "pr" && replaceClosed -> item.links.map { if (it === existing) stamped else it }
            else -> throw conflict("already_linked", "Item $id is already linked to ${existing.ref}: a link does not change. Only a PR closed without merge may be replaced.")
        }
        val noted = if (existing != null && existing.ref != link.ref) item.notes + TodoNote(now, "Linked ${link.ref} instead of ${existing.ref}, which was closed without merge.") else item.notes
        return list.copy(items = list.items.map { if (it.id == id) it.copy(links = links, notes = noted) else it })
    }

    /** Asks the owner to drop an item. Nothing is deleted: the item waits in `drop_requested` for the answer. */
    fun requestDrop(list: TodoList, id: String, reason: String, waitId: String? = null): TodoList {
        requireActive(list)
        text(reason, MAX_REASON, "a reason")
        val item = requireItem(list, id)
        if (item.status == TodoItemStatus.DONE || item.status == TodoItemStatus.DROPPED) throw conflict("item_closed", "Item $id is ${item.status.wire}.")
        if (item.status == TodoItemStatus.DROP_REQUESTED) throw conflict("drop_pending", "Item $id already waits for the owner.")
        return list.copy(items = list.items.map { if (it.id == id) it.copy(status = TodoItemStatus.DROP_REQUESTED, dropReason = reason, dropWaitId = waitId) else it })
    }

    /** The owner's answer: "yes" drops the item, anything else puts it back to `todo`. */
    fun resolveDrop(list: TodoList, id: String, approved: Boolean): TodoList {
        val item = requireItem(list, id)
        if (item.status != TodoItemStatus.DROP_REQUESTED) throw conflict("no_drop_request", "Item $id has no drop request.")
        return list.copy(items = list.items.map { if (it.id == id) it.copy(status = if (approved) TodoItemStatus.DROPPED else TodoItemStatus.TODO, dropWaitId = null) else it })
    }

    /** The owner abandons the whole list. */
    fun abandon(list: TodoList) = list.copy(status = TodoListStatus.ABANDONED)

    /**
     * The final verification once every item is closed: [results] maps the id of each done item to its checks' outcomes. An item
     * whose conditions no longer all pass (a reverted PR, a deleted release) is reopened; if none is, the list is done. An
     * `attested` check is never re-run: its proof was given once with the completion and nothing can observe it again, so the
     * hook's "no proof" at this phase must not undo what the agent validated.
     */
    fun settle(list: TodoList, results: Map<String, List<TodoCheckResult>>, now: Long): TodoList {
        if (list.status != TodoListStatus.ACTIVE || !list.allClosed) return list
        val reopened = list.items.filter { item ->
            item.status == TodoItemStatus.DONE && item.doneWhen.checks.any { check -> check !is TodoCheck.Attested && results[item.id]?.firstOrNull { it.check == check }?.passed == false }
        }.map { it.id }.toSet()
        if (reopened.isEmpty()) return list.copy(status = TodoListStatus.DONE)
        return list.copy(items = list.items.map { item ->
            if (item.id !in reopened) item else {
                val failed = results.getValue(item.id).filter { it.passed == false && it.check !is TodoCheck.Attested }
                item.copy(status = TodoItemStatus.TODO, proof = TodoProof(now, false, failed.joinToString("; ") { it.observed }.take(MAX_PROOF), failed.flatMap { it.urls }.distinct().take(10)))
            }
        })
    }

    /**
     * Registers a stop that was blocked: progress (an item done or added, a note written) since the last blocked stop resets the
     * counter, no progress adds one; at [STALL_LIMIT] the hook opens a wait instead of blocking again.
     */
    fun stop(list: TodoList): TodoStop {
        val last = list.lastStop
        val now = list.progress
        val progressed = last != null && (now.done > last.done || now.items > last.items || now.notes > last.notes)
        val stall = if (progressed) 0 else list.stall + 1
        return TodoStop(list.copy(stall = stall, lastStop = now), progressed, stall >= STALL_LIMIT)
    }

    /** Keeps what the Stop hook says about itself, newest last, bounded. */
    fun recordEvent(list: TodoList, kind: String, text: String, now: Long): TodoList {
        require(kind in TodoHookEvent.kinds)
        return list.copy(hookEvents = (list.hookEvents + TodoHookEvent(now, kind, text.filter { !it.isISOControl() || it == '\n' }.take(MAX_HOOK_TEXT))).takeLast(MAX_HOOK_EVENTS))
    }

    /**
     * A pull request opened in the session that no item covers becomes an item of its own, already linked: "Finish PR #n". A PR
     * that an item is linked to, or that is already an item, is not added twice.
     */
    fun addOrphan(list: TodoList, url: String, now: Long): TodoList {
        val match = Regex("https://github\\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/pull/(\\d+)").matchEntire(url) ?: return list
        if (list.status != TodoListStatus.ACTIVE || list.items.size >= MAX_ITEMS || list.items.any { item -> item.links.any { it.ref == url } }) return list
        val repo = match.groupValues[1]
        val number = match.groupValues[2].toInt()
        var id = "finish-pr-$number"
        var n = 2
        while (list.item(id) != null) id = "finish-pr-$number-${n++}"
        val definition = TodoItemDefinition(
            id, "Finish PR #$number", "A pull request you opened in this session, $url, is not covered by any item of the plan. Get it merged, or ask the owner to close it " +
                "with todo_drop if it no longer makes sense. Out of scope: any other change.",
            TodoDoneWhen("PR #$number of $repo is merged", listOf(TodoCheck.PrMerged(repo))),
        )
        val added = add(list, definition, "Opened in this session and not covered by an item.")
        return link(added, id, TodoLink("pr", url, number), false, now)
    }

    /** After the person unblocked the session, the counter starts again. */
    fun resetStall(list: TodoList) = list.copy(stall = 0)

    /** Share of items that are only attested; a list over a third is flagged in the companion. */
    fun mostlyAttested(list: TodoList): Boolean {
        val attested = list.items.count { item -> item.doneWhen.checks.all { it is TodoCheck.Attested } }
        return list.items.isNotEmpty() && attested * 3 > list.items.size
    }
}

/** The outcome of [TodoRules.complete]: done, or refused with the failure to tell the agent and the list carrying the proof of what was seen. */
sealed interface TodoCompletion {
    val list: TodoList
    data class Done(override val list: TodoList) : TodoCompletion
    data class Refused(override val list: TodoList, val failure: TeamFailure) : TodoCompletion
}
