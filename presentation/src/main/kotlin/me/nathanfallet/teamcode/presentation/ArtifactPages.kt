package me.nathanfallet.teamcode.presentation

/**
 * How an artifact's entry page is served. HTML gets the skeleton agents expect (doctype, charset, viewport, a small
 * reset) when it has no doctype of its own; Markdown is rendered on the server into escaped HTML, so nothing in it
 * runs as markup. Other files are served as published.
 */
internal object ArtifactPages {
    private const val RESET = "*,*::before,*::after{box-sizing:border-box}body{margin:0}"
    private const val SKELETON = """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>$RESET</style>"""
    private const val BARE_SKELETON = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>$RESET</style>"""

    /**
     * An HTML entry page as sent, and whether it is UTF-8. A page with its own doctype is sent byte for byte; one
     * without gets the skeleton in front, which names UTF-8 only when the page is UTF-8, so a page in another
     * encoding keeps its own `<meta charset>`.
     */
    fun html(source: ByteArray): Pair<ByteArray, Boolean> {
        val utf8 = isUtf8(source)
        val head = source.copyOfRange(0, minOf(source.size, 64)).decodeToString().trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (head.regionMatches(0, "<!doctype", 0, 9, ignoreCase = true)) return source to utf8
        return ((if (utf8) SKELETON else BARE_SKELETON).encodeToByteArray() + source) to utf8
    }

    private fun isUtf8(bytes: ByteArray) = try {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)); true
    } catch (_: java.nio.charset.CharacterCodingException) { false }

    private const val MARKDOWN_STYLE = "body{max-width:760px;margin:0 auto;padding:32px 20px;font:16px/1.6 -apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Helvetica,Arial,sans-serif;color:#1f2328}" +
        "pre{background:#f6f8fa;padding:12px 14px;border-radius:8px;overflow:auto}code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:.92em}" +
        ":not(pre)>code{background:#f6f8fa;padding:1px 5px;border-radius:5px}blockquote{margin:0;padding:0 14px;border-left:3px solid #d0d7de;color:#57606a}" +
        "h1,h2,h3{line-height:1.25}hr{border:0;border-top:1px solid #d0d7de}" +
        "@media (prefers-color-scheme:dark){body{background:#0d1117;color:#e6edf3}pre,:not(pre)>code{background:#161b22}blockquote{color:#8b949e;border-color:#30363d}a{color:#58a6ff}}"

    /** A Markdown page as a whole HTML document; [title] is the file's name (the content route knows only the file). */
    fun markdown(source: String, title: String): String =
        """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(title)}</title><style>$RESET$MARKDOWN_STYLE</style></head><body>""" +
            Markdown.render(source) + "</body></html>"
}

/**
 * A small CommonMark subset, enough for agent reports: ATX headings, paragraphs, fenced and indented code, block quotes,
 * bullet and numbered lists, rules, and inline code, emphasis, strong text and links. Every character of the source is
 * escaped first; only the markup produced here is HTML, and links keep `http`, `https`, `mailto` and relative targets.
 */
internal object Markdown {
    fun render(source: String): String = render(source.replace("\u0000", "\uFFFD"), 0)

    /** [depth] bounds nested block quotes, so a line of `>` characters cannot exhaust the stack. */
    private fun render(source: String, depth: Int): String {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder()
        var i = 0
        val paragraph = mutableListOf<String>()
        fun flush() { if (paragraph.isNotEmpty()) { out.append("<p>").append(inline(paragraph.joinToString("\n"))).append("</p>\n"); paragraph.clear() } }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val fence = FENCE.find(trimmed)
            when {
                fence != null -> {
                    flush()
                    val marker = fence.value
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(marker)) { code += lines[i]; i++ }
                    out.append("<pre><code>").append(escape(code.joinToString("\n"))).append("</code></pre>\n")
                }
                trimmed.isEmpty() -> flush()
                HEADING.matches(trimmed) -> {
                    flush()
                    val level = trimmed.takeWhile { it == '#' }.length
                    out.append("<h$level>").append(inline(CLOSING_HASHES.replace(trimmed.drop(level).trim(), "").trim())).append("</h$level>\n")
                }
                RULE.matches(trimmed) && paragraph.isEmpty() -> { out.append("<hr>\n") }
                trimmed.startsWith(">") && depth < MAX_QUOTE_DEPTH -> {
                    flush()
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { quoted += lines[i].trim().removePrefix(">").removePrefix(" "); i++ }
                    out.append("<blockquote>").append(render(quoted.joinToString("\n"), depth + 1)).append("</blockquote>\n")
                    continue
                }
                LIST_ITEM.matches(line) -> {
                    flush()
                    val ordered = line.trim().first().isDigit()
                    out.append(if (ordered) "<ol>\n" else "<ul>\n")
                    while (i < lines.size && LIST_ITEM.matches(lines[i]) && lines[i].trim().first().isDigit() == ordered) {
                        val text = LIST_ITEM.matchEntire(lines[i])!!.groupValues[2]
                        out.append("<li>").append(inline(text)).append("</li>\n")
                        i++
                    }
                    out.append(if (ordered) "</ol>\n" else "</ul>\n")
                    continue
                }
                line.startsWith("    ") && paragraph.isEmpty() -> {
                    val code = mutableListOf<String>()
                    while (i < lines.size && (lines[i].startsWith("    ") || lines[i].isBlank())) { code += lines[i].removePrefix("    "); i++ }
                    out.append("<pre><code>").append(escape(code.joinToString("\n").trimEnd())).append("</code></pre>\n")
                    continue
                }
                else -> paragraph += trimmed
            }
            i++
        }
        flush()
        return out.toString()
    }

    private const val MAX_QUOTE_DEPTH = 8
    private val FENCE = Regex("^(```+|~~~+)")
    private val HEADING = Regex("^#{1,6}(\\s.*)?$")
    private val RULE = Regex("^(\\*\\s*){3,}$|^(-\\s*){3,}$|^(_\\s*){3,}$")
    /** A closing sequence of `#` is only one preceded by a space (`# Learning C#` keeps its `#`). */
    private val CLOSING_HASHES = Regex("(^|\\s+)#+\\s*$")
    private val PLACEHOLDER = Regex("\u0000(\\d+)\u0000")
    private val LIST_ITEM = Regex("^\\s{0,3}([-*+]|\\d{1,9}[.)])\\s+(.*)$")
    private val CODE = Regex("`([^`]+)`")
    // Bounded, so a line of `[` without `]` is scanned in linear time.
    private val LINK = Regex("\\[([^\\]\\[]{1,500})]\\(([^)\\s]{1,2048})\\)")
    private val STRONG = Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__")
    private val EMPHASIS = Regex("(?<![*\\w])\\*([^*\\s][^*]*)\\*(?!\\*)|(?<![_\\w])_([^_\\s][^_]*)_(?![_\\w])")

    /**
     * Inline markup over escaped text. Code spans are set aside first so nothing inside them is interpreted, and link
     * targets are set aside before emphasis, so `__init__.py` in a URL stays as written. The source holds no NUL
     * ([render] replaced them), so a placeholder is always one of ours.
     */
    fun inline(text: String): String {
        val spans = mutableListOf<String>()
        fun hold(html: String): String { spans += html; return "\u0000${spans.size - 1}\u0000" }
        var value = CODE.replace(text.replace("\u0000", "\uFFFD")) { hold("<code>${escape(it.groupValues[1])}</code>") }
        value = escape(value)
        value = LINK.replace(value) { match ->
            val target = match.groupValues[2]
            val decoded = target.replace("&amp;", "&")
            val safe = SAFE_TARGET.matches(decoded) && !decoded.trimStart().lowercase().let { it.startsWith("javascript:") || it.startsWith("data:") || it.startsWith("vbscript:") }
            if (safe) hold("""<a href="$target" target="_blank" rel="noopener noreferrer">""") + match.groupValues[1] + hold("</a>") else match.groupValues[1]
        }
        value = STRONG.replace(value) { "<strong>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</strong>" }
        value = EMPHASIS.replace(value) { "<em>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</em>" }
        value = PLACEHOLDER.replace(value) { m -> spans.getOrNull(m.groupValues[1].toInt()) ?: "" }
        return value
    }

    /** `http(s)`, `mailto`, an anchor or a relative path: never another scheme. */
    private val SAFE_TARGET = Regex("(?i)(https?://|mailto:|#|\\./|\\.\\./|/(?!/)|[A-Za-z0-9_][^:]*$).*")
}
