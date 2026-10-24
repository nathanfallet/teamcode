package me.nathanfallet.teamcode.cli.hooks

import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.api.models.EffectiveHookView
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import okio.FileSystem
import okio.Path

/**
 * What a member reads before approving a hook is what runs: every character that can hide, overwrite or reorder text
 * in a terminal (controls other than line feed and tab, carriage return, bidi overrides and isolates, zero-width and
 * other format characters, line separators, tag characters, spaces other than U+0020, characters that render blank) is
 * printed as `<U+XXXX>`. Letters and marks of every script stay readable, so this is a list of what hides, kept with
 * the tests that name each entry.
 */
internal object HookText {
    fun visible(text: String, keepLines: Boolean = true): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val pair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            val code = if (pair) 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) else c.code
            // A CRLF line ending stays a line ending; a lone carriage return could overwrite the line.
            val kept = keepLines && (c == '\n' || c == '\t' || (c == '\r' && i + 1 < text.length && text[i + 1] == '\n'))
            if (!kept && hidden(code, c, pair)) append("<U+").append(code.toString(16).uppercase().padStart(4, '0')).append('>')
            else { append(c); if (pair) append(text[i + 1]) }
            i += if (pair) 2 else 1
        }
    }

    private fun hidden(code: Int, c: Char, pair: Boolean): Boolean = when {
        // Common Kotlin has no category for code points above U+FFFF, so every supplementary format character
        // (Unicode 17) is listed: tags and variation selectors, Kaithi number signs, Egyptian hieroglyph format
        // controls, shorthand format controls, musical symbol formatting.
        pair -> code in 0xE0000..0xE0FFF || code == 0x110BD || code == 0x110CD || code in 0x13430..0x1343F ||
            code in 0x1BCA0..0x1BCA3 || code in 0x1D173..0x1D17A
        c.isSurrogate() -> true
        c.isISOControl() -> true
        code in 0x200B..0x200F || code in 0x2028..0x202E || code in 0x2060..0x206F -> true
        code == 0xFEFF || code == 0x00AD || code == 0x061C || code == 0x180E || code == 0x034F || code in 0xFFF9..0xFFFB -> true
        // Letters, marks and symbols that render blank: Hangul fillers, Khmer inherent vowels, the Braille blank, variation
        // selectors (Mongolian free ones included).
        code == 0x115F || code == 0x1160 || code == 0x3164 || code == 0xFFA0 || code == 0x2800 || code == 0x17B4 || code == 0x17B5 ||
            code in 0xFE00..0xFE0F || code in 0x180B..0x180F -> true
        // Every space but the ordinary one (no-break, ideographic, en, em... spaces look like it and are not it).
        c.category == CharCategory.SPACE_SEPARATOR -> c != ' '
        else -> c.category == CharCategory.FORMAT || c.category == CharCategory.LINE_SEPARATOR || c.category == CharCategory.PARAGRAPH_SEPARATOR
    }
}

/** The digest `teamcode hooks show` printed last for each hook: what `approve` records unless `--digest` names one. */
internal class HookShownDigests(private val file: Path, private val fileSystem: FileSystem = FileSystem.SYSTEM) {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(): Map<String, String> = try {
        if (!fileSystem.exists(file)) emptyMap() else json.decodeFromString<Map<String, String>>(fileSystem.read(file) { readUtf8() })
    } catch (_: Exception) { emptyMap() }
    fun record(name: String, digest: String) = writePrivateFile(file.toString(), json.encodeToString(load() + (name to digest)))
}

/**
 * Whether `approve` may record the delivered [hook]: it must be the version the member read, never one the server
 * swapped in since. It records the member's review step; a process running with the member's rights can approve hooks
 * anyway (it can write the approvals file), so this is no defense against local code.
 */
internal object HookApprovalCheck {
    private val digest = Regex("(sha256:)?[0-9a-f]{64}")
    fun problem(hook: EffectiveHookView, requested: String?, shown: String?): String? {
        val normalized = requested?.trim()?.lowercase()?.also {
            if (!digest.matches(it)) return "Pass --digest as sha256: followed by 64 hexadecimal digits, as teamcode hooks show prints it."
        }?.let { if (it.startsWith("sha256:")) it else "sha256:$it" }
        // The name and digests come from the server (and the shown-digests file): printed escaped, like show prints them.
        val name = HookText.visible(hook.name, keepLines = false)
        val delivered = HookText.visible(hook.digest, keepLines = false)
        val expected = normalized ?: shown ?: return "Read it first with teamcode hooks show $name, or pass --digest."
        if (expected == hook.digest) return null
        val read = HookText.visible(expected, keepLines = false)
        return if (normalized != null) "The server delivers $delivered, not the --digest you passed ($read). Read it with teamcode hooks show $name."
        else "The delivered version changed since you read it (you read $read, the server now delivers $delivered). Read it again with teamcode hooks show $name."
    }
}
