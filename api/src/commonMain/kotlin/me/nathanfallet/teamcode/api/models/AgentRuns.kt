package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** Wire syntax shared by the local proxy and launcher; ownership is always checked by the server. */
object RunIdentifiers {
    /** The two states a launch's hooks report; the server accepts exactly these. */
    const val ACTIVITY_WORKING = "working"
    const val ACTIVITY_WAITING = "waiting_for_human"
    fun isValid(id: String): Boolean = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id)
}

@Serializable data class StartRunRequest(val id: String, val agent: String, val workload: String = "interactive", val labels: Map<String, String> = emptyMap())
@Serializable data class EndRunRequest(val exitCode: Int? = null)
/** Content-free: the agent's hook says only that it works or waits for a person. */
/** [observedAtMillis]: when the hook fired, by the reporter's clock; the server ignores a report older than the stored one. */
@Serializable data class RunActivityRequest(val state: String, val observedAtMillis: Long? = null)
/** The launch's state after the report; `superseded` when a newer report was already stored and this one changed nothing. */
@Serializable data class RunActivityView(val id: String, val state: String?, val sinceMillis: Long?, val superseded: Boolean = false)
@Serializable data class RunPolicyView(val budgetUnits: Long = 0, val maxConcurrent: Int = 0, val paused: Boolean = false)
/** Narrow lifecycle receipt: inference-only credentials do not gain usage-read permission. */
@Serializable data class RunLeaseView(val id: String, val startedAtMillis: Long, val heartbeatAtMillis: Long, val endedAtMillis: Long?, val state: String)
@Serializable data class AgentRunView(val id: String, val memberId: String, val username: String, val agent: String, val workload: String,
    val deviceId: String?, val startedAtMillis: Long, val heartbeatAtMillis: Long, val lastRequestAtMillis: Long?,
    val endedAtMillis: Long?, val exitCode: Int?, val state: String, val policy: RunPolicyView,
    val chargedAndReservedUnits: Long, val attempts: Long, val completed: Long, val activeRequests: Int, val contextsTruncated: Boolean, val queuedRequests: Int = 0,
    val labels: Map<String, String> = emptyMap(),
    /** `working` or `waiting_for_human` while the launch is RUNNING and its agent reported a state; otherwise absent. */
    val activity: String? = null, val activitySinceMillis: Long? = null)
@Serializable data class RunConversationView(val sessionKey: String, val conversationKey: String, val requestedModel: String,
    val provider: String, val model: String, val lastSeenAtMillis: Long, val pace: String?, val directiveId: String?,
    val receivedByCliAtMillis: Long?, val emittedToHookAtMillis: Long?)
@Serializable data class RunDetailView(val run: AgentRunView, val conversations: List<RunConversationView>, val observationOnly: Boolean = false)
@Serializable data class RunPageView(val runs: List<AgentRunView>, val nextBeforeId: String? = null)
