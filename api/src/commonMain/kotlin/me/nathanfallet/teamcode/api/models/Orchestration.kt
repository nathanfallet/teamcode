package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/**
 * Session orchestration (docs/orchestration.md): a cloud session whose profile enables it starts and drives its own child sessions
 * with its execution credential's `sessions:children` scope, through `/api/v1/cloud/children`. Clients check [CAPABILITY] first.
 */
object Orchestration {
    const val CAPABILITY = "cloud.orchestration.v1"
    const val SCOPE = "sessions:children"
    /** The label the server sets on a child: its parent's id. Reserved: a creation request may not set it. */
    const val PARENT_LABEL = "teamcode.parent"
    /** The `answer.action` of a `child` wait. */
    val childActions = listOf("child_turn_ended", "child_waiting", "child_paused", "child_ended", "timed_out")
}

/**
 * `POST /api/v1/cloud/children` (with `Idempotency-Key`). [profileId] null: the parent's profile; another one must be listed in the
 * parent profile version's `orchestration.childProfileIds`. The agent's kind, image, environment and idle timeout come from the profile.
 */
@Serializable
data class SpawnChildSessionRequest(
    val name: String,
    val profileId: String? = null,
    val agent: CloudAgentRequest? = null,
    val labels: Map<String, String> = emptyMap(),
    /** The child's first message, 1 to 32,000 characters: its task. */
    val initialMessage: String? = null,
)

/** `POST /api/v1/cloud/children/{id}/messages` (`Idempotency-Key` optional, a fresh one per call from the MCP tool). */
@Serializable
data class ChildMessageRequest(val text: String)

/**
 * `POST /api/v1/cloud/children/{id}/wait`: a wait on the calling session that resolves when the child ends its turn, starts waiting
 * for a person, is paused or ends; [timeoutSeconds] (30 to 604,800, 86,400 by default) and [onTimeout] (`wake` or `turn_failed`) as
 * for an event wait. Answers the [WaitView]: `status: resolved` at once when the child had already settled (no wake message then).
 */
@Serializable
data class ChildWaitRequest(val title: String? = null, val timeoutSeconds: Long? = null, val onTimeout: String? = null, val resumeMessage: String? = null)
