package me.nathanfallet.teamcode.domain.automations

import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.domain.cloud.CloudInputStatus
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState
import me.nathanfallet.teamcode.domain.cloud.DesiredState
import me.nathanfallet.teamcode.domain.team.Member
import me.nathanfallet.teamcode.domain.team.Principal

enum class TriggerKind(val wire: String) { CRON("cron"), WEBHOOK("webhook"); companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } } }
enum class ActionKind(val wire: String) { CLOUD("cloud"), REMOTE("remote"); companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } } }
enum class CatchupPolicy(val wire: String) { SKIP("skip"), ONCE("once"); companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } } }
enum class OverlapPolicy(val wire: String) { SKIP("skip"), QUEUE("queue"), ALLOW("allow"); companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } } }
enum class RunSource(val wire: String) { CRON("cron"), WEBHOOK("webhook"), TEST("test"); companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } } }

enum class RunStatus(val wire: String) {
    QUEUED("queued"), STARTING("starting"), RUNNING("running"), SUCCEEDED("succeeded"), FAILED("failed"), TIMED_OUT("timed_out"),
    CANCELLED("cancelled"), EXPIRED("expired"), SKIPPED_OVERLAP("skipped_overlap"), SKIPPED_BUDGET("skipped_budget"), INVALID_PAYLOAD("invalid_payload");
    val final get() = this !in ACTIVE
    /** Counts toward the consecutive failures of cron and webhook runs. */
    val failure get() = this == FAILED || this == TIMED_OUT || this == EXPIRED || this == INVALID_PAYLOAD
    companion object {
        val ACTIVE = setOf(QUEUED, STARTING, RUNNING)
        /** Runs that hold a place in the owner's automation cap. */
        val WORKING = setOf(STARTING, RUNNING)
        val SKIPPED = setOf(SKIPPED_OVERLAP, SKIPPED_BUDGET)
        fun parse(value: String) = entries.singleOrNull { it.wire == value }
    }
}

data class AutomationTrigger(
    val kind: TriggerKind, val cron: String? = null, val timezone: String? = null,
    val catchup: CatchupPolicy = CatchupPolicy.ONCE, val catchupGraceMinutes: Int = AutomationRules.DEFAULT_GRACE_MINUTES,
)
data class AutomationAction(val kind: ActionKind, val profileId: String?, val model: String? = null, val effort: String? = null, val maxRuntimeMinutes: Int = 60)

/** What the owner edits; every saved change bumps [Automation.version]. */
data class AutomationDefinition(
    val name: String, val trigger: AutomationTrigger, val action: AutomationAction, val prompt: String,
    val variables: List<AutomationVariable> = emptyList(), val samplePayload: JsonObject? = null,
    val overlap: OverlapPolicy = OverlapPolicy.SKIP, val maxRunsPerDay: Int = 24,
)

data class RunSummary(val id: String, val source: RunSource, val status: RunStatus, val queuedAt: Long)

data class Automation(
    val id: String, val teamId: String, val ownerId: String, val ownerName: String, val definition: AutomationDefinition,
    val enabled: Boolean, val disabledReason: String?, val disabledBy: String?, val version: Int, val nextRunAt: Long?,
    val consecutiveFailures: Int, val lastRun: RunSummary?, val secretRotatedAt: Long, val previousSecretValidUntil: Long?,
    val createdAt: Long, val updatedAt: Long,
)

data class AutomationRun(
    val id: String, val automationId: String, val teamId: String, val ownerId: String, val automationVersion: Int, val source: RunSource,
    val status: RunStatus, val reason: String?, val sessionId: String?, val profileVersion: Int?, val scheduledFor: Long?, val queuedAt: Long,
    val startedAt: Long?, val endedAt: Long?, val cancelRequested: Boolean, val initiatorId: String?,
    val variables: List<ExtractedVariable> = emptyList(),
)

/**
 * A run to record. [prompt] is the rendered prompt of a run that may start (kept until its session exists); [status]
 * is `queued` unless the trigger already failed ([RunStatus.INVALID_PAYLOAD]). [deliveryKey] is unique per automation.
 */
data class NewRun(
    val id: String, val automationId: String, val source: RunSource, val deliveryKey: String, val status: RunStatus, val reason: String?,
    val prompt: String?, val scheduledFor: Long?, val variables: List<ExtractedVariable>, val variablesDigest: String?, val initiatorId: String?,
    val credentialId: String?, val requestDigest: String? = null,
)

/** The outcome of recording a trigger: a new run (possibly already skipped), or the run its delivery key recorded before. */
sealed interface RunOutcome {
    val run: AutomationRun
    data class Created(override val run: AutomationRun) : RunOutcome
    data class Replayed(override val run: AutomationRun) : RunOutcome
}

/** A queued run claimed by the dispatcher, with what its session needs. */
data class DispatchableRun(val run: AutomationRun, val automation: Automation, val prompt: String?)

/** A run whose session exists, with its automation's runtime cap. */
data class ActiveRun(val run: AutomationRun, val maxRuntimeMinutes: Int)

/** A working run, and where its session is: [initialInput] is the status of the prompt sent at creation. */
data class SessionProgress(val state: CloudSessionState, val desiredState: DesiredState, val initialInput: CloudInputStatus?)

data class RunQuery(val before: String? = null, val limit: Int = 50, val sources: Set<RunSource> = emptySet(), val statuses: Set<RunStatus> = emptySet())
data class AutomationPage(val automations: List<Automation>, val nextBefore: String?)
data class RunPage(val runs: List<AutomationRun>, val nextBefore: String?)

