package me.nathanfallet.teamcode.companion.domain.models

/** The state of one todo item; a value a newer server adds reads as [UNKNOWN] and shows as to do. */
enum class TodoItemState(val wire: String) {
    TODO("todo"), DOING("doing"), DONE("done"), DROP_REQUESTED("drop_requested"), DROPPED("dropped"), UNKNOWN("");

    companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN }
}

/** The last proof reported for an item, [passed] null for an attestation nobody verified. */
data class TodoProof(val at: Long, val passed: Boolean?, val text: String, val urls: List<String>)

data class TodoItem(
    val id: String,
    val name: String,
    val description: String,
    /** The end condition, as the agent wrote it. */
    val doneWhen: String,
    val state: TodoItemState,
    /** Added by the agent after the list was created ("added along the way"). */
    val addedLater: Boolean,
    val addedReason: String?,
    val proof: TodoProof?,
    /** Done on the agent's word alone (an `attested` check): shown as "not verified". */
    val unverified: Boolean,
    val dropReason: String?,
)

/** A problem the Stop hook met (the hook failed open); newest last. */
data class TodoHookEvent(val at: Long, val kind: String, val text: String)

/** A session's todo (`todos.v1`): the plan's reference, then the items in order. */
data class SessionTodo(
    val sessionId: String,
    val reference: String,
    /** The artifact the reference points to, when the list names one. */
    val artifactId: String?,
    /** `active`, `done` or `abandoned`. */
    val status: String,
    val items: List<TodoItem>,
    val updatedAt: Long,
    val hookEvents: List<TodoHookEvent> = emptyList(),
) {
    /** Dropped items are not counted: the counter reads "4 / 9" of what is still to do. */
    val counted: List<TodoItem> get() = items.filter { it.state != TodoItemState.DROPPED }
    val done: Int get() = counted.count { it.state == TodoItemState.DONE }
    val total: Int get() = counted.size
    val current: TodoItem? get() = items.firstOrNull { it.state == TodoItemState.DOING }
    /** More than a third of the items rest on the agent's word alone: the list is flagged. */
    val mostlyAttested: Boolean get() = total >= 3 && counted.count { it.unverified } * 3 > total
}

/** What the session's screen holds of its todo. */
data class TodoCounter(val done: Int, val total: Int)
