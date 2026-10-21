package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.webhooks.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Subscriptions and their delivery log in shared persistence; every worker step is a conditional update, so replicas can run it together. */
class SqlWebhookStore(private val database: Database) : WebhookStore {
    override suspend fun revalidate(actor: Principal) = database.query { requireCurrent(this, actor) }

    override suspend fun list(teamId: String): List<WebhookSubscription> = database.query {
        WebhookSubscriptions.selectAll().where { WebhookSubscriptions.teamId eq teamId }.orderBy(WebhookSubscriptions.createdAt to SortOrder.ASC, WebhookSubscriptions.id to SortOrder.ASC)
            .limit(WebhookService.MAX_PER_TEAM).map(::subscription)
    }

    override suspend fun find(teamId: String, id: String): WebhookSubscription? = database.query { row(teamId, id)?.let(::subscription) }

    override suspend fun create(actor: Principal, subscription: WebhookSubscription, sealedSecret: String): WebhookSubscription = database.query {
        requireCurrent(this, actor)
        lockTeam(actor.teamId)
        if (WebhookSubscriptions.select(WebhookSubscriptions.id).where { WebhookSubscriptions.teamId eq actor.teamId }.count() >= WebhookService.MAX_PER_TEAM)
            throw TeamFailure("webhook_limit", 409, "A team has at most ${WebhookService.MAX_PER_TEAM} webhooks.")
        val now = nowMillis()
        val head = headOf(actor.teamId)
        WebhookSubscriptions.insert {
            it[id] = subscription.id; it[teamId] = subscription.teamId; it[url] = subscription.url; it[types] = encode(subscription.types)
            it[description] = subscription.description; it[enabled] = true; it[disabledReason] = null; it[secret] = sealedSecret
            it[createdBy] = actor.member.id; it[credentialId] = actor.tokenId; it[createdAt] = now; it[updatedAt] = now; it[version] = 1
            it[fanoutSeq] = head; it[failures] = 0
        }
        audit(actor.teamId, actor.member.id, "webhook.created", subscription.id, null, actor.tokenId)
        subscription(row(actor.teamId, subscription.id)!!)
    }

    override suspend fun update(actor: Principal, id: String, expectedVersion: Int, url: String, types: Set<TeamEventType>, description: String, enabled: Boolean): WebhookSubscription = database.query {
        requireCurrent(this, actor)
        val current = lockedRow(actor.teamId, id) ?: throw missing()
        if (current[WebhookSubscriptions.version] != expectedVersion) throw TeamFailure("stale_version", 409, "The webhook changed. Reload it and try again.")
        val now = nowMillis()
        val reenabled = enabled && !current[WebhookSubscriptions.enabled]
        WebhookSubscriptions.update({ WebhookSubscriptions.id eq id }) {
            it[WebhookSubscriptions.url] = url; it[WebhookSubscriptions.types] = encode(types); it[WebhookSubscriptions.description] = description
            it[WebhookSubscriptions.enabled] = enabled
            it[disabledReason] = if (enabled) null else (current[WebhookSubscriptions.disabledReason] ?: "disabled")
            it[updatedAt] = now; it[version] = expectedVersion + 1
            // Events of the time it was off are not replayed.
            if (reenabled) { it[fanoutSeq] = headOf(actor.teamId); it[failures] = 0 }
        }
        audit(actor.teamId, actor.member.id, "webhook.updated", id, null, actor.tokenId)
        subscription(row(actor.teamId, id)!!)
    }

    override suspend fun rotateSecret(actor: Principal, id: String, sealedSecret: String): WebhookSubscription = database.query {
        requireCurrent(this, actor)
        val current = lockedRow(actor.teamId, id) ?: throw missing()
        WebhookSubscriptions.update({ WebhookSubscriptions.id eq id }) {
            it[secret] = sealedSecret; it[updatedAt] = nowMillis(); it[version] = current[WebhookSubscriptions.version] + 1
        }
        audit(actor.teamId, actor.member.id, "webhook.secret_rotated", id, null, actor.tokenId)
        subscription(row(actor.teamId, id)!!)
    }

    override suspend fun delete(actor: Principal, id: String) = database.query {
        requireCurrent(this, actor)
        if (lockedRow(actor.teamId, id) == null) throw missing()
        WebhookSubscriptions.deleteWhere { WebhookSubscriptions.id eq id }
        audit(actor.teamId, actor.member.id, "webhook.deleted", id, null, actor.tokenId)
        Unit
    }

    override suspend fun deliveries(teamId: String, id: String, limit: Int): List<WebhookDelivery> = database.query {
        WebhookDeliveries.selectAll().where { (WebhookDeliveries.subscriptionId eq id) and (WebhookDeliveries.teamId eq teamId) }
            .orderBy(WebhookDeliveries.createdAt to SortOrder.DESC, WebhookDeliveries.id to SortOrder.DESC).limit(limit).map(::delivery)
    }

