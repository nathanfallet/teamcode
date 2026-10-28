package me.nathanfallet.teamcode.domain.automations

import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.domain.cloud.CloudCreateOutcome
import me.nathanfallet.teamcode.domain.cloud.CloudInputStatus
import me.nathanfallet.teamcode.domain.cloud.CloudSessionDraft
import me.nathanfallet.teamcode.domain.cloud.CloudSessionService
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState
import me.nathanfallet.teamcode.domain.cloud.DesiredState
import me.nathanfallet.teamcode.domain.cloud.IdempotencyRequest
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure

/**
 * The scheduler: every due cron automation records the run of its latest due slot (once, when it is on time or within
 * the catch-up grace) and moves to its next slot, in one transaction fenced on the slot it read.
 */
class ScheduleAutomationsUseCase(private val store: AutomationStore) {
    suspend fun run(): Int {
        var recorded = 0
        for (automation in store.dueCron(BATCH)) {
            val expected = automation.nextRunAt ?: continue
            val trigger = automation.definition.trigger
            val now = store.now()
            // Checked when saved; only a timezone that left the time-zone database can fail here: the automation pauses, visibly.
            val schedule = try { CronSchedule.parse(trigger.cron!!, trigger.timezone!!, now, check = false) }
                catch (_: TeamFailure) { if (store.advance(automation.id, expected, null, null)) store.disableBySystem(automation.id, INVALID_SCHEDULE); continue }
            val window = if (trigger.catchup == CatchupPolicy.ONCE) maxOf(trigger.catchupGraceMinutes * 60_000L, AutomationRules.ON_TIME_MILLIS) else AutomationRules.ON_TIME_MILLIS
            val slot = schedule.latest(now, notBefore = maxOf(expected, now - window))
            val run = slot?.let { prepareRun(automation, RunSource.CRON, "cron:$it", null, now, scheduledFor = it, actor = null).run }
            if (store.advance(automation.id, expected, schedule.next(now), run) && run != null) recorded++
        }
        return recorded
    }
    companion object {
        const val BATCH = 100
        const val INVALID_SCHEDULE = "invalid_schedule"
    }
}

/**
 * The dispatcher: creates the session of each queued run as its owner, within the owner's automation cap, and follows
 * the runs whose session exists until they end.
 */
class DispatchAutomationRunsUseCase(private val store: AutomationStore, private val sessions: AutomationSessions, private val directory: AutomationDirectory) {
    suspend fun run(): Int {
        val claimed = store.claimQueued(BATCH, LEASE_MILLIS)
        for (item in claimed) dispatch(item)
        follow()
        return claimed.size
    }

