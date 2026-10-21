package me.nathanfallet.teamcode.domain.hooks

import me.nathanfallet.teamcode.domain.skills.SkillArtifacts
import me.nathanfallet.teamcode.domain.skills.SkillFile
import me.nathanfallet.teamcode.domain.skills.SkillFileEntry
import me.nathanfallet.teamcode.domain.skills.SkillFileMode
import me.nathanfallet.teamcode.domain.team.TeamFailure

/**
 * Bounds and canonical digest of hook artifacts. Names, version labels and paths follow the skill catalog's rules;
 * the definition is part of the digest, so a client verifies what it will run and when.
 */
object HookArtifacts {
    const val MAX_FILES = 50
    const val MAX_ARTIFACT_BYTES = 1024L * 1024
    const val MAX_MATCHER_LENGTH = 256
    const val DEFAULT_TIMEOUT_SECONDS = 60
    const val MAX_TIMEOUT_SECONDS = 600

    private fun failure(code: String, message: String) = TeamFailure(code, 400, message)

    fun validateName(name: String) = try { SkillArtifacts.validateName(name) } catch (_: TeamFailure) {
        throw failure("invalid_hook_name", "Use a hook name of 1–64 lowercase letters, digits and single hyphens.")
    }

    fun validateVersion(version: String) = try { SkillArtifacts.validateVersion(version) } catch (_: TeamFailure) {
        throw failure("invalid_hook_version", "Use a version label of 1–64 letters, digits, dots, plus signs, underscores or hyphens.")
    }

    /** The definition alone: events, matcher, command path and timeout. The command's file is checked by [validate]. */
    fun validateDefinition(definition: HookDefinition) {
        if (definition.events.isEmpty()) throw failure("invalid_hook_definition", "A hook listens to at least one event.")
        val matcher = definition.matcher
        if (matcher != null && (matcher.length !in 1..MAX_MATCHER_LENGTH || matcher.any { it.isISOControl() }))
            throw failure("invalid_hook_definition", "A matcher is 1–$MAX_MATCHER_LENGTH characters without control characters.")
        if (definition.timeoutSeconds !in 1..MAX_TIMEOUT_SECONDS)
            throw failure("invalid_hook_definition", "A timeout is 1–$MAX_TIMEOUT_SECONDS seconds.")
        SkillArtifacts.validatePath(definition.command, "hook")
    }

    /** Validates the definition and the files; the command must be one of them, published as executable. */
    fun validate(definition: HookDefinition, files: List<SkillFile>): HookArtifact {
        validateDefinition(definition)
        val entries = SkillArtifacts.validateTree(files, MAX_FILES, MAX_ARTIFACT_BYTES, "hook")
        if (entries.none { it.path == definition.command && it.mode == SkillFileMode.EXECUTABLE })
            throw failure("invalid_hook_definition", "The command must be an executable file of the artifact.")
        return HookArtifact(digest(definition, entries), definition, files.sortedBy { it.path }, entries)
    }

    /**
     * Canonical digest: SHA-256 over one definition line,
     * `hook\u0000<events by wire name, sorted, comma-separated>\u0000<matcher or empty>\u0000<command>\u0000<timeout>\n`,
     * followed by the skill catalog's file lines (`<path>\u0000<mode>\u0000<size>\u0000<sha256 hex>\n`, sorted by path),
     * rendered as `sha256:<hex>`.
     */
    fun digest(definition: HookDefinition, entries: List<SkillFileEntry>): String {
        val events = definition.events.map { it.wire }.sorted().joinToString(",")
        val head = "hook\u0000$events\u0000${definition.matcher.orEmpty()}\u0000${definition.command}\u0000${definition.timeoutSeconds}\n"
        val files = entries.sortedBy { it.path }.joinToString("") { "${it.path}\u0000${it.mode.wire}\u0000${it.size}\u0000${it.sha256}\n" }
        return "sha256:" + SkillArtifacts.sha256((head + files).encodeToByteArray())
    }
}
