package me.nathanfallet.teamcode.domain.automations

import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.*

/** In-memory persistence for the rules; the SQL behaviour is proven on MySQL by `AutomationPersistenceTest`. */
class MemoryAutomationStore(var clock: Long = 1_800_000_000_000L) : AutomationStore {
    val automations = linkedMapOf<String, Automation>()
    val secrets = mutableMapOf<String, SealedSecrets>()
    val runs = linkedMapOf<String, AutomationRun>()
    val prompts = mutableMapOf<String, String>()
    val keys = mutableMapOf<Pair<String, String>, String>()
    val attempts = mutableMapOf<String, Long>()
    val audit = mutableListOf<String>()
    val members = mutableMapOf<String, Member>()
    /** Automations whose owner credential was revoked. */
    val revoked = mutableSetOf<String>()
    val progress = mutableMapOf<String, SessionProgress>()
    val released = mutableSetOf<String>()

    override suspend fun now() = clock
    override suspend fun revalidate(actor: Principal) = Unit
    override suspend fun member(teamId: String, memberId: String) = members[memberId]
    override suspend fun create(actor: Principal, automation: Automation, sealedSecret: String?, limit: Int): Automation {
        if (automations.values.count { it.ownerId == automation.ownerId } >= limit) throw TeamFailure("automation_limit", 409, "Limit.")
        automations[automation.id] = automation; secrets[automation.id] = SealedSecrets(sealedSecret, null, null); audit += "automation.created"
        return automation
    }
    override suspend fun update(actor: Principal, id: String, expectedVersion: Int, definition: AutomationDefinition, nextRunAt: Long?): Automation {
        val current = automations.getValue(id)
        if (current.version != expectedVersion) throw TeamFailure("stale_version", 409, "Changed.")
        audit += "automation.updated"
        return current.copy(definition = definition, version = current.version + 1, nextRunAt = nextRunAt).also { automations[id] = it }
    }
    override suspend fun setEnabled(actor: Principal, id: String, enabled: Boolean, nextRunAt: Long?): Automation {
        if (enabled) revoked -= id
        audit += if (enabled) "automation.enabled" else "automation.disabled"
        return automations.getValue(id).copy(enabled = enabled, nextRunAt = nextRunAt, disabledReason = if (enabled) null else "disabled", disabledBy = if (enabled) null else actor.member.id)
            .also { automations[id] = it }
    }
    override suspend fun rotateSecret(actor: Principal, id: String, sealedSecret: String, previousValidUntil: Long): Automation {
        secrets[id] = SealedSecrets(sealedSecret, secrets[id]?.current, previousValidUntil); audit += "automation.secret_rotated"
        return automations.getValue(id).copy(previousSecretValidUntil = previousValidUntil).also { automations[id] = it }
    }
    override suspend fun delete(actor: Principal, id: String) { automations.remove(id); runs.values.removeIf { it.automationId == id }; audit += "automation.deleted" }
    override suspend fun find(teamId: String, id: String) = automations[id]?.takeIf { it.teamId == teamId }
    override suspend fun list(teamId: String, owner: String?, before: String?, limit: Int) =
        AutomationPage(automations.values.filter { it.teamId == teamId && (owner == null || it.ownerId == owner) }.take(limit), null)
    override suspend fun count(teamId: String, owner: String) = automations.values.count { it.ownerId == owner }
    override suspend fun forTrigger(id: String) = automations[id]?.let { it to secrets.getValue(id) }
    override suspend fun runs(teamId: String, automationId: String, query: RunQuery) =
        RunPage(runs.values.filter { it.automationId == automationId }.reversed().take(query.limit), null)
    override suspend fun run(teamId: String, automationId: String, runId: String) = runs[runId]?.takeIf { it.automationId == automationId }

