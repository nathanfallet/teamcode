package me.nathanfallet.teamcode.domain.notifications

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.team.*

/**
 * What a member is told about. A kind is one switch per channel in the member's settings, on until they turn it off.
 * The wire name is stored, so it never changes once released.
 */
enum class NotificationKind(val wire: String) {
    /** A person is asked something: a wait assigned to them (`ask_human`, a workflow question, a blocked todo), or a permission request or a question of their session's agent. */
    WAITING("waiting"),
    /** The agent of their session finished its turn. */
    TURN_COMPLETED("turn_completed"),
    /** The agent's turn failed; a `turn_failed` wait offers to retry it. */
    TURN_FAILED("turn_failed"),
    /** Their session ended in `failed`. */
    SESSION_FAILED("session_failed");

    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}

/**
 * One notification for one member, whatever the channel: what happened, said neutrally. A channel renders it its own way
 * (a push, a Slack or Discord message) and keeps its own destinations. [title] and [body] are short English text; a client
 * may render [kind] with [sessionId] or [waitId] in its own language instead. [collapseKey] groups notifications that
 * replace one another (a newer one about the same session or wait).
 */
data class Notification(
    val id: String, val teamId: String, val memberId: String, val kind: NotificationKind,
    val title: String, val body: String, val sessionId: String?, val waitId: String?, val collapseKey: String, val createdAt: Long,
    /** The journal event it comes from. */
    val eventSeq: Long,
)

/** What one attempt on a channel produced. [error] is a short code, never a provider's answer. */
data class ChannelResult(val outcome: Outcome, val error: String? = null) {
    enum class Outcome {
        DELIVERED,
        /** Worth another attempt later: the provider was unreachable, failing or asking to slow down. */
        RETRY,
        /** Refused for good, or nothing left to deliver to. */
        FAILED,
    }

    companion object {
        val DELIVERED = ChannelResult(Outcome.DELIVERED)
        fun retry(error: String) = ChannelResult(Outcome.RETRY, error)
        fun failed(error: String) = ChannelResult(Outcome.FAILED, error)
    }
}

/**
 * A way to reach a member: push to their devices today, a Slack or Discord message later. A channel owns its
 * destinations (the push tokens on the member's devices, a linked chat account) and how a notification looks there; the
 * router decides what is sent and to whom, the same for every channel.
 */
interface NotificationChannel {
    /** Stable and stored with settings and deliveries: `push`, later `slack`, `discord`. At most 16 characters. */
    val id: String

    /** The members of [memberIds] this channel can reach now, in one read whatever their number. */
    suspend fun reachable(teamId: String, memberIds: Set<String>): Set<String>

    /** Delivers to every destination of the member on this channel. Bounded by the caller's deadline. */
    suspend fun deliver(notification: Notification): ChannelResult
}

/** A member's switch for one kind on one channel. Without a stored switch, the kind is on. */
data class NotificationSetting(val channel: String, val kind: NotificationKind, val enabled: Boolean)

/** A journal event with what it refers to, read in the same pass so the rules need no other read. */
data class NotificationSource(val event: TeamEvent, val wait: WaitSummary?, val sessionName: String?)

/** The part of a wait a notification needs; null in [NotificationSource] when the wait is gone. */
data class WaitSummary(val id: String, val assigneeId: String, val sessionId: String?, val kind: String, val resolver: String, val open: Boolean, val title: String)

/** One notification to queue: who, about what, without the channel (the router multiplies it by channels). */
data class NotificationDraft(val eventSeq: Long, val memberId: String, val kind: NotificationKind, val title: String, val body: String, val sessionId: String?, val waitId: String?, val collapseKey: String)

/** Turns journal events into notifications. Pure: every read happened before. */
object NotificationRules {
    /** The journal types that can notify; the store reads only these. */
    val SOURCE_TYPES = setOf(TeamEventType.WAIT_CREATED, TeamEventType.SESSION_WAITING, TeamEventType.CLOUD_TURN, TeamEventType.SESSION_ENDED)

    private const val DEFAULT_TITLE = "TeamCode"