    override suspend fun enqueueTest(actor: Principal, id: String, payload: String): WebhookDelivery = database.query {
        requireCurrent(this, actor)
        lockedRow(actor.teamId, id) ?: throw missing()
        val now = nowMillis()
        // A test is not a pending event: a flood of them is bounded by the log, not by a queue.
        if (WebhookDeliveries.select(WebhookDeliveries.id).where { (WebhookDeliveries.subscriptionId eq id) and (WebhookDeliveries.test eq true) and (WebhookDeliveries.createdAt greater now - 10_000) }.count() >= 3)
            throw TeamFailure("webhook_test_rate", 429, "Wait a few seconds between tests.", retryAfterSeconds = 10)
        val deliveryId = Json.parseToJsonElement(payload).jsonObject["id"]!!.jsonPrimitive.content
        WebhookDeliveries.insert {
            it[WebhookDeliveries.id] = deliveryId; it[subscriptionId] = id; it[teamId] = actor.teamId; it[eventSeq] = null; it[eventType] = "webhook.test"
            it[WebhookDeliveries.payload] = payload; it[state] = DeliveryState.PENDING.wire; it[attempts] = 0; it[nextAttemptAt] = now; it[leaseUntil] = 0
            it[createdAt] = now; it[test] = true
        }
        audit(actor.teamId, actor.member.id, "webhook.tested", id, null, actor.tokenId)
        delivery(WebhookDeliveries.selectAll().where { WebhookDeliveries.id eq deliveryId }.single())
    }

    override suspend fun fanOut(subscriptions: Int, eventsPerSubscription: Int): Int {
        val candidates = database.query {
            WebhookSubscriptions.select(WebhookSubscriptions.id).where { WebhookSubscriptions.enabled eq true }.orderBy(WebhookSubscriptions.id to SortOrder.ASC).limit(subscriptions).map { it[WebhookSubscriptions.id] }
        }
        var created = 0
        for (id in candidates) created += database.query {
            val sub = WebhookSubscriptions.selectAll().where { (WebhookSubscriptions.id eq id) and (WebhookSubscriptions.enabled eq true) }.forUpdate().singleOrNull() ?: return@query 0
            val team = sub[WebhookSubscriptions.teamId]; val from = sub[WebhookSubscriptions.fanoutSeq]; val head = headOf(team)
            if (head <= from) return@query 0
            val wanted = decode(sub[WebhookSubscriptions.types]).map { it.wire }
            val rows = TeamEvents.selectAll().where { (TeamEvents.teamId eq team) and (TeamEvents.seq greater from) and (TeamEvents.seq lessEq head) and (TeamEvents.type inList wanted) }
                .orderBy(TeamEvents.seq to SortOrder.ASC).limit(eventsPerSubscription + 1).toList()
            val page = rows.take(eventsPerSubscription)
            val now = nowMillis()
            for (event in page) {
                val deliveryId = UUID.randomUUID().toString()
                val data = buildJsonObject {
                    put("seq", event[TeamEvents.seq]); put("type", event[TeamEvents.type])
                    put("subject", TeamEventType.parse(event[TeamEvents.type])?.subject?.wire ?: "unknown"); put("subjectId", event[TeamEvents.subjectId])
                    put("memberId", event[TeamEvents.ownerId]); put("at", event[TeamEvents.at])
                    put("data", runCatching { Json.parseToJsonElement(event[TeamEvents.data]) }.getOrDefault(JsonObject(emptyMap())))
                }
                WebhookDeliveries.insertIgnore {
                    it[WebhookDeliveries.id] = deliveryId; it[subscriptionId] = id; it[teamId] = team; it[eventSeq] = event[TeamEvents.seq]; it[eventType] = event[TeamEvents.type]
                    it[payload] = WebhookService.payload(deliveryId, event[TeamEvents.type], now, data.toString()); it[state] = DeliveryState.PENDING.wire; it[attempts] = 0
                    it[nextAttemptAt] = now; it[leaseUntil] = 0; it[createdAt] = now; it[test] = false
                }
            }
            val through = if (rows.size > eventsPerSubscription) page.last()[TeamEvents.seq] else head
            WebhookSubscriptions.update({ WebhookSubscriptions.id eq id }) { it[fanoutSeq] = through }
            page.size
        }
        return created
    }

    override suspend fun claimDue(limit: Int, leaseMillis: Long): List<DueDelivery> = database.query {
        val now = nowMillis()
        val rows = (WebhookDeliveries innerJoin WebhookSubscriptions).selectAll().where {
            (WebhookDeliveries.state eq DeliveryState.PENDING.wire) and (WebhookDeliveries.nextAttemptAt lessEq now) and (WebhookDeliveries.leaseUntil less now) and
                ((WebhookSubscriptions.enabled eq true) or (WebhookDeliveries.test eq true))
        }.orderBy(WebhookDeliveries.nextAttemptAt to SortOrder.ASC).limit(limit).toList()
        rows.mapNotNull { row ->
            val claimed = WebhookDeliveries.update({ (WebhookDeliveries.id eq row[WebhookDeliveries.id]) and (WebhookDeliveries.leaseUntil less now) and (WebhookDeliveries.state eq DeliveryState.PENDING.wire) }) {
                it[leaseUntil] = now + leaseMillis
            }
            if (claimed == 1) DueDelivery(delivery(row), row[WebhookSubscriptions.url], row[WebhookSubscriptions.secret]) else null
        }
    }

