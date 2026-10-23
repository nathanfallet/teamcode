package me.nathanfallet.teamcode.cli.terminal

/**
 * Agent-chosen text shown to whoever decides a request: control characters (escape sequences, carriage returns) and
 * invisible format characters (bidirectional overrides, zero-width marks, line separators) become spaces.
 */
internal fun String.printable(): String = buildString {
    var i = 0
    while (i < this@printable.length) {
        val c = this@printable[i]
        val low = this@printable.getOrNull(i + 1)
        if (c.isHighSurrogate() && low != null && low.isLowSurrogate()) {
            // Supplementary characters are checked by code point: the tag characters and the other invisible ones there.
            val point = 0x10000 + ((c.code - 0xD800) shl 10) + (low.code - 0xDC00)
            if (HIDDEN_SUPPLEMENTARY.any { point in it }) append("  ") else append(c).append(low)
            i += 2; continue
        }
        // A zero-width joiner only joins emoji, so it is kept; the blank-looking fillers are hidden like format characters.
        append(if (c != '\u200D' && (c.isISOControl() || c.category in HIDDEN || c.isSurrogate() || c in BLANK_FILLERS)) ' ' else c)
        i++
    }
}
/** Letters and symbols that render as nothing (Hangul fillers, the braille blank, Khmer inherent vowels). */
private val BLANK_FILLERS = setOf('\u115F', '\u1160', '\u3164', '\uFFA0', '\u2800', '\u17B4', '\u17B5')
/** The format characters outside the basic plane (Unicode 15). */
private val HIDDEN_SUPPLEMENTARY = listOf(0x110BD..0x110BD, 0x110CD..0x110CD, 0x13430..0x1343F, 0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0000..0xE007F, 0xE0100..0xE01EF)
/** Agent text quoted, so a label cannot pass for more options or indexes. */
internal fun String.quoted() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
private val HIDDEN = setOf(CharCategory.FORMAT, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR, CharCategory.SPACE_SEPARATOR)