    private suspend fun dispatch(item: DispatchableRun) {
        val run = item.run
        val now = store.now()
        if (run.cancelRequested) return store.finish(run.id, RunStatus.CANCELLED, "cancelled")
        val owner = store.ownerPrincipal(item.automation.id) ?: return ownerUnavailable(run)
        val prompt = item.prompt ?: return store.finish(run.id, RunStatus.FAILED, "prompt_missing")
        if (now - run.queuedAt > AutomationRules.QUEUE_TIMEOUT_MILLIS) return store.finish(run.id, RunStatus.EXPIRED, "capacity_timeout")
        if (store.working(run.teamId, run.ownerId) >= directory.settings(run.teamId).maxRunningAutomationsPerMember) return store.retry(run.id, now + CAPACITY_RETRY_MILLIS)
        try {
            val (sessionId, profileVersion) = sessions.create(owner, item.automation, run, prompt, "automation-run:${run.id}")
            store.started(run.id, sessionId, profileVersion)
        } catch (error: TeamFailure) {
            when {
                error.status == 401 || error.code in OWNER_CODES -> ownerUnavailable(run)
                error.retryable -> store.retry(run.id, now + ERROR_RETRY_MILLIS)
                else -> store.finish(run.id, RunStatus.FAILED, error.code)
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { store.retry(run.id, now + ERROR_RETRY_MILLIS) }
    }

    private suspend fun ownerUnavailable(run: AutomationRun) {
        store.finish(run.id, RunStatus.FAILED, AutomationService.OWNER_UNAVAILABLE)
        store.disableBySystem(run.automationId, AutomationService.OWNER_UNAVAILABLE)
    }

    private suspend fun follow() {
        val active = store.active(FOLLOW_BATCH)
        if (active.isEmpty()) return
        val progress = store.progress(active.mapNotNull { it.run.sessionId })
        val now = store.now()
        for ((run, maxRuntimeMinutes) in active) {
            val sessionId = run.sessionId ?: continue
            val state = progress[sessionId]
            val outcome: Pair<RunStatus, String?>? = when {
                state == null -> RunStatus.FAILED to "session_missing"
                run.cancelRequested -> { stop(run, sessionId); RunStatus.CANCELLED to "cancelled" }
                state.state == CloudSessionState.FAILED -> RunStatus.FAILED to "session_failed"
                state.state == CloudSessionState.TERMINATED || state.desiredState == DesiredState.TERMINATED -> RunStatus.FAILED to "session_deleted"
                state.initialInput == CloudInputStatus.CANCELLED || state.initialInput == CloudInputStatus.REJECTED -> RunStatus.FAILED to "message_rejected"
                // The turn that took the prompt (or a later one) ended: the run is over, as that turn ended.
                state.initialInput == CloudInputStatus.CONSUMED && state.turnEnd != null ->
                    if (state.turnEnd == COMPLETED) RunStatus.SUCCEEDED to null else RunStatus.FAILED to "turn_${state.turnEnd}"
                (run.startedAt ?: run.queuedAt) + maxRuntimeMinutes * 60_000L < now -> { stop(run, sessionId); RunStatus.TIMED_OUT to "max_runtime" }
                else -> { if (run.status == RunStatus.STARTING && state.state == CloudSessionState.ACTIVE) store.running(run.id); null }
            }
            if (outcome != null) { store.finish(run.id, outcome.first, outcome.second); release(run) }
        }
    }

    /** Pauses the session; one still being prepared cannot pause, so it is deleted. Best effort: the run ends either way. */
    private suspend fun stop(run: AutomationRun, sessionId: String) {
        val owner = store.ownerPrincipal(run.automationId) ?: return
        val preparing = bestEffort { sessions.pause(owner, sessionId) }?.let { it is TeamFailure && it.code == "session_preparing" } ?: false
        if (preparing) bestEffort { sessions.delete(owner, sessionId) }
    }

    /** Runs [call] and returns what it threw, so that one broken cloud call never stops following the other runs. */
    private suspend fun bestEffort(call: suspend () -> Unit): Exception? =
        try { call(); null } catch (error: CancellationException) { throw error } catch (error: Exception) { error }

    /** Deletes the sessions of the automation's runs beyond the latest [AutomationRules.KEEP_SESSIONS]. */
    private suspend fun release(run: AutomationRun) {
        val ids = store.releasable(run.automationId, AutomationRules.KEEP_SESSIONS)
        if (ids.isEmpty()) return
        val owner = store.ownerPrincipal(run.automationId) ?: return
        for (id in ids) bestEffort { sessions.delete(owner, id) }
    }

    companion object {
        const val BATCH = 25
        const val FOLLOW_BATCH = 200
        const val LEASE_MILLIS = 60_000L
        const val CAPACITY_RETRY_MILLIS = 15_000L
        const val ERROR_RETRY_MILLIS = 30_000L
        /** The owner can no longer act: the automation pauses until they enable it again. */
        val OWNER_CODES = setOf("authentication_required", "invalid_effective_member", "owner_unavailable", "password_change_required", "forbidden", "credential_scope")
        const val COMPLETED = "completed"
    }
}

/** Creates and stops automation sessions through the cloud session service, as the owner. */
class CloudAutomationSessions(private val cloud: CloudSessionService) : AutomationSessions {
    override suspend fun create(owner: Principal, automation: Automation, run: AutomationRun, prompt: String, idempotencyKey: String): Pair<String, Int> {
        val action = automation.definition.action
        val labels = mapOf(LABEL to automation.id) + (if (run.source == RunSource.TEST) mapOf(TEST_LABEL to "1") else emptyMap())
        val draft = CloudSessionDraft(automation.definition.name, action.profileId!!, null, action.model, action.effort, labels, null, null, prompt, automationId = automation.id)
        val session = when (val outcome = cloud.create(owner, null, draft, IdempotencyRequest(ROUTE, idempotencyKey, AutomationRules.sha256(run.id.encodeToByteArray())))) {
            is CloudCreateOutcome.Created -> outcome.session
            is CloudCreateOutcome.Replayed -> outcome.session
        }
        return session.id to session.profileVersion
    }
    override suspend fun pause(owner: Principal, sessionId: String) { cloud.pause(owner, null, sessionId, force = true) }
    override suspend fun delete(owner: Principal, sessionId: String) { cloud.delete(owner, null, sessionId) }

    companion object {
        const val LABEL = "automation"
        const val TEST_LABEL = "automation-test"
        const val ROUTE = "automation-run"
    }
}
