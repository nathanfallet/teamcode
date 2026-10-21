package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/**
 * When and what a team hook runs. `events` are agent event names (`PreToolUse`, `PostToolUse`, `UserPromptSubmit`,
 * `Stop`, `SubagentStop`, `SessionStart`, `SessionEnd`, `PreCompact`, `Notification`); `command` is the path of an
 * executable file of the artifact, run with no argument; `timeoutSeconds` is 1–600.
 */
@Serializable
data class HookDefinitionView(val events: List<String>, val matcher: String? = null, val command: String, val timeoutSeconds: Int = 60)

@Serializable
data class HookView(val id: String, val name: String, val pinnedVersion: String? = null, val createdAt: Long, val updatedAt: Long)

@Serializable
data class HookVersionView(
    val version: String,
    val digest: String,
    val definition: HookDefinitionView,
    val sizeBytes: Long,
    val fileCount: Int,
    val publishedBy: String,
    val publishedAt: Long,
    val pinned: Boolean,
)

@Serializable
data class HookDetailView(val hook: HookView, val versions: List<HookVersionView>)

/** Publication never pins: an interactive administrator approves each version with `PUT /api/v1/hooks/{name}/pin`. */
@Serializable
data class PublishHookVersionRequest(val version: String, val definition: HookDefinitionView, val files: List<SkillFileUpload>)

@Serializable
data class PublishHookVersionResponse(val hook: HookView, val version: HookVersionView, val created: Boolean)

@Serializable
data class PinHookRequest(val version: String)

@Serializable
data class HookAssignmentView(val memberId: String, val decision: String, val updatedAt: Long)

@Serializable
data class SetHookAssignmentRequest(val decision: String)

/** A pinned hook version the member is currently allowed to install. */
@Serializable
data class EffectiveHookView(val name: String, val version: String, val digest: String, val definition: HookDefinitionView, val sizeBytes: Long, val fileCount: Int)

@Serializable
data class HookManifestView(val memberId: String, val hooks: List<EffectiveHookView>)

/** The approved files of one effective version. `digest` is recomputable from the definition and the file metadata. */
@Serializable
data class HookArtifactView(val name: String, val version: String, val digest: String, val definition: HookDefinitionView, val files: List<SkillArtifactFile>)