    override suspend fun record(id: String, state: DeliveryState, attempts: Int, nextAttemptAt: Long, result: SendResult, now: Long) = database.query {
        val row = WebhookDeliveries.selectAll().where { WebhookDeliveries.id eq id }.forUpdate().singleOrNull() ?: return@query
        WebhookDeliveries.update({ WebhookDeliveries.id eq id }) {
            it[WebhookDeliveries.state] = state.wire; it[WebhookDeliveries.attempts] = attempts; it[WebhookDeliveries.nextAttemptAt] = nextAttemptAt
            it[leaseUntil] = 0; it[lastStatus] = result.status; it[lastError] = result.error?.take(40)
            it[deliveredAt] = if (state == DeliveryState.DELIVERED) now else null
        }
        // Tests say nothing about the health of the subscription's real traffic.
        if (!row[WebhookDeliveries.test] && state != DeliveryState.PENDING) {
            val sub = WebhookSubscriptions.selectAll().where { WebhookSubscriptions.id eq row[WebhookDeliveries.subscriptionId] }.forUpdate().singleOrNull() ?: return@query
            WebhookSubscriptions.update({ WebhookSubscriptions.id eq sub[WebhookSubscriptions.id] }) {
                it[failures] = if (state == DeliveryState.DELIVERED) 0 else sub[failures] + 1
            }
        }
    }

    override suspend fun disableFailing(limit: Int): Int = database.query {
        val now = nowMillis()
        val ids = WebhookSubscriptions.select(WebhookSubscriptions.id).where { (WebhookSubscriptions.enabled eq true) and (WebhookSubscriptions.failures greaterEq WebhookService.DISABLE_AFTER) }
            .limit(limit).map { it[WebhookSubscriptions.id] }
        if (ids.isNotEmpty()) WebhookSubscriptions.update({ WebhookSubscriptions.id inList ids }) {
            it[enabled] = false; it[disabledReason] = "too_many_failures"; it[updatedAt] = now
            it[version] = version + 1
        }
        ids.size
    }

    override suspend fun maintain(limit: Int): Int = database.query {
        val now = nowMillis()
        val expired = WebhookDeliveries.update({
            (WebhookDeliveries.state eq DeliveryState.PENDING.wire) and (WebhookDeliveries.createdAt less now - WebhookService.GIVE_UP_MILLIS) and (WebhookDeliveries.leaseUntil less now)
        }, limit = limit) { it[state] = DeliveryState.FAILED.wire; it[lastError] = "expired"; it[leaseUntil] = 0 }
        expired + WebhookDeliveries.deleteWhere(limit = limit) { (state neq DeliveryState.PENDING.wire) and (createdAt less now - WebhookService.LOG_RETENTION_MILLIS) }
    }

    private fun missing() = TeamFailure("not_found", 404, "Webhook not found.")
    private fun row(teamId: String, id: String) = WebhookSubscriptions.selectAll().where { (WebhookSubscriptions.id eq id) and (WebhookSubscriptions.teamId eq teamId) }.singleOrNull()
    private fun lockedRow(teamId: String, id: String) = WebhookSubscriptions.selectAll().where { (WebhookSubscriptions.id eq id) and (WebhookSubscriptions.teamId eq teamId) }.forUpdate().singleOrNull()
    private fun headOf(teamId: String) = TeamEventCursors.select(TeamEventCursors.lastSeq).where { TeamEventCursors.teamId eq teamId }.singleOrNull()?.get(TeamEventCursors.lastSeq) ?: 0L
    private fun encode(types: Set<TeamEventType>) = types.map { it.wire }.sorted().joinToString(",")
    private fun decode(value: String) = value.split(',').mapNotNull(TeamEventType::parse).toSet()

    private fun subscription(row: ResultRow) = with(WebhookSubscriptions) {
        WebhookSubscription(row[id], row[teamId], row[url], decode(row[types]), row[description], row[enabled], row[disabledReason], row[createdBy], row[credentialId],
            row[createdAt], row[updatedAt], row[version], row[fanoutSeq], row[failures])
    }
    private fun delivery(row: ResultRow) = with(WebhookDeliveries) {
        WebhookDelivery(row[id], row[subscriptionId], row[teamId], row[eventSeq], row[eventType], row[payload], DeliveryState.parse(row[state]), row[attempts], row[nextAttemptAt],
            row[lastStatus], row[lastError], row[createdAt], row[deliveredAt], row[test])
    }
}
