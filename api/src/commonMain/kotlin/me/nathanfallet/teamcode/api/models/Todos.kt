package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** Wire names of a session's todo (`todos.v1`, docs/todos.md). A client must tolerate an unknown value from a newer server: show it as text. */
object Todos {
    const val CAPABILITY = "todos.v1"
    /** The caller-wide feed carries `cloud.todo` (the list changed: `sessionId`, `status`, `done`, `total`), so a screen follows a list live. */
    const val LIVE_CAPABILITY = "todos.live.v1"
    val checkTypes = listOf("pr_merged", "pr_closed", "release_contains", "ci_green", "command", "artifact_published", "wait_answered", "attested")
    val itemStatuses = listOf("todo", "doing", "done", "drop_requested", "dropped")
    val listStatuses = listOf("active", "done", "abandoned")
    val linkKinds = listOf("pr", "release", "artifact", "wait")
}

/**
 * One typed check of an end condition. Only the fields of its [type] are read: `pr_merged` (repo), `pr_closed` (repo, reason),
 * `release_contains` (repo, of or commit), `ci_green` (repo, of or ref, workflow), `command` (run, cwd, expectExit, timeoutSeconds),
 * `artifact_published` (id, minVersion), `wait_answered` (action), `attested` (what). No check names a PR number or a version.
 */
@Serializable
data class TodoCheckInput(
    val type: String,
    val repo: String? = null,
    val of: String? = null,
    val commit: String? = null,
    val ref: String? = null,
    val workflow: String? = null,
    val reason: String? = null,
    val run: String? = null,
    val cwd: String? = null,
    val expectExit: Int = 0,
    val timeoutSeconds: Int = 60,
    val id: String? = null,
    val minVersion: Int? = null,
    val action: String? = null,
    val what: String? = null,
)

@Serializable
data class TodoDoneWhenInput(val text: String, val checks: List<TodoCheckInput>)

@Serializable
data class TodoItemInput(val id: String, val name: String, val description: String, val doneWhen: TodoDoneWhenInput, val dependsOn: List<String> = emptyList())

/** A reference of the plan, like a wait's: `artifact` by id, `session` by id, or `url` (https). */
@Serializable
data class TodoRefInput(val kind: String, val id: String? = null, val url: String? = null, val title: String? = null)

/** Creates the session's todo. [sessionId] is named by the session's owner; an execution credential is confined to its own session and names none. */
@Serializable
data class CreateTodoRequest(val sessionId: String? = null, val reference: String, val refs: List<TodoRefInput> = emptyList(), val items: List<TodoItemInput>)

@Serializable
data class AddTodoItemRequest(val sessionId: String? = null, val item: TodoItemInput, val reason: String)

@Serializable
data class StartTodoItemRequest(val sessionId: String? = null, val background: Boolean = false, val agent: String? = null)

@Serializable
data class NoteTodoItemRequest(val sessionId: String? = null, val text: String)

@Serializable
data class DropTodoItemRequest(val sessionId: String? = null, val reason: String)

/**
 * Binds a PR, a release (their https address in [ref]), an artifact or a wait (their id) to an item. A link is frozen: the same [ref]
 * may be completed with [number] (a PR's), [sha] (its merge commit) or [tag] (a release's); another of the same kind is refused
 * (`already_linked`), except to replace a PR closed without merge ([replaceClosed]).
 */
@Serializable
data class LinkTodoItemRequest(val sessionId: String? = null, val kind: String, val ref: String, val number: Int? = null, val sha: String? = null, val tag: String? = null, val replaceClosed: Boolean = false)

@Serializable
data class TodoLinkView(val kind: String, val ref: String, val number: Int? = null, val sha: String? = null, val tag: String? = null, val at: Long = 0)

/** What the check at [index] of the item's stored condition observed; [passed] null is "unknown". */
@Serializable
data class TodoCheckReportInput(val index: Int, val passed: Boolean? = null, val observed: String, val urls: List<String> = emptyList())

@Serializable
data class CompleteTodoItemRequest(val sessionId: String? = null, val checks: List<TodoCheckReportInput>)

