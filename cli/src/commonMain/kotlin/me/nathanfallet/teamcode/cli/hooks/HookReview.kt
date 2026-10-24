package me.nathanfallet.teamcode.cli.hooks

import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.api.models.EffectiveHookView
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import okio.FileSystem
import okio.Path

/**
 * What a member reads before approving a hook is what runs: every character that can hide, overwrite or reorder text
 * in a terminal (controls other than line feed and tab, carriage return, bidi overrides and isolates, zero-width and
 * other format characters, line separators, tag characters) is printed as `<U+XXXX>`.
 */
internal object HookText {
    fun visible(text: String, keepLines: Boolean = true): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val pair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            val code = if (pair) 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) else c.code
            val kept = keepLines && (c == '\n' || c == '\t')
            if (!kept && hidden(code, c, pair)) append("<U+").append(code.toString(16).uppercase().padStart(4, '0')).append('>')
            else { append(c); if (pair) append(text[i + 1]) }
            i += if (pair) 2 else 1
        }
    }

    private fun hidden(code: Int, c: Char, pair: Boolean): Boolean = when {
        pair -> code in 0xE0000..0xE0FFF || code in 0x1BCA0..0x1BCA3 || code in 0x1D173..0x1D17A
        c.isSurrogate() -> true
        c.isISOControl() -> true
        code in 0x200B..0x200F || code in 0x2028..0x202E || code in 0x2060..0x206F -> true
        code == 0xFEFF || code == 0x00AD || code == 0x061C || code == 0x180E || code == 0x034F || code in 0xFFF9..0xFFFB -> true
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

/** Whether `approve` may record the delivered [hook]: it must be the version the member read, never one swapped in since. */
internal object HookApprovalCheck {
    fun problem(hook: EffectiveHookView, requested: String?, shown: String?): String? {
        val expected = requested ?: shown ?: return "Read it first with teamcode hooks show ${hook.name}, or pass --digest."
        if (expected != hook.digest) return "The delivered version changed since you read it (you read $expected, the server now delivers ${hook.digest}). " +
            "Read it again with teamcode hooks show ${hook.name}."
        return null
    }
}
