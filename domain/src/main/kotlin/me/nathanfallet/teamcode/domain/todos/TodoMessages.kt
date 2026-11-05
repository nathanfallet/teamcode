package me.nathanfallet.teamcode.domain.todos

/**
 * The messages TeamCode writes to an agent about its todo (docs/todos.md, "The resume message"). Facts and one instruction, in
 * English like every instruction of TeamCode; the reference and the items are copied as the agent wrote them. The reference comes
 * first, so the agent sees a point of the plan that its list is missing, and the list is whole, with only the next item in full.
 */
object TodoMessages {
    const val MAX_CHARS = 8_000

    /** Formats an instant (epoch milliseconds) as `HH:MM` in UTC. */
    private fun clock(at: Long): String {
        val minutes = (at / 60_000) % (24 * 60)
        return "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"
    }

    private fun oneLine(text: String, max: Int) = text.filter { !it.isISOControl() || it == '\n' }.replace('\n', ' ').trim().let { if (it.length > max) it.take(max - 1) + "…" else it }

    private fun mark(item: TodoItem, next: TodoItem?) = when {
        item.status == TodoItemStatus.DONE -> "✓"
        item.status == TodoItemStatus.DROPPED -> "✗"
        item.status == TodoItemStatus.DROP_REQUESTED -> "?"
        item.id == next?.id -> "→"
        else -> "·"
    }

    private fun detail(item: TodoItem): String {
        val link = item.links.firstOrNull { it.kind == "pr" }
        return when {
            item.status == TodoItemStatus.DONE && link?.number != null -> "PR #${link.number} merged"
            item.status == TodoItemStatus.DONE -> item.links.firstOrNull()?.let { "${it.kind} ${it.ref.takeLast(40)}" } ?: ""
            item.status == TodoItemStatus.DROPPED -> "dropped by the owner"
            item.status == TodoItemStatus.DROP_REQUESTED -> "drop requested, waiting for the owner"
            item.doneWhen.checks.all { it is TodoCheck.Attested } -> "(attested)"
            item.origin == TodoOrigin.DISCOVERED && item.originReason != null && item.id.startsWith("finish-pr-") -> "(opened in this session, not covered by an item)"
            else -> ""
        }
    }

    /** The message that sends the agent back to work: `block` reason of the Stop hook, and the server-side reminder. */
    fun resume(list: TodoList): String {
        val next = TodoRules.next(list) ?: list.items.firstOrNull { it.status == TodoItemStatus.DOING }
        val head = "[TeamCode todo] ${list.doneCount} of ${list.items.size} items done. Items remain and no wait is open, so the turn goes on."
        val reference = list.reference.trim().lines().joinToString("\n") { "  $it" }
        val items = list.items.joinToString("\n") { item ->
            val detail = detail(item)
            "  ${mark(item, next)} ${item.id.padEnd(22)} ${oneLine(item.name, 60)}" + if (detail.isEmpty()) "" else "   $detail"
        }
        val nextBlock = if (next == null) "No item is ready: an item waits for another one, or for the owner. Call todo_show." else buildString {
            appendLine("Next item: ${next.id} — ${oneLine(next.name, 80)}")
            next.description.trim().lines().forEach { appendLine("  $it") }
            appendLine("  Done when: ${next.doneWhen.text.trim().lines().joinToString("\n  ")}")
            next.proof?.let { appendLine("  Last check (${clock(it.at)}): ${oneLine(it.text, 400)}") }
        }.trimEnd()
        val tail = "Do the next item now. If a person must decide or act, call ask_human; to wait\nfor a CI or a delay, call wait_for; if an item no longer makes sense, call\ntodo_drop. Each of these ends the turn properly."
        val parts = listOf(head, "Plan reference:\n$reference", nextBlock, "Items:\n$items", tail)
        val text = parts.joinToString("\n")
        if (text.length <= MAX_CHARS) return text
        // Too long: the item list is what gives way (the reference and the next item are what the agent needs).
        val short = list.items.joinToString("\n") { "  ${mark(it, next)} ${it.id}" }
        return listOf(head, "Plan reference:\n$reference", nextBlock, "Items:\n$short", tail).joinToString("\n").take(MAX_CHARS)
    }

    /** The title and body of the wait the hook opens for the owner on the third stop without progress. */
    fun blockedTitle(list: TodoList): String = "Blocked: ${TodoRules.next(list)?.name ?: "the todo"}".take(120)

    fun blockedBody(list: TodoList, lastMessage: String?): String = buildString {
        appendLine("The agent stopped three times in a row without finishing an item, adding one or writing a note, and no wait is open.")
        appendLine()
        appendLine("${list.doneCount} of ${list.items.size} items done:")
        list.items.forEach { appendLine("- ${mark(it, TodoRules.next(list))} `${it.id}` ${oneLine(it.name, 80)}") }
        if (!lastMessage.isNullOrBlank()) { appendLine(); appendLine("Its last message:"); appendLine("> " + oneLine(lastMessage, 600)) }
    }.take(4_000)
}