@Serializable
data class TodoCheckView(
    val type: String,
    val repo: String? = null,
    val of: String? = null,
    val commit: String? = null,
    val ref: String? = null,
    val workflow: String? = null,
    val reason: String? = null,
    val run: String? = null,
    val cwd: String? = null,
    val expectExit: Int? = null,
    val timeoutSeconds: Int? = null,
    val id: String? = null,
    val minVersion: Int? = null,
    val action: String? = null,
    val what: String? = null,
)

@Serializable
data class TodoDoneWhenView(val text: String, val checks: List<TodoCheckView>)

@Serializable
data class TodoProofView(val at: Long, val passed: Boolean? = null, val text: String, val urls: List<String> = emptyList())

@Serializable
data class TodoNoteView(val at: Long, val text: String)

@Serializable
data class TodoItemView(
    val id: String,
    val name: String,
    val description: String,
    val doneWhen: TodoDoneWhenView,
    val dependsOn: List<String> = emptyList(),
    val origin: String,
    val originReason: String? = null,
    val status: String,
    val proof: TodoProofView? = null,
    val notes: List<TodoNoteView> = emptyList(),
    val dropReason: String? = null,
    val dropWaitId: String? = null,
    val links: List<TodoLinkView> = emptyList(),
    /** `true` when every check of the condition is `attested`: nothing verifies it. */
    val unverified: Boolean = false,
)

@Serializable
data class TodoView(
    val id: String,
    val sessionId: String,
    val reference: String,
    val refs: List<TodoRefInput> = emptyList(),
    val status: String,
    val stall: Int = 0,
    val items: List<TodoItemView>,
    val createdAt: Long,
    val updatedAt: Long,
    /** The id of the first `todo` item whose dependencies are done, or null. */
    val nextItemId: String? = null,
    /** What the Stop hook recorded about itself, oldest first (`blocked`, `hook_error`). */
    val hookEvents: List<TodoHookEventView> = emptyList(),
)

@Serializable
data class TodoHookEventView(val at: Long, val kind: String, val text: String)

/** The outcome of the checks the Stop hook ran for one item, by position in the item's stored checks. */
@Serializable
data class TodoStopItemInput(val itemId: String, val checks: List<TodoCheckReportInput> = emptyList())

/**
 * One stop of the agent, asked to the server by `teamcode cloud todo-stop`. [phase] is `begin`, `verify` (with [items], the cheap
 * checks, and [orphans], the pull requests the session opened) or `settle` (every check of the done items). [background]: a
 * background agent of the session still runs.
 */
@Serializable
data class TodoStopRequest(
    val sessionId: String? = null, val phase: String = "begin", val background: Boolean = false,
    val items: List<TodoStopItemInput> = emptyList(), val orphans: List<String> = emptyList(), val lastMessage: String? = null,
)

/**
 * What the hook does: `allow` (the stop passes; [why] says why), `verify` (run the cheap checks of the items in [todo] and ask again with
 * `verify`), `settle` (run every check of the done items and ask again with `settle`) or `block` ([reason] is the message to give the agent).
 */
@Serializable
data class TodoStopView(val action: String, val todo: TodoView? = null, val reason: String? = null, val why: String? = null, val escalated: Boolean = false)

/** One look of the Stop hook of a local run at the waits of its session: how long the server may hold the answer back, at most 30 000 ms. */
@Serializable
data class TodoWakeRequest(val sessionId: String? = null, val waitMillis: Long = 25_000)

/** What came back: [open] says a wait of the session is still open (hold the agent), [messages] the answers that arrived, oldest first. */
@Serializable
data class TodoWakeView(val open: Boolean, val messages: List<String> = emptyList())

/** The block a session is reminded of after a compaction or on resume: [text] is null while the session has no active list. */
@Serializable
data class TodoContextView(val text: String? = null)

/** A failure of the hook, recorded on the list so a broken hook shows. */
@Serializable
data class TodoHookErrorRequest(val sessionId: String? = null, val text: String)

/** The answer of a read: [todo] is null while the session has no list. */
@Serializable
data class TodoSessionView(val todo: TodoView? = null)

/** The answer of a start: the list, and the [marker] to put in the body of the item's pull request so it is found whatever its number. */
@Serializable
data class StartTodoItemView(val todo: TodoView, val marker: String)
