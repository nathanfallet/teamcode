package me.nathanfallet.teamcode.cli.terminal


internal fun String.dropLastCharacter() = if (length >= 2 && this[length - 1].isLowSurrogate() && this[length - 2].isHighSurrogate()) dropLast(2) else dropLast(1)

/** Cuts [line] to [width] columns (one per character), so a live area never wraps. */
internal fun fit(line: String, width: Int): String {
    val room = (width - 1).coerceAtLeast(20)
    return if (line.length > room) line.take(room - 1) + "…" else line
}

/** One line of typed text, for messages and commands. */
internal class LineEditor {
    var text = ""
        private set

    /** The line when [key] was Enter on a non-empty line, otherwise null. */
    fun key(key: Key): String? {
        when (key) {
            is Key.Text -> text += key.text
            Key.Backspace -> text = text.dropLastCharacter()
            Key.ClearLine -> text = ""
            Key.Enter -> if (text.isNotBlank()) return text.also { text = "" }
            else -> Unit
        }
        return null
    }

    /** The line as shown: its end when it is longer than [width]. */
    fun line(width: Int): String {
        val room = (width - 3).coerceAtLeast(10)
        val shown = text.printable()
        return "> " + if (shown.length > room) "…" + shown.takeLast(room - 1) else shown
    }
}

/**
 * The bottom of a terminal: a live area (a picker, a line being typed) redrawn in place under what is printed. Every
 * write goes through it, so a line printed while a member types never breaks theirs. Area lines must not wrap ([fit]).
 */
internal class LiveArea(private val write: (String) -> Unit) {
    private var drawn = 0

    private fun clear() {
        if (drawn == 0) return
        write("\r" + (if (drawn > 1) "\u001B[${drawn - 1}A" else "") + "\u001B[J")
        drawn = 0
    }

    /** Prints [text] above the live area, then draws [area]. */
    fun print(text: String, area: List<String>) { clear(); write(text + "\n"); show(area) }

    /** Replaces the live area; the cursor stays at the end of its last line. */
    fun show(area: List<String>) { clear(); if (area.isNotEmpty()) write(area.joinToString("\n")); drawn = area.size }
}
