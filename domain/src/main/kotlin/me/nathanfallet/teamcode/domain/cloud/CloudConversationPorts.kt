package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.team.Principal

data class RunnerEventsAck(val acceptedThrough: Long, val lastSeq: Long)
data class RunnerInputAck(val id: String, val consumed: Boolean, val reason: String?)

/**
 * Transcript, inputs, attachments and the access policy in shared persistence. Every write that orders events or
 * inputs locks the session row, so replicas agree on sequence numbers and on the pending-input count.
 */
interface CloudConversationStore {
    suspend fun now(): Long

    /**
     * Appends runner events of [epoch] in order. An event whose `(session, epoch, sequence)` is already stored is
     * skipped, so a retried batch is idempotent; a gap (a sequence beyond the next expected one) stops the batch.
     */
    suspend fun appendRunnerEvents(sessionId: String, epoch: Int, events: List<RunnerEvent>): RunnerEventsAck
    /** Ascending page: events after [after] (forward), or the newest ones before [before] (backward). */
    suspend fun events(sessionId: String, after: Long?, before: Long?, limit: Int): CloudEventPage
    suspend fun lastSeq(sessionId: String): Long

    /**
     * Inserts an input under the session row lock: refuses a session that is ending, a stale view, more than
     * [pendingLimit] queued or delivered inputs, and replays an existing idempotency key (or refuses a different
     * request with the same key).
     */
    suspend fun insertInput(new: NewInput, pendingLimit: Int): InputOutcome
    suspend fun input(sessionId: String, id: String): CloudInput?
    suspend fun inputs(sessionId: String, beforeId: String?, limit: Int): CloudInputPage
    /** Marks the session's pending wake requests handled. */
    suspend fun clearWake(sessionId: String)
    /** Sessions that hold a queued input still waiting to wake them (sent while the session was pausing). */
    suspend fun pendingWakes(limit: Int): List<Pair<String, String>>

    /**
     * Delivers queued inputs to [epoch], oldest first. Inputs delivered to an earlier epoch and never consumed are
     * queued again first (or rejected with `delivery_failed` after [CloudConversationRules.MAX_ATTEMPTS]).
     */
    suspend fun deliver(sessionId: String, epoch: Int, limit: Int): List<CloudInput>
    /**
     * Records the runner's acknowledgements for inputs delivered to [epoch]. A consumed message is written to the
     * transcript as a `message.user` event; a rejection as an `input` event. Unknown or foreign ids are ignored.
     */
    suspend fun acknowledge(sessionId: String, epoch: Int, acks: List<RunnerInputAck>): List<CloudInput>
    /** Cancels queued and delivered inputs of sessions that ended (bounded); returns how many changed. */
    suspend fun cancelEnded(limit: Int): Int

    suspend fun attach(attachment: CloudAttachment): CloudAttachment
    /** Extends a live lease owned by [memberId]; null when it expired, was released or belongs to someone else. */
    suspend fun renew(sessionId: String, id: String, memberId: String, leaseUntil: Long): CloudAttachment?
    suspend fun detach(sessionId: String, id: String, memberId: String)
    suspend fun attachments(sessionId: String, now: Long): List<CloudAttachment>

    suspend fun accessPolicy(teamId: String): CloudAccessPolicy
    /** Replaces the policy when [expectedVersion] is current (0 for a team that never saved one); journals `cloud.access.updated`. */
    suspend fun saveAccessPolicy(actor: Principal, expectedVersion: Int, rules: List<CloudAccessRule>): CloudAccessPolicy

    /** Deletes events and finished inputs older than each team's transcript retention (bounded); returns the rows deleted. */
    suspend fun purgeTranscripts(limit: Int): Int
}

/**
 * Member groups, used by group rules of the access policy. TeamCode has no groups yet: [NONE] knows none, so a
 * group rule cannot be saved until a directory is wired here.
 */
interface CloudGroupDirectory {
    suspend fun groupsOf(teamId: String, memberId: String): Set<String>
    suspend fun exists(teamId: String, groupId: String): Boolean
    companion object {
        val NONE = object : CloudGroupDirectory {
            override suspend fun groupsOf(teamId: String, memberId: String) = emptySet<String>()
            override suspend fun exists(teamId: String, groupId: String) = false
        }
    }
}
