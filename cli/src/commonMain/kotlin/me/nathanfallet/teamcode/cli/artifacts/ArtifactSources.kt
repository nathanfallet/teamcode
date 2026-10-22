package me.nathanfallet.teamcode.cli.artifacts

import me.nathanfallet.teamcode.cli.CliUserException
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Why a component of a source path could not be read; [component] is its index below the root (-1 for the root). */
class SourceReadFailure(val kind: Kind, val component: Int) : Exception(kind.name) {
    enum class Kind { ROOT, LINK, MISSING, NOT_REGULAR, DENIED, TOO_LARGE, OTHER }
}

/**
 * Reads the regular file at [root]/[components] without following any symbolic link (or, on Windows, reparse point)
 * below [root], reading at most [maxBytes]. Throws [SourceReadFailure]; [SourceReadFailure.Kind.TOO_LARGE] past the bound.
 */
internal expect fun readWithoutLinks(root: String, components: List<String>, maxBytes: Long): ByteArray

/** A file read for publication: its published path, the local path it came from, its bytes and their SHA-256. */
class ArtifactSource(val publishedPath: String, val localPath: String, val bytes: ByteArray) {
    val sha256: String = bytes.toByteString().sha256().hex()
    val size: Long get() = bytes.size.toLong()
}

/**
 * The one place that reads files to publish, shared by `teamcode artifacts publish` and `teamcode mcp artifacts`. It
 * runs as the user (or agent) who launched it and only reads below its [roots]: a relative path is resolved against
 * the first root, an absolute one must lie inside one of them. No `..`, no symbolic link, regular files only, and the
 * places credentials live are refused by name; a refusal names the path and the rule, never the content.
 */
class ArtifactSources(roots: List<String>, private val refusedPrefixes: List<String> = emptyList(), private val fileSystem: FileSystem = FileSystem.SYSTEM,
    /**
     * Refused directories with one subdirectory still published: the configuration directory and the scratch directory
     * inside it. The exception lifts that refusal only, never [refusedPrefixes] or another entry.
     */
    private val refusedExcept: List<Pair<String, String>> = emptyList()) {
    private val roots: List<Path> = roots.map { fileSystem.canonicalize(it.toPath()) }.also { require(it.isNotEmpty()) }
    // Compared with canonical paths: a refused directory reached through a link (or /tmp for /private/tmp) is still refused.
    private fun canonical(path: String) = try { fileSystem.canonicalize(path.toPath()).toString() } catch (_: okio.IOException) { path }
    private val refused: List<String> = refusedPrefixes.flatMap { listOf(it, canonical(it)) }.distinct()
    private val refusedExceptCanonical: List<Pair<String, String>> = refusedExcept.map { (refused, kept) -> canonical(refused) to canonical(kept) }

    fun read(localPath: String, publishedPath: String): ArtifactSource {
        if ('\u0000' in localPath || localPath.isBlank()) refuse(localPath, "the path is empty or holds a NUL byte")
        val path = localPath.toPath(normalize = false)
        val (root, components) = locate(localPath, path)
        refusal(components)?.let { refuse(localPath, it) }
        val absolute = components.fold(root) { parent, name -> parent / name }.toString()
        fun under(prefix: String) = absolute == prefix || absolute.startsWith(prefix.trimEnd('/', '\\') + "/") || absolute.startsWith(prefix.trimEnd('/', '\\') + "\\")
        (refused.firstOrNull(::under) ?: refusedExceptCanonical.firstOrNull { (refused, kept) -> under(refused) && !under(kept) }?.first)
            ?.let { refuse(localPath, "files under $it are never published") }
        val bytes = try { readWithoutLinks(root.toString(), components, ArtifactLimits.MAX_TEXT_FILE_BYTES) }
        catch (failure: SourceReadFailure) {
            val at = if (failure.component in components.indices) components.take(failure.component + 1).joinToString("/") else root.toString()
            refuse(localPath, when (failure.kind) {
                SourceReadFailure.Kind.ROOT -> "the directory it is read from cannot be opened"
                SourceReadFailure.Kind.LINK -> "$at is a symbolic link or not a directory; links are never followed"
                SourceReadFailure.Kind.MISSING -> "$at does not exist"
                SourceReadFailure.Kind.NOT_REGULAR -> "it is not a regular file"
                SourceReadFailure.Kind.DENIED -> "$at cannot be read with your permissions"
                SourceReadFailure.Kind.TOO_LARGE -> "it is larger than 16 MiB"
                SourceReadFailure.Kind.OTHER -> "it cannot be read"
            })
        }
        return ArtifactSource(publishedPath, localPath, bytes)
    }

    /** The root a path belongs to and its components below it, refusing `..` and anything outside every root. */
    private fun locate(original: String, path: Path): Pair<Path, List<String>> {
        if (path.isAbsolute) {
            val target = path.segments
            for (root in roots) {
                val base = root.segments
                if (path.root?.toString().equals(root.root?.toString(), ignoreCase = true) && target.size > base.size && target.take(base.size) == base)
                    return root to clean(original, target.drop(base.size))
            }
            refuse(original, "it is outside the directories files are published from (${roots.joinToString(", ")})")
        }
        return roots.first() to clean(original, path.segments)
    }

    private fun clean(original: String, segments: List<String>): List<String> {
        if (segments.any { it == ".." }) refuse(original, "it contains ..")
        return segments.filter { it.isNotEmpty() && it != "." }.ifEmpty { refuse(original, "it names a directory") }
    }

    private fun refuse(path: String, rule: String): Nothing = throw CliUserException("Refused $path: $rule.")

    companion object {
        private val refusedDirectories = setOf(".git", ".ssh", ".aws", ".teamcode", ".gnupg")
        private val refusedPairs = listOf(".claude" to ".credentials.json", ".codex" to "auth.json")

        /** The rule a path breaks by name, or null: where credentials and keys live is never read. */
        fun refusal(components: List<String>): String? {
            val lower = components.map { it.lowercase() }
            lower.dropLast(1).firstOrNull { it in refusedDirectories }?.let { return "files in $it directories are never published" }
            val leaf = lower.last()
            if (leaf.startsWith(".env")) return ".env files are never published"
            if (leaf.endsWith(".pem") || leaf.endsWith(".key") || leaf.startsWith("id_rsa") || leaf.startsWith("id_ed25519") || leaf.startsWith("id_ecdsa"))
                return "private keys are never published"
            if (leaf == ".npmrc" || leaf == ".netrc" || leaf == ".pypirc") return "credential files are never published"
            if (lower.zipWithNext().any { it in refusedPairs }) return "agent credentials are never published"
            return null
        }
    }
}

