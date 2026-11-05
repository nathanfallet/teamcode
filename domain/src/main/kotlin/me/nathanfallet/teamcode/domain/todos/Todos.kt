package me.nathanfallet.teamcode.domain.todos

import me.nathanfallet.teamcode.domain.waits.WaitRef

enum class TodoListStatus(val wire: String) {
    ACTIVE("active"), DONE("done"), ABANDONED("abandoned");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}
enum class TodoItemStatus(val wire: String) {
    TODO("todo"), DOING("doing"), DONE("done"), DROP_REQUESTED("drop_requested"), DROPPED("dropped");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}
enum class TodoOrigin(val wire: String) {
    PLAN("plan"), DISCOVERED("discovered");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}

/**
 * A typed check of an item's end condition (docs/todos.md). A check never names a number written in advance: a PR, a release or
 * an artifact is found and linked to the item when it exists, and `${pr:<item>.number}` style values in [Command.run] cite it.
 */
sealed interface TodoCheck {
    val type: String

    data class PrMerged(val repo: String) : TodoCheck { override val type get() = "pr_merged" }
    data class PrClosed(val repo: String, val reason: String) : TodoCheck { override val type get() = "pr_closed" }
    /** [of]: the id of the item whose PR must be shipped; [commit] instead names a commit. */
    data class ReleaseContains(val repo: String, val of: String? = null, val commit: String? = null) : TodoCheck { override val type get() = "release_contains" }
    data class CiGreen(val repo: String, val of: String? = null, val ref: String? = null, val workflow: String? = null) : TodoCheck { override val type get() = "ci_green" }
    data class Command(val run: String, val cwd: String? = null, val expectExit: Int = 0, val timeoutSeconds: Int = TodoRules.DEFAULT_COMMAND_SECONDS) : TodoCheck { override val type get() = "command" }
    /** [id] null: the artifact is linked later, like a PR. */
    data class ArtifactPublished(val id: String? = null, val minVersion: Int? = null) : TodoCheck { override val type get() = "artifact_published" }
    data class WaitAnswered(val action: String? = null) : TodoCheck { override val type get() = "wait_answered" }
    /** The agent supplies a proof; nobody verifies it. Shown as "unverified". */
    data class Attested(val what: String) : TodoCheck { override val type get() = "attested" }
}

data class TodoDoneWhen(val text: String, val checks: List<TodoCheck>)

/** What the agent writes for an item (todo_create, todo_add); [dependsOn] names items of the same list. */
data class TodoItemDefinition(
    val id: String, val name: String, val description: String, val doneWhen: TodoDoneWhen, val dependsOn: List<String> = emptyList(),
)

/**
 * What an item is bound to once it exists (docs/todos.md, "The link"): a `pr` or a `release` ([ref] is its https address), an
 * `artifact` or a `wait` ([ref] is its id). A link is frozen: see [TodoRules.link]. [number], [sha] (a PR's merge commit) and [tag]
 * (a release's) are what `${pr:<item>.number}` style values in a command check cite.
 */
data class TodoLink(val kind: String, val ref: String, val number: Int? = null, val sha: String? = null, val tag: String? = null, val at: Long = 0) {
    companion object { val kinds = listOf("pr", "release", "artifact", "wait") }
}

data class TodoNote(val at: Long, val text: String)

/** What the last verification observed: [passed] null is "unknown" (it never counts as done). */
data class TodoProof(val at: Long, val passed: Boolean?, val text: String, val urls: List<String> = emptyList())

data class TodoItem(
    val id: String, val name: String, val description: String, val doneWhen: TodoDoneWhen, val dependsOn: List<String>,
    val origin: TodoOrigin, val originReason: String?, val status: TodoItemStatus, val proof: TodoProof?, val notes: List<TodoNote>,
    val dropReason: String? = null,
    /** The wait asked of the session's owner while the item is in `drop_requested`. */
    val dropWaitId: String? = null,
    val links: List<TodoLink> = emptyList(),
)

/** A counter snapshot taken at a stop: a stop made progress when an item was finished or added, or a note written, since. */
data class TodoProgress(val done: Int, val items: Int, val notes: Int)

/**
 * What the Stop hook recorded about itself: `would_block` (observation mode: the stop it would have refused, with the reason),
 * `blocked` (the third stop without progress opened a wait) or `hook_error` (it failed open). The newest [TodoRules.MAX_HOOK_EVENTS] are kept.
 */
data class TodoHookEvent(val at: Long, val kind: String, val text: String) {
    companion object { val kinds = listOf("would_block", "blocked", "hook_error") }
}

data class TodoList(
    val id: String, val sessionId: String, val teamId: String, val reference: String, val refs: List<WaitRef>,
    val status: TodoListStatus, val stall: Int, val lastStop: TodoProgress?, val items: List<TodoItem>,
    val createdAt: Long, val updatedAt: Long, val hookEvents: List<TodoHookEvent> = emptyList(),
    /** When the Stop hook last asked about this session: a turn it was asked in was already judged, so the server does not remind again. */
    val lastHookAt: Long? = null,
    /** The sequence of the last turn end the server-side reminder judged (`cloud_session_events.seq`), so a turn is reminded once across replicas and restarts. */
    val reminderSeq: Long? = null,
) {
    fun item(id: String) = items.firstOrNull { it.id == id }
    val doneCount get() = items.count { it.status == TodoItemStatus.DONE }
    val progress get() = TodoProgress(doneCount, items.size, items.sumOf { it.notes.size })
    /** Items settled for good: done, or dropped by the owner. */
    val allClosed get() = items.isNotEmpty() && items.all { it.status == TodoItemStatus.DONE || it.status == TodoItemStatus.DROPPED }
}

/** One check's outcome, as the process that ran it observed it. [passed] null: unknown (timeout, unreachable, not linked yet). */
data class TodoCheckResult(val check: TodoCheck, val passed: Boolean?, val observed: String, val urls: List<String> = emptyList())

/** What a stop leads to: the updated list, and whether the counter reached the limit (the hook then opens a wait). */
data class TodoStop(val list: TodoList, val progressed: Boolean, val escalate: Boolean)
