package me.nathanfallet.teamcode.domain.mcp

import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.team.requireAdmin
import me.nathanfallet.teamcode.domain.variants.VariantBinding
import me.nathanfallet.teamcode.domain.variants.VariantService

/** States of a hosted process, as the MCP host reports them (docs/mcp.md). The wire value is stored and shown as is. */
object HostedProcessStates {
    const val IDLE = "idle"
    const val STARTING = "starting"
    const val READY = "ready"
    const val FAILED = "failed"
    const val BACKOFF = "backoff"

    const val UNKNOWN = "unknown"
    private val KNOWN = setOf(IDLE, STARTING, READY, FAILED, BACKOFF)

    /** [state] when it is one of the host's states, else [UNKNOWN] (a newer host's state is never stored as is). */
    fun known(state: String) = if (state in KNOWN) state else UNKNOWN

    /** A state in which the process cannot serve: its last start failed, or it failed too often and waits. */
    fun failing(state: String) = state == FAILED || state == BACKOFF
}

/** What the host says about one process (`GET /v1/status`). [stderr] is scrubbed by the host; it is shown to administrators only. */
data class HostedProcessStatus(
    val key: String, val state: String, val launcher: String, val pkg: String,
    val pid: Long? = null, val startedAt: Long? = null, val lastUsedAt: Long? = null, val inFlight: Int = 0,
    val starts: Int = 0, val failures: Int = 0, val lastExitCode: Int? = null, val lastFailure: String? = null,
    val coldStartMillis: Long? = null, val rssBytes: Long? = null, val backoffUntil: Long? = null, val stderr: String = "",
)

/** The host's whole status: its version and every process it knows, by key. */
data class HostStatusSnapshot(val version: String, val processes: List<HostedProcessStatus>) {
    val byKey: Map<String, HostedProcessStatus> by lazy { processes.associateBy { it.key } }
}

/**
 * The last state the server recorded for the process of one assignment ([memberId] is empty for a SHARED variant, whose
 * process the whole team shares). [processKey] is the key it was observed under: after a rotation the stored state
 * belongs to the previous process and reads as [HostedProcessStates.IDLE]. [failure] is the last failure not yet
 * followed by a successful start, so a failure that went idle (a backoff that expired, a restart) still recovers once.
 */
data class HostedProcessRecord(
    val teamId: String, val variantId: String, val memberId: String, val processKey: String,
    val state: String, val failure: String?, val changedAt: Long,
) {
    /** The state of the process [key] now designates for this assignment. */
    fun stateFor(key: String) = if (key == processKey) state else HostedProcessStates.IDLE
    fun failureFor(key: String) = if (key == processKey) failure else null
}

/** A team event about a hosted process. */
enum class HostedProcessEvent { FAILED, RECOVERED }

/**
 * One write of the state sweep: [next] replaces [previous] (null: no row yet) only when [previous] is still what is
 * stored, so two replicas never publish the same transition twice. [server] is the variant target, for the event.
 */
data class HostedProcessChange(val previous: HostedProcessRecord?, val next: HostedProcessRecord, val server: String, val event: HostedProcessEvent?)

/** Pure transition rules of the state sweep, tested without a database. */
object HostedProcessStateRules {
    /**
     * The change for [assignment] given what the host reported (null: the host does not know the key, the process is
     * idle) and what is stored. Null when nothing needs writing. An event is produced when the process starts failing
     * ([HostedProcessEvent.FAILED], once per streak: retries, a `failed` that becomes `backoff` and an expired backoff stay
     * in it) and when it is ready again after a failure ([HostedProcessEvent.RECOVERED]).
     */
    fun change(assignment: HostedMcpAssignment, status: HostedProcessStatus?, stored: HostedProcessRecord?, now: Long): HostedProcessChange? {
        val key = assignment.key
        val previousFailure = stored?.failureFor(key)
        val state = status?.state?.let(HostedProcessStates::known) ?: HostedProcessStates.IDLE
        val failure = when {
            HostedProcessStates.failing(state) -> status?.lastFailure?.takeIf { FAILURE_CODE.matches(it) } ?: previousFailure ?: state
            state == HostedProcessStates.READY -> null
            else -> previousFailure
        }
        val event = when {
            // A streak lasts until a start succeeds: retries (starting, failed again) and an expired backoff stay in it.
            HostedProcessStates.failing(state) && previousFailure == null -> HostedProcessEvent.FAILED
            state == HostedProcessStates.READY && previousFailure != null -> HostedProcessEvent.RECOVERED
            else -> null
        }
        // Nothing stored and nothing worth storing: an idle process that never failed has no row.
        if (stored == null && state == HostedProcessStates.IDLE && failure == null) return null
        if (stored != null && stored.processKey == key && stored.state == state && stored.failure == failure) return null
        val next = HostedProcessRecord(assignment.teamId, assignment.variantId, assignment.processMemberId, key, state, failure, now)
        return HostedProcessChange(stored, next, assignment.target, event)
    }

    /** Host failure codes are short labels (`start_failed`, `crashed`, `memory`...); anything else is not stored. */
    val FAILURE_CODE = Regex("[a-z0-9_]{1,32}")
}

