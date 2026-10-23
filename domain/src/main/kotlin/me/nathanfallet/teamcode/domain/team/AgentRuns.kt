package me.nathanfallet.teamcode.domain.team

/** Wrapper observations are distinct from provider conversations and authentication sessions. */
enum class RunState { RUNNING, STALE, ENDED, REVOKED }
/** What an agent's own hooks say, without any content: it works, or its turn ended or it asked for a person. */
enum class RunActivity(val wire: String) {
    WORKING("working"), WAITING_FOR_HUMAN("waiting_for_human");
    companion object { fun parse(value: String): RunActivity = entries.firstOrNull { it.wire == value }
        ?: throw TeamFailure("invalid_run_activity", 400, "Use working or waiting_for_human.") }
}
/**
 * The launch's state after a report. [superseded]: the report was older (by the reporter's own clock) than one already
 * stored, so nothing was written and [activity] is the newer stored state (null when a silence cleared it).
 */
data class RunActivityChange(val id: String, val activity: RunActivity?, val sinceMillis: Long?, val superseded: Boolean = false)
data class RunLease(val id: String, val startedAtMillis: Long, val heartbeatAtMillis: Long, val endedAtMillis: Long?, val state: RunState)
data class RunPolicy(val budgetUnits: Long = 0, val maxConcurrent: Int = 0, val paused: Boolean = false)
data class AgentRun(val id: String, val memberId: String, val username: String, val agent: String, val workload: String,
    val deviceId: String?, val startedAtMillis: Long, val heartbeatAtMillis: Long, val lastRequestAtMillis: Long?,
    val endedAtMillis: Long?, val exitCode: Int?, val state: RunState, val policy: RunPolicy,
    val chargedAndReservedUnits: Long, val attempts: Long, val completed: Long, val activeRequests: Int, val contextsTruncated: Boolean, val queuedRequests: Int = 0,
    val labels: Map<String, String> = emptyMap(), val activity: RunActivity? = null, val activitySinceMillis: Long? = null)
data class RunConversation(val sessionKey: String, val conversationKey: String, val requestedModel: String,
    val provider: String, val model: String, val lastSeenAtMillis: Long, val pace: String?, val directiveId: String?,
    val receivedByCliAtMillis: Long?, val emittedToHookAtMillis: Long?)
data class RunDetail(val run: AgentRun, val conversations: List<RunConversation>, val observationOnly: Boolean = false)
data class RunPage(val runs: List<AgentRun>, val nextBeforeId: String?)

/** Lifecycle writes bind to the creating credential; administrative reads/policies never expose it. */
interface AgentRunService {
    suspend fun start(actor: Principal, id: String, agent: String, workload: String, labels: Map<String, String> = emptyMap()): RunLease
    suspend fun heartbeat(actor: Principal, id: String): RunLease
    /**
     * Records the agent's reported state. A repeated state keeps its time. [observedAtMillis] is when the reporter saw the
     * event, by its own clock: a report older than the one stored arrived late (a slow request) and is not applied.
     */
    suspend fun activity(actor: Principal, id: String, activity: RunActivity, observedAtMillis: Long? = null): RunActivityChange
    suspend fun end(actor: Principal, id: String, exitCode: Int?): RunLease
    suspend fun list(actor: Principal, memberId: String? = null, all: Boolean = false, beforeId: String? = null, labels: Map<String, String> = emptyMap()): RunPage
    suspend fun get(actor: Principal, id: String): RunDetail
    suspend fun configure(actor: Principal, id: String, policy: RunPolicy): AgentRun
    companion object {
        const val PAGE_SIZE = 50
        const val MAX_CONTEXTS = 128
        const val MAX_MEMBER_RUNS = 10_000L
        const val MAX_TEAM_RUNS = 100_000L
        const val HEARTBEAT_TTL_MILLIS = 90_000L
        fun validateStart(id: String, agent: String, workload: String, labels: Map<String, String> = emptyMap()) {
            identifier(id)
            LabelRules.validate(labels)
            if (agent !in setOf("claude", "codex") || workload !in setOf("interactive", "background"))
                throw TeamFailure("invalid_run", 400, "Choose Claude or Codex and an interactive or background workload.")
        }
        fun identifier(id: String) {
            if (!Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id))
                throw TeamFailure("invalid_run", 400, "Use a canonical run UUID.")
        }
        fun validate(policy: RunPolicy) {
            if (policy.budgetUnits !in 0..100_000_000_000 || policy.maxConcurrent !in 0..32)
                throw TeamFailure("invalid_run_policy", 400, "Use a nonnegative credit cap and a concurrency cap between 0 and 32.")
        }
    }
}