    override suspend fun enqueue(actor: Principal?, automationId: String, run: NewRun): RunOutcome {
        val automation = automations.getValue(automationId)
        keys[automationId to run.deliveryKey]?.let { return RunOutcome.Replayed(runs.getValue(it)) }
        val mine = runs.values.filter { it.automationId == automationId }
        if (run.source == RunSource.WEBHOOK && mine.count { it.source == RunSource.WEBHOOK && it.queuedAt > clock - 60_000 } >= AutomationRules.WEBHOOK_PER_MINUTE)
            throw TeamFailure("automation_rate_limit", 429, "Slow down.", retryAfterSeconds = 60)
        val active = mine.filter { it.status in RunStatus.ACTIVE }
        val status = when {
            run.status != RunStatus.QUEUED -> run.status
            automation.definition.overlap == OverlapPolicy.SKIP && active.isNotEmpty() -> RunStatus.SKIPPED_OVERLAP
            automation.definition.overlap == OverlapPolicy.QUEUE && active.count { it.status == RunStatus.QUEUED } >= 1 -> RunStatus.SKIPPED_OVERLAP
            run.source != RunSource.TEST && mine.count { it.source != RunSource.TEST && it.status !in RunStatus.SKIPPED && it.queuedAt > clock - AutomationRules.DAY_MILLIS } >= automation.definition.maxRunsPerDay -> RunStatus.SKIPPED_BUDGET
            else -> RunStatus.QUEUED
        }
        val created = AutomationRun(run.id, automationId, automation.teamId, automation.ownerId, automation.version, run.source, status,
            if (status == run.status) run.reason else status.wire.removePrefix("skipped_"), null, null, run.scheduledFor, clock, null, if (status.final) clock else null, false, run.initiatorId, run.variables)
        runs[run.id] = created; keys[automationId to run.deliveryKey] = run.id
        if (status == RunStatus.QUEUED && run.prompt != null) prompts[run.id] = run.prompt
        if (run.source == RunSource.TEST) audit += "automation.test_run"
        if (status.final && run.source != RunSource.TEST) failures(automationId, status)
        return RunOutcome.Created(created)
    }
    override suspend fun auditPreview(actor: Principal, automationId: String) { audit += "automation.test_previewed" }
    override suspend fun wouldSkip(automationId: String) = if (runs.values.any { it.automationId == automationId && it.status in RunStatus.ACTIVE }) "overlap" else null
    override suspend fun requestCancel(actor: Principal, automationId: String, runId: String): AutomationRun {
        val run = runs[runId]?.takeIf { it.automationId == automationId } ?: throw TeamFailure("not_found", 404, "Run not found.")
        val next = when (run.status) {
            RunStatus.QUEUED -> run.copy(status = RunStatus.CANCELLED, reason = "cancelled", endedAt = clock).also { prompts -= runId }
            in RunStatus.WORKING -> run.copy(cancelRequested = true)
            else -> run
        }
        runs[runId] = next; return next
    }
    override suspend fun dueCron(limit: Int) = automations.values.filter { it.enabled && it.definition.trigger.kind == TriggerKind.CRON && (it.nextRunAt ?: Long.MAX_VALUE) <= clock }.take(limit)
    override suspend fun advance(automationId: String, expected: Long, next: Long?, run: NewRun?): Boolean {
        val automation = automations.getValue(automationId)
        if (automation.nextRunAt != expected) return false
        automations[automationId] = automation.copy(nextRunAt = next)
        run?.let { enqueue(null, automationId, it) }
        return true
    }
    override suspend fun claimQueued(limit: Int, leaseMillis: Long) = runs.values.filter { it.status == RunStatus.QUEUED && (attempts[it.id] ?: 0) <= clock }.take(limit)
        .map { attempts[it.id] = clock + leaseMillis; DispatchableRun(it, automations.getValue(it.automationId), prompts[it.id]) }
    override suspend fun working(teamId: String, ownerId: String) = runs.values.count { it.ownerId == ownerId && it.status in RunStatus.WORKING }
    override suspend fun ownerPrincipal(automationId: String): Principal? {
        if (automationId in revoked) return null
        val automation = automations[automationId] ?: return null
        val owner = members[automation.ownerId] ?: return null
        return Principal(owner, "credential-$automationId", automation.teamId, setOf(ServiceScope.CLOUD_WRITE))
    }
    override suspend fun retry(runId: String, at: Long) { attempts[runId] = at }
    override suspend fun started(runId: String, sessionId: String, profileVersion: Int) {
        runs[runId] = runs.getValue(runId).copy(status = RunStatus.STARTING, sessionId = sessionId, profileVersion = profileVersion, startedAt = clock); prompts -= runId
    }
    override suspend fun running(runId: String) { runs[runId] = runs.getValue(runId).copy(status = RunStatus.RUNNING) }
    override suspend fun finish(runId: String, status: RunStatus, reason: String?) {
        val run = runs.getValue(runId)
        if (run.status.final) return
        runs[runId] = run.copy(status = status, reason = reason, endedAt = clock); prompts -= runId
        if (run.source != RunSource.TEST) failures(run.automationId, status)
    }
    private fun failures(automationId: String, status: RunStatus) {
        val automation = automations[automationId] ?: return
        val count = when { status == RunStatus.SUCCEEDED -> 0; status.failure -> automation.consecutiveFailures + 1; else -> automation.consecutiveFailures }
        automations[automationId] = automation.copy(consecutiveFailures = count)
    }
    override suspend fun disableBySystem(automationId: String, reason: String) {
        automations[automationId] = automations.getValue(automationId).copy(enabled = false, disabledReason = reason, nextRunAt = null); audit += "automation.$reason"
    }
    override suspend fun active(limit: Int) = runs.values.filter { it.status in RunStatus.WORKING }
        .map { ActiveRun(it, automations.getValue(it.automationId).definition.action.maxRuntimeMinutes) }
    override suspend fun progress(sessionIds: List<String>) = progress.filterKeys { it in sessionIds }
    override suspend fun releasable(automationId: String, keep: Int): List<String> {
        val ended = runs.values.filter { it.automationId == automationId && it.status.final && it.sessionId != null }.reversed()
        return ended.drop(keep).mapNotNull { it.sessionId }.filter { released.add(it) }
    }
    override suspend fun purge(limit: Int) = 0
}

