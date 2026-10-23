package me.nathanfallet.teamcode.cli.terminal

import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.interactiveMultiSelectList
import com.github.ajalt.mordant.input.interactiveSelectList
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.SelectList

/** One choice of [pickOne] or [pickMany]. Labels and descriptions are shown as plain text, control characters removed. */
internal data class PickerOption(val label: String, val description: String? = null)

/**
 * Asks the member to choose one of [options]: an arrow-key list (Mordant's select list, raw mode on every platform)
 * when input and output are a terminal, otherwise a numbered list answered by one line ("2"). Returns the chosen
 * index, or null when the member cancelled (Ctrl-C) or input ended.
 */
internal fun pickOne(title: String, options: List<PickerOption>, initial: Int = 0, terminal: Terminal = Terminal()): Int? {
    require(options.isNotEmpty()) { "Nothing to pick from." }
    val titles = titles(options)
    if (!terminal.terminalInfo.interactive) return numbered(terminal, title, options, multiple = false)?.single()
    val chosen = terminal.interactiveSelectList {
        entries(titles.mapIndexed { i, it -> SelectList.Entry(it, options[i].description?.printable()) })
        title(title.printable())
        startingCursorIndex(initial.coerceIn(options.indices))
    } ?: return null
    return titles.indexOf(chosen).takeIf { it >= 0 }
}

/**
 * Asks the member to choose any of [options] ([initial] chosen at first): space toggles, enter confirms. Without a
 * terminal, a numbered list answered by one line ("0,2"; an empty line keeps [initial]). Returns the chosen indexes, or
 * null when the member cancelled or input ended.
 */
internal fun pickMany(title: String, options: List<PickerOption>, initial: Set<Int> = emptySet(), terminal: Terminal = Terminal()): Set<Int>? {
    require(options.isNotEmpty()) { "Nothing to pick from." }
    val titles = titles(options)
    if (!terminal.terminalInfo.interactive) return numbered(terminal, title, options, multiple = true, initial)
    val chosen = terminal.interactiveMultiSelectList {
        entries(titles.mapIndexed { i, it -> SelectList.Entry(it, options[i].description?.printable(), selected = i in initial) })
        title(title.printable())
        keyToggle(KeyboardEvent(" "))
        descToggle("toggle (space)")
    } ?: return null
    return chosen.map(titles::indexOf).filter { it >= 0 }.toSet()
}

/** Printable titles, made unique (Mordant answers with the title): a repeated label gets its position. */
private fun titles(options: List<PickerOption>): List<String> {
    val labels = options.map { it.label.printable() }
    return labels.mapIndexed { i, label -> if (labels.count { it == label } > 1) "$label (${i + 1})" else label }
}

private fun numbered(terminal: Terminal, title: String, options: List<PickerOption>, multiple: Boolean, initial: Set<Int> = emptySet()): Set<Int>? {
    terminal.println(title.printable())
    options.forEachIndexed { i, option -> terminal.println("  $i: ${option.label.printable()}" + (option.description?.let { " - ${it.printable()}" } ?: "")) }
    while (true) {
        terminal.print(if (multiple) "Choose any, separated by commas: " else "Choose one: ")
        val line = terminal.readLineOrNull(hideInput = false)?.trim() ?: return null
        if (multiple && line.isEmpty()) return initial
        val indexes = line.split(',', ' ').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: -1 }
        when {
            indexes.isEmpty() || indexes.any { it !in options.indices } -> terminal.println("Type ${if (multiple) "indexes" else "an index"} from 0 to ${options.lastIndex}.")
            !multiple && indexes.size > 1 -> terminal.println("Choose one.")
            else -> return indexes.toSet()
        }
    }
}