/** The webhook secrets of an automation, sealed: the current one, and the former one with the end of its grace. */
data class SealedSecrets(val current: String?, val previous: String?, val previousValidUntil: Long?)

/** A cron automation the scheduler found due. */
data class DueAutomation(val automation: Automation)

/**
 * Persistence. Every write that names an actor revalidates them in its transaction. Recording a run ([enqueue]) applies
 * the overlap policy and, for cron and webhook runs, the per-minute and per-day limits under the automation's row lock.
 */
interface AutomationStore {
    suspend fun now(): Long
    suspend fun revalidate(actor: Principal)
    suspend fun member(teamId: String, memberId: String): Member?
    /** Fails `409 automation_limit` past [limit] automations for the owner; mints the owner credential. */
    suspend fun create(actor: Principal, automation: Automation, sealedSecret: String?, limit: Int): Automation
    /** `409 stale_version` unless [expectedVersion] is current; [nextRunAt] replaces the stored slot. */
    suspend fun update(actor: Principal, id: String, expectedVersion: Int, definition: AutomationDefinition, nextRunAt: Long?): Automation
    /** Enabling mints a new owner credential when the former one was revoked. Audited `automation.enabled` / `.disabled`. */
    suspend fun setEnabled(actor: Principal, id: String, enabled: Boolean, nextRunAt: Long?): Automation
    suspend fun rotateSecret(actor: Principal, id: String, sealedSecret: String, previousValidUntil: Long): Automation
    suspend fun delete(actor: Principal, id: String)
    suspend fun find(teamId: String, id: String): Automation?
    /** [owner] null: the whole team. Newest first by id. */
    suspend fun list(teamId: String, owner: String?, before: String?, limit: Int): AutomationPage
    suspend fun count(teamId: String, owner: String): Int
    /** For the webhook only: the automation of any team, by id, and its sealed secrets. */
    suspend fun forTrigger(id: String): Pair<Automation, SealedSecrets>?
    suspend fun runs(teamId: String, automationId: String, query: RunQuery): RunPage
    suspend fun run(teamId: String, automationId: String, runId: String): AutomationRun?
    /** Records [run] under the automation's lock; audited `automation.test_run` for a test. [actor] null for cron and webhook. */
    suspend fun enqueue(actor: Principal?, automationId: String, run: NewRun): RunOutcome
    /** `automation.test_previewed` in the audit journal. */
    suspend fun auditPreview(actor: Principal, automationId: String)
    /** Whether a real trigger would be skipped now: `overlap`, `budget` or null. */
    suspend fun wouldSkip(automationId: String): String?
    suspend fun requestCancel(actor: Principal, automationId: String, runId: String): AutomationRun

    /** Scheduler: enabled cron automations whose slot is due, oldest first. */
    suspend fun dueCron(limit: Int): List<Automation>
    /**
     * Moves the automation from [expected] to [next] and records [run] (when not null) in the same transaction, only if
     * its slot is still [expected]: two replicas never both advance it. Returns whether this call advanced it.
     */
    suspend fun advance(automationId: String, expected: Long, next: Long?, run: NewRun?): Boolean

    /** Dispatcher: claims queued runs due now for [leaseMillis]. */
    suspend fun claimQueued(limit: Int, leaseMillis: Long): List<DispatchableRun>
    /** The owner's runs that are starting or working. */
    suspend fun working(teamId: String, ownerId: String): Int
    /** Whether a run of the automation is starting or working: a `queue` automation's next run waits for it. */
    suspend fun busy(automationId: String): Boolean
    /** The owner's credential for the automation, or null when it was revoked (or the owner is no longer eligible). */
    suspend fun ownerPrincipal(automationId: String): Principal?
    suspend fun retry(runId: String, at: Long)
    /**
     * Links the created session to its queued run. False when the run ended meanwhile (cancelled): the run then only
     * records the session, unreleased, so that it is deleted now or by a later release.
     */
    suspend fun started(runId: String, sessionId: String, profileVersion: Int): Boolean
    suspend fun running(runId: String)
    /** Ends a run; cron and webhook runs update the automation's consecutive failures. */
    suspend fun finish(runId: String, status: RunStatus, reason: String?)
    /** Pauses the automation for a system reason (`owner_unavailable`, `invalid_schedule`) and clears its next slot, audited with actor `system`. */
    suspend fun disableBySystem(automationId: String, reason: String)
    /** Runs starting or working, with their session. */
    suspend fun active(limit: Int): List<ActiveRun>
    suspend fun progress(sessionIds: List<String>): Map<String, SessionProgress>
    /** Sessions of the automation's ended runs beyond its [keep] latest, not yet released. */
    suspend fun releasable(automationId: String, keep: Int): List<String>
    /** Marks [sessionIds], now deleted, released: their runs may then be purged. */
    suspend fun released(automationId: String, sessionIds: List<String>)
    /** Deletes ended runs older than [AutomationRules.RETENTION_MILLIS]; returns how many. */
    suspend fun purge(limit: Int): Int
}

/** The cloud sessions the engine creates and stops, as the owner. */
interface AutomationSessions {
    /** Creates the run's session; [idempotencyKey] makes a retried creation return the same session. Returns its id and profile version. */
    suspend fun create(owner: Principal, automation: Automation, run: AutomationRun, prompt: String, idempotencyKey: String): Pair<String, Int>
    suspend fun pause(owner: Principal, sessionId: String)
    suspend fun delete(owner: Principal, sessionId: String)
}
