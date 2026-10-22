package me.nathanfallet.teamcode.presentation

/**
 * How an artifact's entry page is served. HTML gets the skeleton agents expect (doctype, charset, viewport, a small
 * reset) when it has no doctype of its own; Markdown is rendered on the server into escaped HTML, so nothing in it
 * runs as markup. Other files are served as published.
 */
internal object ArtifactPages {
    private const val RESET = "*,*::before,*::after{box-sizing:border-box}body{margin:0}"
    private const val SKELETON = """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>$RESET</style>"""

    fun html(source: String): String {
        val start = source.trimStart('﻿', ' ', '\t', '\r', '\n')
        return if (start.regionMatches(0, "<!doctype", 0, 9, ignoreCase = true)) source else SKELETON + source
    }

    private const val MARKDOWN_STYLE = "body{max-width:760px;margin:0 auto;padding:32px 20px;font:16px/1.6 -apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Helvetica,Arial,sans-serif;color:#1f2328}" +
        "pre{background:#f6f8fa;padding:12px 14px;border-radius:8px;overflow:auto}code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:.92em}" +
        ":not(pre)>code{background:#f6f8fa;padding:1px 5px;border-radius:5px}blockquote{margin:0;padding:0 14px;border-left:3px solid #d0d7de;color:#57606a}" +
        "h1,h2,h3{line-height:1.25}hr{border:0;border-top:1px solid #d0d7de}" +
        "@media (prefers-color-scheme:dark){body{background:#0d1117;color:#e6edf3}pre,:not(pre)>code{background:#161b22}blockquote{color:#8b949e;border-color:#30363d}a{color:#58a6ff}}"

    /** A Markdown page as a whole HTML document; [title] is the artifact's. */
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
    fun render(source: String): String {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder()
        var i = 0
        val paragraph = mutableListOf<String>()
        fun flush() { if (paragraph.isNotEmpty()) { out.append("<p>").append(inline(paragraph.joinToString("\n"))).append("</p>\n"); paragraph.clear() } }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val fence = Regex("^(```+|~~~+)").find(trimmed)
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
                Regex("^#{1,6}\\s").containsMatchIn(trimmed) || Regex("^#{1,6}$").matches(trimmed) -> {
                    flush()
                    val level = trimmed.takeWhile { it == '#' }.length
                    out.append("<h$level>").append(inline(trimmed.drop(level).trim().trimEnd('#').trim())).append("</h$level>\n")
                }
                Regex("^(\\*\\s*){3,}$|^(-\\s*){3,}$|^(_\\s*){3,}$").matches(trimmed) && paragraph.isEmpty() -> { out.append("<hr>\n") }
                trimmed.startsWith(">") -> {
                    flush()
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { quoted += lines[i].trim().removePrefix(">").removePrefix(" "); i++ }
                    out.append("<blockquote>").append(render(quoted.joinToString("\n"))).append("</blockquote>\n")
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

    private val LIST_ITEM = Regex("^\\s{0,3}([-*+]|\\d{1,9}[.)])\\s+(.*)$")
    private val CODE = Regex("`([^`]+)`")
    private val LINK = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)")
    private val STRONG = Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__")
    private val EMPHASIS = Regex("(?<![*\\w])\\*([^*\\s][^*]*)\\*(?!\\*)|(?<![_\\w])_([^_\\s][^_]*)_(?![_\\w])")

    /** Inline markup over escaped text. Code spans are set aside first so nothing inside them is interpreted. */
    fun inline(text: String): String {
        val spans = mutableListOf<String>()
        var value = CODE.replace(text) { spans += "<code>${escape(it.groupValues[1])}</code>"; "\u0000${spans.size - 1}\u0000" }
        value = escape(value)
        value = LINK.replace(value) { match ->
            val target = match.groupValues[2]
            val decoded = target.replace("&amp;", "&")
            val safe = SAFE_TARGET.matches(decoded) && !decoded.trimStart().lowercase().let { it.startsWith("javascript:") || it.startsWith("data:") || it.startsWith("vbscript:") }
            if (safe) """<a href="$target" target="_blank" rel="noopener noreferrer">${match.groupValues[1]}</a>""" else match.groupValues[1]
        }
        value = STRONG.replace(value) { "<strong>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</strong>" }
        value = EMPHASIS.replace(value) { "<em>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</em>" }
        value = Regex("\u0000(\\d+)\u0000").replace(value) { m -> spans[m.groupValues[1].toInt()] }
        return value
    }

    /** `http(s)`, `mailto`, an anchor or a relative path: never another scheme. */
    private val SAFE_TARGET = Regex("(?i)(https?://|mailto:|#|\\./|\\.\\./|/(?!/)|[A-Za-z0-9_][^:]*$).*")
}