/** The publication limits, checked again by the server. */
object ArtifactLimits {
    const val MAX_TEXT_FILE_BYTES = 16L * 1024 * 1024
    const val MAX_VERSION_FILES = 511
    const val MAX_VERSION_BYTES = 256L * 1024 * 1024
    private const val MAX_PATH_LENGTH = 255
    private const val MAX_PATH_DEPTH = 8
    private val segment = Regex("[A-Za-z0-9_][A-Za-z0-9._+-]{0,127}")
    private val windowsReserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
    private val entryExtensions = setOf("html", "htm", "md")

    /** The server's published path rule: relative, `/`-separated, portable, no hidden or parent segment. */
    fun validPublishedPath(path: String): Boolean {
        val segments = path.split('/')
        return path.length in 1..MAX_PATH_LENGTH && segments.size <= MAX_PATH_DEPTH && segments.all {
            segment.matches(it) && !it.endsWith('.') && it.substringBefore('.').uppercase() !in windowsReserved
        }
    }

    fun validEntry(path: String) = path.substringAfterLast('.', "").lowercase() in entryExtensions

    fun requirePublishedPath(path: String) {
        if (!validPublishedPath(path)) throw CliUserException("Invalid published path $path: use up to 8 /-separated segments of letters, digits, '.', '_', '+' or '-', not starting with a dot.")
    }
}

/** The page's name for the gallery: its `<title>` in the first 8 KiB, or a Markdown page's first `# ` heading. */
fun artifactTitle(page: ArtifactSource): String? {
    val head = page.bytes.copyOf(minOf(page.bytes.size, 8 * 1024)).decodeToString()
    val raw = if (page.publishedPath.lowercase().endsWith(".md")) head.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")
        else Regex("<title[^>]*>([^<]{1,400})</title>", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
            ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&#39;", "'")?.replace("&amp;", "&")
    return raw?.replace(Regex("\\s+"), " ")?.trim()?.filterNot { it.isISOControl() }?.take(200)?.takeIf { it.isNotEmpty() }
}