    fun draft(source: NotificationSource): NotificationDraft? {
        val event = source.event
        val data = try { Json.parseToJsonElement(event.data) as? JsonObject } catch (_: Exception) { null } ?: return null
        fun str(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull
        val title = source.sessionName?.takeIf { it.isNotBlank() }?.take(MAX_TITLE) ?: DEFAULT_TITLE
        return when (event.type) {
            TeamEventType.WAIT_CREATED -> {
                // Waits a timer or an event resolves ask nobody; one answered or withdrawn before this pass is old news.
                val wait = source.wait?.takeIf { it.open && it.resolver == "human" } ?: return null
                val failed = wait.kind == "turn_failed"
                NotificationDraft(event.seq, wait.assigneeId, if (failed) NotificationKind.TURN_FAILED else NotificationKind.WAITING, title,
                    if (failed) "The agent's turn failed." else wait.title.take(MAX_BODY), wait.sessionId, wait.id, "wait:${wait.id}")
            }
            TeamEventType.SESSION_WAITING -> {
                val sessionId = str("sessionId") ?: return null
                val body = if (str("reason") == "question") "The agent has a question for you." else "The agent asks for your permission."
                NotificationDraft(event.seq, event.ownerId, NotificationKind.WAITING, title, body, sessionId, null, "session:$sessionId:waiting")
            }
            TeamEventType.CLOUD_TURN -> {
                val sessionId = str("sessionId") ?: return null
                // A failed turn notifies through its `turn_failed` wait, which carries the retry.
                if (str("phase") != "completed") return null
                NotificationDraft(event.seq, event.ownerId, NotificationKind.TURN_COMPLETED, title, "The agent finished its turn.", sessionId, null, "session:$sessionId:turn")
            }
            TeamEventType.SESSION_ENDED -> {
                val sessionId = str("sessionId") ?: return null
                if (str("state") != "failed") return null
                NotificationDraft(event.seq, event.ownerId, NotificationKind.SESSION_FAILED, title, "The session failed.", sessionId, null, "session:$sessionId:state")
            }
            else -> null
        }
    }

    const val MAX_TITLE = 120
    const val MAX_BODY = 200
}

enum class NotificationState(val wire: String) {
    PENDING("pending"), DELIVERED("delivered"), FAILED("failed");
    companion object { fun parse(value: String) = entries.single { it.wire == value } }
}

/** A notification on one channel; [Notification.id] is the delivery's id. */
data class NotificationDelivery(val notification: Notification, val channel: String, val attempts: Int)

/** A team whose journal moved past what was turned into notifications. */
data class NotificationCursor(val teamId: String, val from: Long, val head: Long)

/** Events after a cursor with what they refer to, and how far the cursor may move. */
data class NotificationSourcePage(val sources: List<NotificationSource>, val through: Long)

interface NotificationStore {
    suspend fun revalidate(actor: Principal)

    /**
     * Worker: the teams whose journal is ahead of their notification cursor, at most [limit]. A team seen for the first time
     * starts at its journal's head: nothing that happened before notifications existed is sent.
     */
    suspend fun behind(limit: Int): List<NotificationCursor>
    /** Worker: the events of [NotificationRules.SOURCE_TYPES] in `(from, head]`, at most [limit], with their waits and session names. */
    suspend fun read(cursor: NotificationCursor, limit: Int): NotificationSourcePage
    /** Worker: of [memberIds], the switches turned off, as `(memberId, channel, kind)`. */
    suspend fun disabled(memberIds: Set<String>): Set<Triple<String, String, NotificationKind>>
    /**
     * Worker: queues [deliveries] and moves the team's cursor from [NotificationCursor.from] to [through], together. False,
     * with nothing written, when the cursor moved meanwhile (another replica did this page).
     */
    suspend fun enqueue(cursor: NotificationCursor, through: Long, deliveries: List<NotificationDelivery>): Boolean
    /** Worker: claims up to [limit] deliveries due now for [leaseMillis]. */
    suspend fun claimDue(limit: Int, leaseMillis: Long): List<NotificationDelivery>
    suspend fun record(id: String, state: NotificationState, attempts: Int, nextAttemptAt: Long, error: String?)
    /** Gives up on pending deliveries older than [NotificationService.GIVE_UP_MILLIS], deletes finished ones older than [NotificationService.RETENTION_MILLIS]. */
    suspend fun maintain(limit: Int): Int

    /** The member's stored switches; a missing one is on. */
    suspend fun settings(teamId: String, memberId: String): List<NotificationSetting>
    suspend fun set(teamId: String, memberId: String, setting: NotificationSetting)
}

/** The settings of a member: the channels this server offers, the kinds, and the switches in force (every pair, defaults filled). */
data class NotificationSettings(val channels: List<String>, val settings: List<NotificationSetting>)

/** A member's own switches. Interactive credentials only: a service key has no person to notify. */
class NotificationService(private val store: NotificationStore, private val channels: List<String>) {
    suspend fun settings(actor: Principal): NotificationSettings {
        actor.requireInteractive()
        store.revalidate(actor)
        val stored = store.settings(actor.teamId, actor.member.id).associateBy { it.channel to it.kind }
        return NotificationSettings(channels, channels.flatMap { channel ->
            NotificationKind.entries.map { kind -> stored[channel to kind] ?: NotificationSetting(channel, kind, true) }
        })
    }