/** The stored states of hosted processes and the team events of their transitions. */
interface HostedProcessStateStore {
    /** The teams that have at least one stored state, at most [limit] (null when there are more). */
    suspend fun teamsWithStates(limit: Int): Set<String>?
    /** Every stored state of [teamIds], at most [limit] (null when there are more). */
    suspend fun records(teamIds: Set<String>, limit: Int): List<HostedProcessRecord>?
    /** The stored states of one member's assignments: for each variant, the member's row and the shared one. */
    suspend fun records(teamId: String, variantIds: Collection<String>, memberId: String): List<HostedProcessRecord>
    /**
     * Applies [changes] whose previous row is unchanged, publishing each one's event in the same transaction, and
     * deletes [removals] (states of assignments that no longer exist). Returns how many changes were applied.
     */
    suspend fun apply(changes: List<HostedProcessChange>, removals: List<HostedProcessRecord>): Int
    /** Publishes `mcp.unreachable` to each of [teamIds], with the [reason] (`unreachable` or `refused`). */
    suspend fun publishUnreachable(teamIds: Set<String>, reason: String)
    /** Journals an administrator's restart of a process (`mcp.stdio.restarted`, subject `variantId` or `variantId/memberId`). */
    suspend fun recordRestart(actor: Principal, variantId: String, memberId: String?)
}

/** What one state sweep did. */
sealed interface HostedMcpSweep {
    /** More than [RetainHostedMcpProcessesUseCase.MAX_ASSIGNMENTS] hosted assignments: nothing is stopped or recorded. */
    data object TooMany : HostedMcpSweep
    /**
     * The host did not answer ([UNREACHABLE]) or refused the request ([REFUSED]: a token or version mismatch between the
     * server and its host): [teamIds] have hosted assignments that cannot be served.
     */
    data class HostUnavailable(val teamIds: Set<String>, val reason: String = UNREACHABLE) : HostedMcpSweep
    data class Done(val stopped: Int, val changed: Int) : HostedMcpSweep

    companion object {
        const val UNREACHABLE = "unreachable"
        const val REFUSED = "refused"
    }
}

/** The team's hosted processes, for administrators. [username] is null for a shared process. */
data class HostedMcpProcess(
    val assignment: HostedMcpAssignment, val variantName: String, val username: String?, val launcher: String, val pkg: String,
    val record: HostedProcessRecord?, val status: HostedProcessStatus?,
) {
    val state get() = status?.state ?: record?.stateFor(assignment.key) ?: HostedProcessStates.IDLE
    val failure get() = record?.failureFor(assignment.key) ?: status?.lastFailure?.takeIf { HostedProcessStates.failing(status.state) }
}

data class HostedMcpProcesses(val hostReachable: Boolean, val hostVersion: String?, val processes: List<HostedMcpProcess>)

/**
 * Administration of hosted processes: their state on the host (the dashboard's "Hosted commands" and
 * `GET /api/v1/mcp/hosted`) and the "Restart" that stops one and lifts its backoff. Interactive administrators only.
 */
class HostedMcpAdministration(
    private val source: HostedMcpAssignmentSource,
    private val states: HostedProcessStateStore,
    private val host: McpProcessHost,
    private val variants: VariantService,
) {
    suspend fun processes(actor: Principal): HostedMcpProcesses {
        actor.requireAdmin()
        val assignments = source.teamHostedMcpAssignments(actor.teamId, RetainHostedMcpProcessesUseCase.MAX_ASSIGNMENTS)
        val byVariant = variants.list(actor).associateBy { it.id }
        val records = states.records(setOf(actor.teamId), RetainHostedMcpProcessesUseCase.MAX_ASSIGNMENTS * 2).orEmpty().associateBy { it.variantId to it.memberId }
        // Read after the database, never inside a transaction; an unreachable host still shows the last recorded states.
        val status = try { host.status() } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (_: Exception) { null }
        val processes = assignments.distinctBy { it.first.key }.mapNotNull { (assignment, username) ->
            val variant = byVariant[assignment.variantId] ?: return@mapNotNull null
            val command = variant.command ?: return@mapNotNull null
            HostedMcpProcess(assignment, variant.name, username.takeIf { assignment.binding == VariantBinding.PER_MEMBER }, command.launcher.wire, command.pkg,
                records[assignment.variantId to assignment.processMemberId], status?.byKey?.get(assignment.key))
        }.sortedWith(compareBy({ it.assignment.target }, { it.username.orEmpty() }))
        return HostedMcpProcesses(status != null, status?.version, processes)
    }

    /** Stops the process of [variantId] (and [memberId] for a PER_MEMBER variant); the next operation starts it again. */
    suspend fun restart(actor: Principal, variantId: String, memberId: String?): Boolean {
        actor.requireAdmin()
        val assignment = source.teamHostedMcpAssignments(actor.teamId, RetainHostedMcpProcessesUseCase.MAX_ASSIGNMENTS).map { it.first }.firstOrNull {
            it.variantId == variantId && (it.binding == VariantBinding.SHARED || it.memberId == memberId)
        } ?: throw TeamFailure("not_found", 404, "No hosted MCP process for this variant and member.")
        // Journaled first: a stop that times out after the host acted is still on record.
        states.recordRestart(actor, variantId, memberId.takeIf { assignment.binding == VariantBinding.PER_MEMBER })
        return host.stop(assignment.key)
    }
}
