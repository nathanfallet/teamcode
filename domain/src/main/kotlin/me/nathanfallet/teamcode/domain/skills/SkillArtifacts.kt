package me.nathanfallet.teamcode.domain.skills

import java.security.MessageDigest

/**
 * Bounds and canonical digest of skill artifacts. Paths are relative, portable across macOS, Linux and Windows,
 * and cannot escape the installation directory. The upload format has no symbolic-link entry type; a publisher
 * that declares one is refused.
 */
object SkillArtifacts {
    const val MAX_ARTIFACT_BYTES = 5 * 1024 * 1024
    const val MAX_FILES = 200
    const val MAX_PATH_LENGTH = 255
    const val MAX_PATH_DEPTH = 8
    const val ENTRY_FILE = "SKILL.md"

    private val namePattern = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    private val versionPattern = Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}")
    private val segmentPattern = Regex("[A-Za-z0-9_][A-Za-z0-9._+-]{0,127}")
    private val digestPattern = Regex("sha256:[0-9a-f]{64}")
    private val windowsReserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

    fun validateName(name: String) {
        if (name.length > 64 || !namePattern.matches(name))
            throw skillFailure("invalid_skill_name", "Use a skill name of 1–64 lowercase letters, digits and single hyphens.")
    }

    fun validateVersion(version: String) {
        if (!versionPattern.matches(version))
            throw skillFailure("invalid_skill_version", "Use a version label of 1–64 letters, digits, dots, plus signs, underscores or hyphens.")
    }

    fun validateDigest(digest: String) {
        if (!digestPattern.matches(digest)) throw skillFailure("invalid_skill_digest", "Use a sha256:<64 lowercase hex> digest.")
    }

    fun validatePath(path: String) {
        val segments = path.split('/')
        val valid = path.length in 1..MAX_PATH_LENGTH && segments.size <= MAX_PATH_DEPTH && segments.all { segment ->
            segmentPattern.matches(segment) && !segment.endsWith('.') &&
                segment.substringBefore('.').uppercase() !in windowsReserved
        }
        if (!valid) throw skillFailure(
            "invalid_skill_path",
            "File paths must be relative, use '/' separators, at most $MAX_PATH_DEPTH segments and $MAX_PATH_LENGTH characters, " +
                "and contain only letters, digits, '.', '_', '+' or '-' without hidden, parent or reserved segments.",
        )
    }

    /** Validates bounds and paths, then returns the files sorted by path with their canonical digest. */
    fun validate(files: List<SkillFile>): SkillArtifact {
        if (files.isEmpty() || files.size > MAX_FILES)
            throw skillFailure("invalid_skill_artifact", "An artifact contains 1–$MAX_FILES files.")
        var total = 0L
        for (file in files) {
            validatePath(file.path)
            total += file.content.size
            if (total > MAX_ARTIFACT_BYTES)
                throw skillFailure("skill_artifact_too_large", "An artifact is limited to $MAX_ARTIFACT_BYTES bytes of file content.", 413)
        }
        // Case-insensitive file systems would merge these paths; a file cannot also be a directory.
        val folded = files.map { it.path.lowercase() }
        if (folded.toSet().size != folded.size)
            throw skillFailure("invalid_skill_artifact", "File paths must be unique, ignoring case.")
        val directories = folded.flatMap { path -> path.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" } }.toSet()
        if (folded.any { it in directories })
            throw skillFailure("invalid_skill_artifact", "A path cannot be both a file and a directory.")
        if (files.none { it.path == ENTRY_FILE })
            throw skillFailure("invalid_skill_artifact", "An artifact must contain $ENTRY_FILE at its root.")
        val sorted = files.sortedBy { it.path }
        val entries = sorted.map { SkillFileEntry(it.path, it.mode, it.content.size, sha256(it.content)) }
        return SkillArtifact(digest(entries), sorted, entries)
    }

    /**
     * Canonical digest: SHA-256 over one line per file, sorted by path, of the form
     * `<path>\u0000<mode>\u0000<size>\u0000<sha256 hex>\n`, rendered as `sha256:<hex>`.
     */
    fun digest(entries: List<SkillFileEntry>): String {
        val canonical = entries.sortedBy { it.path }.joinToString("") { "${it.path}\u0000${it.mode.wire}\u0000${it.size}\u0000${it.sha256}\n" }
        return "sha256:" + sha256(canonical.encodeToByteArray())
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
