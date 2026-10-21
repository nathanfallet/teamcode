package me.nathanfallet.teamcode.domain.hooks

import me.nathanfallet.teamcode.domain.skills.SkillDecision
import me.nathanfallet.teamcode.domain.skills.SkillFile
import me.nathanfallet.teamcode.domain.skills.SkillFileEntry

/**
 * The agent events a team hook can listen to, with their wire name (identical in Claude Code and Codex). [codex] is
 * false for an event Codex has no equivalent of: a hook listening only to such events is not installed for Codex.
 */
enum class HookEvent(val wire: String, val codex: Boolean = true) {
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    USER_PROMPT_SUBMIT("UserPromptSubmit"),
    STOP("Stop"),
    SUBAGENT_STOP("SubagentStop"),
    SESSION_START("SessionStart"),
    SESSION_END("SessionEnd"),
    PRE_COMPACT("PreCompact"),
    /** Claude Code only: Codex has no notification hook. */
    NOTIFICATION("Notification", codex = false);

    companion object {
        fun of(wire: String) = entries.find { it.wire == wire }
    }
}

/**
 * What the agent runs, and when: [command] is the path of an executable file of the artifact, started with no
 * argument and the event's JSON on standard input, as the agents define it. Nothing else is configurable, so the
 * installed command line is always a quoted absolute path the client builds itself.
 */
data class HookDefinition(val events: Set<HookEvent>, val matcher: String?, val command: String, val timeoutSeconds: Int)

/** A catalog entry. A hook is delivered only through its pinned version, which only an interactive administrator sets. */
data class Hook(val id: String, val name: String, val pinnedVersion: String?, val createdAt: Long, val updatedAt: Long)

/** An immutable published version: its definition, files and digest never change. */
data class HookVersion(
    val id: String,
    val hookId: String,
    val version: String,
    val digest: String,
    val definition: HookDefinition,
    val sizeBytes: Long,
    val fileCount: Int,
    val publishedBy: String,
    val publishedAt: Long,
)

/** A validated artifact ready for persistence: its definition, files sorted by path and canonical digest. */
class HookArtifact(val digest: String, val definition: HookDefinition, val files: List<SkillFile>, val entries: List<SkillFileEntry>) {
    val sizeBytes: Long get() = entries.sumOf { it.size.toLong() }
    override fun toString() = "HookArtifact(digest=$digest, files=${entries.size})"
}

data class HookAssignment(val hookId: String, val memberId: String, val decision: SkillDecision, val updatedAt: Long)

/** A pinned, allowed hook version as delivered to one member. */
data class EffectiveHook(val name: String, val version: String, val digest: String, val definition: HookDefinition, val sizeBytes: Long, val fileCount: Int)

/** The persisted facts needed to decide whether one hook is delivered to one member. */
data class HookCandidate(val hook: Hook, val pinned: HookVersion?, val memberDecision: SkillDecision?)

data class HookPublication(val hook: Hook, val version: HookVersion, val created: Boolean)