    suspend fun set(actor: Principal, channel: String, kind: String, enabled: Boolean): NotificationSettings {
        actor.requireInteractive()
        if (channel !in channels) throw TeamFailure("invalid_notification_setting", 400, "This server has no notification channel `${channel.take(16)}`.")
        val parsed = NotificationKind.parse(kind) ?: throw TeamFailure("invalid_notification_setting", 400, "Unknown notification kind.")
        store.revalidate(actor)
        store.set(actor.teamId, actor.member.id, NotificationSetting(channel, parsed, enabled))
        return settings(actor)
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        /** A notification late by more than this is no longer worth sending. */
        const val GIVE_UP_MILLIS = 3_600_000L
        /** Finished deliveries keep their text this long, for support; then they are deleted. */
        const val RETENTION_MILLIS = 24 * 3_600_000L
        /** Waits before attempt 2, 3, ...: 10 s, 30 s, 2 min, 10 min. */
        val BACKOFF_MILLIS = longArrayOf(10_000, 30_000, 120_000, 600_000)
    }
}

/**
 * The worker: one pass turns new journal events into deliveries, then attempts what is due. Runs outside every request
 * transaction and the team lock, one replica at a time (`notifications/deliver`); a crash between the two halves loses
 * nothing: deliveries and the cursor are written together, and a claimed delivery comes back after its lease.
 */
class DeliverNotificationsUseCase(
    private val store: NotificationStore, channels: List<NotificationChannel>,
    private val clock: () -> Long = System::currentTimeMillis, private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val channels = channels.associateBy { it.id }

    suspend fun run(): Int {
        fanOut()
        val due = store.claimDue(BATCH, LEASE_MILLIS)
        // Together, each with its own deadline: one slow provider delays neither the others nor its own backoff.
        coroutineScope { due.map { async { attempt(it) } }.awaitAll() }
        return due.size
    }

    /** Turns new events into deliveries, team by team, a page at a time. */
    suspend fun fanOut(): Int {
        if (channels.isEmpty()) return 0
        var queued = 0
        for (cursor in store.behind(TEAMS)) {
            val page = store.read(cursor, EVENTS)
            val drafts = page.sources.mapNotNull(NotificationRules::draft)
            val members = drafts.map { it.memberId }.toSet()
            val reachable = if (members.isEmpty()) emptyMap() else channels.values.associate { it.id to it.reachable(cursor.teamId, members) }
            val off = if (members.isEmpty()) emptySet() else store.disabled(members)
            val now = clock()
            val deliveries = drafts.flatMap { draft ->
                channels.keys.filter { channel -> draft.memberId in reachable.getValue(channel) && Triple(draft.memberId, channel, draft.kind) !in off }.map { channel ->
                    NotificationDelivery(Notification(newId(), cursor.teamId, draft.memberId, draft.kind, draft.title, draft.body, draft.sessionId, draft.waitId, draft.collapseKey, now, draft.eventSeq), channel, 0)
                }
            }
            if (store.enqueue(cursor, page.through, deliveries)) queued += deliveries.size
        }
        return queued
    }

    private suspend fun attempt(due: NotificationDelivery) {
        val channel = channels[due.channel]
        val result = if (channel == null) ChannelResult.failed("channel_unavailable") else try {
            withTimeout(SEND_DEADLINE_MILLIS) { channel.deliver(due.notification) }
        } catch (_: TimeoutCancellationException) { ChannelResult.retry("timeout") }
        val attempts = due.attempts + 1
        val id = due.notification.id
        when {
            result.outcome == ChannelResult.Outcome.DELIVERED -> store.record(id, NotificationState.DELIVERED, attempts, clock(), null)
            result.outcome == ChannelResult.Outcome.FAILED || attempts >= NotificationService.MAX_ATTEMPTS ->
                store.record(id, NotificationState.FAILED, attempts, clock(), result.error)
            else -> store.record(id, NotificationState.PENDING, attempts, clock() + NotificationService.BACKOFF_MILLIS[attempts - 1], result.error)
        }
    }

    companion object {
        const val LEASE_MILLIS = 60_000L
        const val SEND_DEADLINE_MILLIS = 15_000L
        const val BATCH = 50
        const val TEAMS = 100
        const val EVENTS = 200
    }
}
