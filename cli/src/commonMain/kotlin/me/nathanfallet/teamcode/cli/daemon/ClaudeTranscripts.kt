package me.nathanfallet.teamcode.cli.daemon

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.ResumableSessionView
import me.nathanfallet.teamcode.api.models.ResumableSessions
import me.nathanfallet.teamcode.api.models.ResumableSessionsView
import me.nathanfallet.teamcode.cli.platform.environment
import okio.BufferedSource
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * The Claude Code conversations of a folder on this device, which a remote session can continue
 * (`cloud.sessions.remote.resume.v1`). Claude Code keeps each one in `<config>/projects/<project>/<session id>.jsonl`,
 * where `<config>` is `CLAUDE_CONFIG_DIR` or `~/.claude` and `<project>` the directory it started in with every character
 * other than a letter or a digit replaced by `-` (cut and hashed past 200 characters). Since two folders can share a
 * project name (`/a/b-c` and `/a/b/c`), a conversation belongs to a folder only when its own `cwd` is that folder.
 *
 * Only an id, a short title, the time of the last write and the number of messages the member sent leave this class:
 * never the rest of a transcript. The format is Claude Code's own and changes between versions, so every line that does
 * not read as expected is skipped, and a file without a single message from the member is not offered.
 */
internal class ClaudeTranscripts(
    private val projects: Path?,
    private val windows: Boolean = isWindows(),
    private val fs: FileSystem = FileSystem.SYSTEM,
    /** When a transcript was last written: its modification time. */
    private val lastWrite: (Path, FileMetadata) -> Long = { _, metadata -> metadata.lastModifiedAtMillis ?: 0L },
) {
    /** The most recent conversations of [directory] first, at most [limit], last written before [before] when set. */
    fun list(directory: String, limit: Int, before: Long? = null): ResumableSessionsView {
        val wanted = limit.coerceIn(1, ResumableSessions.MAX_LIMIT)
        val candidates = transcripts(directory).filter { before == null || it.second < before }
            .sortedWith(compareByDescending<Pair<Path, Long>> { it.second }.thenBy { it.first.name })
        val found = mutableListOf<ResumableSessionView>()
        var read = 0
        for ((file, modified) in candidates) {
            // Each file is read up to its bound; a folder with thousands of conversations stops after a fixed number.
            if (found.size > wanted || read++ >= MAX_READ_PER_PAGE) break
            val summary = summarize(file) ?: continue
            if (!sameDirectory(summary.cwd, directory)) continue
            found += ResumableSessionView(file.name.removeSuffix(".jsonl"), summary.title, modified, summary.turns)
        }
        val page = found.take(wanted)
        return ResumableSessionsView(page, if (found.size > wanted) page.last().lastActiveAt else null)
    }

    /** Whether [sessionId] is one of [directory]'s conversations, as [list] would offer it. */
    fun belongs(directory: String, sessionId: String): Boolean {
        if (!ResumableSessions.ID.matches(sessionId)) return false
        val file = transcripts(directory).firstOrNull { it.first.name == "$sessionId.jsonl" }?.first ?: return false
        return summarize(file)?.let { sameDirectory(it.cwd, directory) } == true
    }

    /** The regular `<uuid>.jsonl` files of the project directories [directory] may be kept in, with their last write. */
    private fun transcripts(directory: String): List<Pair<Path, Long>> {
        val root = projects ?: return emptyList()
        val encoded = projectName(directory)
        val folders = if (encoded.length <= PROJECT_NAME_LIMIT) listOf(root / encoded)
            else (fs.listOrNull(root) ?: emptyList()).filter { it.name.startsWith(encoded.take(PROJECT_NAME_LIMIT)) }
        return folders.flatMap { folder ->
            val metadata = fs.metadataOrNull(folder)
            if (metadata == null || metadata.symlinkTarget != null || !metadata.isDirectory) return@flatMap emptyList()
            (fs.listOrNull(folder) ?: emptyList()).mapNotNull { file ->
                val name = file.name
                if (!name.endsWith(".jsonl") || !ResumableSessions.ID.matches(name.removeSuffix(".jsonl"))) return@mapNotNull null
                val info = fs.metadataOrNull(file) ?: return@mapNotNull null
                if (info.symlinkTarget != null || !info.isRegularFile) return@mapNotNull null
                file to lastWrite(file, info)
            }
        }
    }

    private fun sameDirectory(cwd: String?, directory: String): Boolean {
        if (cwd == null) return false
        if (!windows) return cwd.trimEnd('/').ifEmpty { "/" } == directory.trimEnd('/').ifEmpty { "/" }
        fun key(value: String) = value.replace('/', '\\').trimEnd('\\').lowercase()
        return key(cwd) == key(directory)
    }

    private fun summarize(file: Path): TranscriptSummary? = try {
        fs.read(file) { summarizeTranscript(this) }
    } catch (_: Exception) { null }

    companion object {
        /** Past this length Claude Code cuts the project's name and appends a hash, which is not reproduced here. */
        const val PROJECT_NAME_LIMIT = 200
        /** At most this many transcripts are opened for one page, whatever they hold. */
        const val MAX_READ_PER_PAGE = 200

        /** This device's Claude Code projects: `$CLAUDE_CONFIG_DIR/projects`, or `~/.claude/projects`. */
        fun local(): ClaudeTranscripts = ClaudeTranscripts(projectsDirectory(environment("CLAUDE_CONFIG_DIR"), homeDirectory()))

        fun projectsDirectory(configDir: String?, home: Path?): Path? =
            configDir?.takeIf { it.isNotBlank() }?.toPath()?.div("projects") ?: home?.div(".claude")?.div("projects")

        /** Claude Code's project name for a directory: each character other than an ASCII letter or digit becomes `-`. */
        fun projectName(directory: String): String = buildString(directory.length) {
            for (char in directory) append(if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9') char else '-')
        }
    }
}

/** What a transcript says about itself: the first `cwd`, a title, and the number of messages the member typed. */
internal data class TranscriptSummary(val cwd: String?, val title: String, val turns: Int)

private const val MAX_TRANSCRIPT_BYTES = 32L * 1024 * 1024
private const val MAX_LINE_BYTES = 2L * 1024 * 1024
private val lenient = Json { ignoreUnknownKeys = true; isLenient = false }

/**
 * Reads at most [MAX_TRANSCRIPT_BYTES] of a transcript, line by line; a line longer than [MAX_LINE_BYTES] (a large tool
 * result) is skipped without being held whole. The title is the last name the member gave the conversation
 * (`custom-title`), else its last summary (`summary`, older versions), else the start of its first message. Null when
 * the member sent no message (an empty or unreadable file).
 */
internal fun summarizeTranscript(source: BufferedSource): TranscriptSummary? {
    var cwd: String? = null
    var named: String? = null
    var summary: String? = null
    var first: String? = null
    var turns = 0
    source.boundedLines { line ->
        // Only the lines that can matter are parsed; the type is then read from the parsed line, never from the text.
        if ("\"custom-title\"" !in line && "\"summary\"" !in line && "\"user\"" !in line) return@boundedLines
        // A tool result (most user lines, often large) is never a message; typed text holds that only with escaped quotes.
        if ("\"type\":\"tool_result\"" in line) return@boundedLines
        val entry = line.json() ?: return@boundedLines
        when (entry.text("type")) {
            "custom-title" -> entry.text("customTitle")?.let { clean(it) }?.let { named = it }
            "summary" -> entry.text("summary")?.let { clean(it) }?.let { summary = it }
            "user" -> {
                if (entry.flag("isSidechain") || entry.flag("isMeta")) return@boundedLines
                val prompt = prompt(entry["message"] as? JsonObject) ?: return@boundedLines
                if (cwd == null) cwd = entry.text("cwd")
                turns++
                if (first == null) clean(prompt)?.let { first = it }
            }
        }
    }
    if (turns == 0) return null
    val title = named ?: summary ?: first ?: return null
    return TranscriptSummary(cwd, truncate(title), turns)
}

/**
 * The text of a message the member sent, or null for what Claude Code records as a user line but nobody typed: tool
 * results, and wrapped notices (`<task-notification>`, `<local-command-stdout>`…). A slash command reads as its name and
 * arguments; a message of images only is a message without text.
 */
private fun prompt(message: JsonObject?): String? {
    val content = message?.get("content") ?: return null
    if (content is JsonPrimitive && content.isString) {
        val text = content.content.trimStart()
        if (!text.startsWith("<")) return content.content
        val command = tag(text, "command-name") ?: return null
        return listOfNotNull(command, tag(text, "command-args")?.takeIf { it.isNotBlank() }).joinToString(" ")
    }
    if (content !is JsonArray) return null
    val blocks = content.mapNotNull { it as? JsonObject }
    if (blocks.isEmpty() || blocks.any { it.text("type") == "tool_result" }) return null
    return blocks.filter { it.text("type") == "text" }.mapNotNull { it.text("text") }.joinToString(" ")
}

private fun tag(text: String, name: String): String? {
    val start = text.indexOf("<$name>").takeIf { it >= 0 } ?: return null
    val end = text.indexOf("</$name>", start).takeIf { it >= 0 } ?: return null
    return text.substring(start + name.length + 2, end).trim()
}

/** One line: spaces for line breaks and runs of blanks, without control or format characters (which could reorder a line). */
internal fun clean(value: String): String? {
    val out = StringBuilder()
    var space = false
    for (char in value) {
        when {
            char.isWhitespace() || char.isISOControl() -> space = out.isNotEmpty()
            char.category == CharCategory.FORMAT || char.category == CharCategory.PRIVATE_USE -> {}
            else -> { if (space) out.append(' '); space = false; out.append(char) }
        }
    }
    return out.toString().takeIf { it.isNotEmpty() }
}

/** At most [ResumableSessions.TITLE_LENGTH] characters, an ellipsis included, never cutting a surrogate pair in two. */
internal fun truncate(value: String): String {
    val limit = ResumableSessions.TITLE_LENGTH
    if (value.length <= limit) return value
    var end = limit - 1
    if (value[end - 1].isHighSurrogate()) end--
    return value.substring(0, end).trimEnd() + "…"
}

private fun String.json(): JsonObject? = try { lenient.parseToJsonElement(this) as? JsonObject } catch (_: Exception) { null }
private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true

private inline fun BufferedSource.boundedLines(each: (String) -> Unit) {
    var read = 0L
    while (read < MAX_TRANSCRIPT_BYTES && !exhausted()) {
        val newline = indexOf('\n'.code.toByte(), 0, MAX_LINE_BYTES + 1)
        if (newline >= 0) { val line = readUtf8(newline); skip(1); read += newline + 1; each(line); continue }
        if (!request(MAX_LINE_BYTES + 1)) { val rest = readUtf8(); read += rest.length; each(rest); break }
        // A line longer than the bound: skipped up to its end, without holding it.
        val end = indexOf('\n'.code.toByte())
        if (end < 0) break
        skip(end + 1); read += end + 1
    }
}
