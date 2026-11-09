package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.notifications.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Notification cursors, deliveries and member switches. The worker reads the journal with bounded, indexed queries and
 * writes the deliveries and the cursor in one transaction, guarded by the cursor's value, so replicas can run it together.
 */
class SqlNotificationStore(private val database: Database) : NotificationStore {
    override suspend fun revalidate(actor: Principal) = database.query { requireCurrent(this, actor) }

    override suspend fun behind(limit: Int): List<NotificationCursor> = database.query {
        // One row per team: the journal's head next to the notification cursor.
        val rows = TeamEventCursors.join(NotificationCursors, JoinType.LEFT, TeamEventCursors.teamId, NotificationCursors.teamId)
            .select(TeamEventCursors.teamId, TeamEventCursors.lastSeq, NotificationCursors.fanoutSeq)
            .where { NotificationCursors.fanoutSeq.isNull() or (TeamEventCursors.lastSeq greater NotificationCursors.fanoutSeq) }
            .orderBy(TeamEventCursors.teamId to SortOrder.ASC).limit(limit).toList()
        val (fresh, late) = rows.partition { it.getOrNull(NotificationCursors.fanoutSeq) == null }
        // A team seen for the first time starts at its head: what happened before notifications existed stays unsent.
        if (fresh.isNotEmpty()) NotificationCursors.batchInsert(fresh, ignore = true, shouldReturnGeneratedValues = false) { row ->
            this[NotificationCursors.teamId] = row[TeamEventCursors.teamId]; this[NotificationCursors.fanoutSeq] = row[TeamEventCursors.lastSeq]
        }
        late.map { NotificationCursor(it[TeamEventCursors.teamId], it[NotificationCursors.fanoutSeq], it[TeamEventCursors.lastSeq]) }
    }

    override suspend fun read(cursor: NotificationCursor, limit: Int): NotificationSourcePage = database.query {
        val types = NotificationRules.SOURCE_TYPES.map { it.wire }
        val rows = TeamEvents.selectAll().where {
            (TeamEvents.teamId eq cursor.teamId) and (TeamEvents.seq greater cursor.from) and (TeamEvents.seq lessEq cursor.head) and (TeamEvents.type inList types)
        }.orderBy(TeamEvents.seq to SortOrder.ASC).limit(limit + 1).toList()
        val page = rows.take(limit)
        val events = page.mapNotNull { row ->
            TeamEventType.parse(row[TeamEvents.type])?.let { TeamEvent(row[TeamEvents.seq], it, row[TeamEvents.subjectId], row[TeamEvents.ownerId], row[TeamEvents.at], row[TeamEvents.data]) }
        }
        // What the events refer to, one statement each: a wait's subject is the wait, every other source's is the session.
        val waitIds = events.filter { it.type == TeamEventType.WAIT_CREATED }.map { it.subjectId }.toSet()
        val waits = if (waitIds.isEmpty()) emptyMap() else Waits.select(Waits.id, Waits.assigneeId, Waits.sessionId, Waits.kind, Waits.resolver, Waits.status, Waits.title)
            .where { (Waits.id inList waitIds) and (Waits.teamId eq cursor.teamId) }
            .associate { it[Waits.id] to WaitSummary(it[Waits.id], it[Waits.assigneeId], it[Waits.sessionId], it[Waits.kind], it[Waits.resolver], it[Waits.status] == "open", it[Waits.title]) }
        val sessionIds = events.filter { it.type != TeamEventType.WAIT_CREATED }.map { it.subjectId }.toSet() + waits.values.mapNotNull { it.sessionId }
        val names = if (sessionIds.isEmpty()) emptyMap() else CloudSessions.select(CloudSessions.id, CloudSessions.name)
            .where { (CloudSessions.id inList sessionIds) and (CloudSessions.teamId eq cursor.teamId) }.associate { it[CloudSessions.id] to it[CloudSessions.name] }
        val sources = events.map { event ->
            val wait = if (event.type == TeamEventType.WAIT_CREATED) waits[event.subjectId] else null
            NotificationSource(event, wait, names[wait?.sessionId ?: event.subjectId])
        }
        // A full page resumes after its last event; otherwise everything up to the head was looked at.
        NotificationSourcePage(sources, if (rows.size > limit) page.last()[TeamEvents.seq] else cursor.head)
    }

    override suspend fun disabled(memberIds: Set<String>): Set<Triple<String, String, NotificationKind>> = database.query {
        if (memberIds.isEmpty()) return@query emptySet()
        MemberNotificationSettings.selectAll().where { (MemberNotificationSettings.memberId inList memberIds) and (MemberNotificationSettings.enabled eq false) }
            .limit(memberIds.size * MAX_SWITCHES_PER_MEMBER)
            .mapNotNull { row -> NotificationKind.parse(row[MemberNotificationSettings.kind])?.let { Triple(row[MemberNotificationSettings.memberId], row[MemberNotificationSettings.channel], it) } }
            .toSet()
    }

