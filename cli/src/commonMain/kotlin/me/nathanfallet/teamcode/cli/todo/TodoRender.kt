package me.nathanfallet.teamcode.cli.todo

import me.nathanfallet.teamcode.api.models.*

/** The todo as text, for the agent: the reference first, then the items and the next one. */
internal object TodoRender {
    private fun mark(item: TodoItemView, next: String?) = when {
        item.status == "done" -> "✓"
        item.status == "dropped" -> "x"
        item.status == "drop_requested" -> "?"
        item.id == next || item.status == "doing" -> "→"
        else -> "·"
    }

    fun show(todo: TodoView): String = buildString {
        appendLine("[TeamCode todo] ${todo.items.count { it.status == "done" }} of ${todo.items.size} items done (${todo.status}).")
        appendLine("Plan reference:")
        todo.reference.lines().forEach { appendLine("  $it") }
        val next = todo.items.firstOrNull { it.id == todo.nextItemId }
        if (next != null) {
            appendLine("Next item: ${next.id} — ${next.name}")
            next.description.lines().forEach { appendLine("  $it") }
            appendLine("  Done when: ${next.doneWhen.text}")
            next.proof?.let { appendLine("  Last check: ${it.text}") }
        }
        appendLine("Items:")
        todo.items.forEach { item ->
            val detail = when {
                item.status == "done" -> item.proof?.text.orEmpty()
                item.status == "drop_requested" -> "waiting for the owner to agree to drop it"
                item.dependsOn.isNotEmpty() && item.status == "todo" && item.id != todo.nextItemId -> "(after ${item.dependsOn.joinToString()})"
                else -> ""
            }
            appendLine("  ${mark(item, todo.nextItemId)} ${item.id.padEnd(22)} ${item.name}" + (if (item.origin == "discovered") " [added on the way]" else "") +
                (if (item.unverified) " (attested)" else "") + (if (detail.isNotEmpty()) "  $detail" else ""))
        }
    }.trimEnd()
}