/** Records what the engine asked of the cloud sessions. */
class FakeAutomationSessions(private val store: MemoryAutomationStore) : AutomationSessions {
    val created = mutableListOf<Triple<String, String, String>>()
    val paused = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    var failure: TeamFailure? = null
    var preparing = false
    /** Thrown by every pause and delete, as a cloud call that breaks for another reason than a refusal. */
    var stopError: Exception? = null
    override suspend fun create(owner: Principal, automation: Automation, run: AutomationRun, prompt: String, idempotencyKey: String): Pair<String, Int> {
        failure?.let { throw it }
        created += Triple(owner.tokenId, prompt, idempotencyKey)
        store.progress["session-${run.id}"] = SessionProgress(CloudSessionState.CREATING, DesiredState.RUNNING, CloudInputStatus.QUEUED)
        return "session-${run.id}" to 3
    }
    override suspend fun pause(owner: Principal, sessionId: String) { stopError?.let { throw it }; if (preparing) throw TeamFailure("session_preparing", 409, "Preparing."); paused += sessionId }
    override suspend fun delete(owner: Principal, sessionId: String) { stopError?.let { throw it }; deleted += sessionId }
}

class FakeDirectory(var settings: CloudSettings = CloudSettings()) : AutomationDirectory {
    val profiles = mutableMapOf("p1" to CloudProfile("p1", "team", "dev", 3, CloudProfileSpec("c1", "ns", "image"), setOf("ada"), 0, 0))
    override suspend fun profile(teamId: String, profileId: String) = profiles[profileId]
    override suspend fun settings(teamId: String) = settings
}

object Fixtures {
    val sealer = object : SecretSealer {
        override fun seal(plaintext: ByteArray, context: String) = "sealed:$context:" + plaintext.decodeToString()
        override fun open(envelope: String, context: String) = envelope.removePrefix("sealed:$context:").takeIf { envelope.startsWith("sealed:$context:") }?.encodeToByteArray()
    }
    val ada = Member("ada", "ada", Role.MEMBER, false, false)
    val bob = Member("bob", "bob", Role.MEMBER, false, false)
    val admin = Member("admin", "admin", Role.ADMIN, false, false)
    fun principal(who: Member, scopes: Set<ServiceScope>? = null) = Principal(who, "token-${who.id}", "team", scopes)
    val cron = AutomationDefinition("Nightly", AutomationTrigger(TriggerKind.CRON, "0 21 * * *", "Europe/Paris"), AutomationAction(ActionKind.CLOUD, "p1"), "Summarize {{run.date}} ({{run.timezone}})")
    val webhook = AutomationDefinition("Ticket", AutomationTrigger(TriggerKind.WEBHOOK), AutomationAction(ActionKind.CLOUD, "p1"), "Fix ticket {{ticket.number}}",
        listOf(AutomationVariable("ticket.number", "$.ticket.number", VariableType.INT)))

    class World {
        val store = MemoryAutomationStore().also { it.members += listOf(ada, bob, admin).associateBy { m -> m.id } }
        val directory = FakeDirectory()
        val sessions = FakeAutomationSessions(store)
        private var secrets = 0
        val service = AutomationService(store, directory, sealer, random = { secrets++; ByteArray(32) { secrets.toByte() } })
        val dispatcher = DispatchAutomationRunsUseCase(store, sessions, directory)
        val scheduler = ScheduleAutomationsUseCase(store)
    }
}