    override suspend fun enqueue(cursor: NotificationCursor, through: Long, deliveries: List<NotificationDelivery>): Boolean = database.query {
        val moved = NotificationCursors.update({ (NotificationCursors.teamId eq cursor.teamId) and (NotificationCursors.fanoutSeq eq cursor.from) }) { it[fanoutSeq] = through }
        if (moved == 0) return@query false
        if (deliveries.isNotEmpty()) {
            val now = nowMillis()
            NotificationDeliveries.batchInsert(deliveries, ignore = true, shouldReturnGeneratedValues = false) { delivery ->
                val n = delivery.notification
                this[NotificationDeliveries.id] = n.id; this[NotificationDeliveries.teamId] = n.teamId; this[NotificationDeliveries.eventSeq] = n.eventSeq
                this[NotificationDeliveries.memberId] = n.memberId; this[NotificationDeliveries.channel] = delivery.channel; this[NotificationDeliveries.kind] = n.kind.wire
                this[NotificationDeliveries.title] = n.title.take(NotificationRules.MAX_TITLE); this[NotificationDeliveries.body] = n.body.take(NotificationRules.MAX_BODY)
                this[NotificationDeliveries.sessionId] = n.sessionId; this[NotificationDeliveries.waitId] = n.waitId; this[NotificationDeliveries.collapseKey] = n.collapseKey.take(80)
                this[NotificationDeliveries.state] = NotificationState.PENDING.wire; this[NotificationDeliveries.attempts] = 0; this[NotificationDeliveries.nextAttemptAt] = now
                this[NotificationDeliveries.leaseUntil] = 0; this[NotificationDeliveries.createdAt] = now
            }
        }
        true
    }

    override suspend fun claimDue(limit: Int, leaseMillis: Long): List<NotificationDelivery> = database.query {
        val now = nowMillis()
        val rows = NotificationDeliveries.selectAll().where {
            (NotificationDeliveries.state eq NotificationState.PENDING.wire) and (NotificationDeliveries.nextAttemptAt lessEq now) and (NotificationDeliveries.leaseUntil less now)
        }.orderBy(NotificationDeliveries.nextAttemptAt to SortOrder.ASC).limit(limit).toList()
        rows.mapNotNull { row ->
            val claimed = NotificationDeliveries.update({
                (NotificationDeliveries.id eq row[NotificationDeliveries.id]) and (NotificationDeliveries.leaseUntil less now) and (NotificationDeliveries.state eq NotificationState.PENDING.wire)
            }) { it[leaseUntil] = now + leaseMillis }
            if (claimed == 1) delivery(row) else null
        }
    }

    override suspend fun record(id: String, state: NotificationState, attempts: Int, nextAttemptAt: Long, error: String?) = database.query {
        val now = nowMillis()
        NotificationDeliveries.update({ NotificationDeliveries.id eq id }) {
            it[NotificationDeliveries.state] = state.wire; it[NotificationDeliveries.attempts] = attempts; it[NotificationDeliveries.nextAttemptAt] = nextAttemptAt
            it[leaseUntil] = 0; it[lastError] = error?.take(40); it[deliveredAt] = if (state == NotificationState.DELIVERED) now else null
        }
        Unit
    }

    override suspend fun maintain(limit: Int): Int = database.query {
        val now = nowMillis()
        // Ids first, then an update by key: updating `state` while scanning the index that starts with it would need a temporary table.
        // Pending deliveries are few by construction (given up after an hour), so the scan of the pending range stays small.
        val stale = NotificationDeliveries.select(NotificationDeliveries.id).where {
            (NotificationDeliveries.state eq NotificationState.PENDING.wire) and (NotificationDeliveries.createdAt less now - NotificationService.GIVE_UP_MILLIS) and (NotificationDeliveries.leaseUntil less now)
        }.limit(limit).map { it[NotificationDeliveries.id] }
        val expired = if (stale.isEmpty()) 0 else NotificationDeliveries.update({ (NotificationDeliveries.id inList stale) and (NotificationDeliveries.state eq NotificationState.PENDING.wire) }) {
            it[state] = NotificationState.FAILED.wire; it[lastError] = "expired"; it[leaseUntil] = 0
        }
        expired + NotificationDeliveries.deleteWhere(limit = limit) { createdAt less now - NotificationService.RETENTION_MILLIS }
    }

    override suspend fun settings(teamId: String, memberId: String): List<NotificationSetting> = database.query {
        (MemberNotificationSettings innerJoin Members).select(MemberNotificationSettings.channel, MemberNotificationSettings.kind, MemberNotificationSettings.enabled)
            .where { (MemberNotificationSettings.memberId eq memberId) and (Members.teamId eq teamId) }.limit(MAX_SWITCHES_PER_MEMBER)
            .mapNotNull { row -> NotificationKind.parse(row[MemberNotificationSettings.kind])?.let { NotificationSetting(row[MemberNotificationSettings.channel], it, row[MemberNotificationSettings.enabled]) } }
    }

    override suspend fun set(teamId: String, memberId: String, setting: NotificationSetting) = database.query {
        if (Members.select(Members.id).where { (Members.id eq memberId) and (Members.teamId eq teamId) }.empty()) throw TeamFailure("not_found", 404, "Member not found.")
        MemberNotificationSettings.upsert {
            it[MemberNotificationSettings.memberId] = memberId; it[channel] = setting.channel; it[kind] = setting.kind.wire; it[enabled] = setting.enabled
        }
        Unit
    }

    private fun delivery(row: ResultRow) = with(NotificationDeliveries) {
        NotificationDelivery(Notification(row[id], row[teamId], row[memberId], NotificationKind.parse(row[kind]) ?: NotificationKind.WAITING, row[title], row[body],
            row[sessionId], row[waitId], row[collapseKey], row[createdAt], row[eventSeq]), row[channel], row[attempts])
    }

    private companion object {
        /** Channels times kinds, with room for the channels to come. */
        const val MAX_SWITCHES_PER_MEMBER = 64
    }
}
