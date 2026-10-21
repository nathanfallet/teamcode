package me.nathanfallet.teamcode.domain.webhooks

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import me.nathanfallet.teamcode.domain.cloud.CloudAdministrationService
import me.nathanfallet.teamcode.domain.cloud.SecretSealer
import me.nathanfallet.teamcode.domain.team.*

enum class DeliveryState(val wire: String) {
    PENDING("pending"), DELIVERED("delivered"),
    /** Gave up: out of attempts, expired, or refused for good by the receiver. */
    FAILED("failed");
    companion object { fun parse(value: String) = entries.single { it.wire == value } }
}

/**
 * A team-wide subscription: every event of [types] the team's feed produces, whoever it belongs to, so only an
 * administrator (or an administrator's `admin:cloud` key) creates one. [fanoutSeq] is the last journal sequence
 * turned into deliveries.
 */
data class WebhookSubscription(
    val id: String, val teamId: String, val url: String, val types: Set<TeamEventType>, val description: String,
    val enabled: Boolean, val disabledReason: String?, val createdBy: String, val credentialId: String?,
    val createdAt: Long, val updatedAt: Long, val version: Int, val fanoutSeq: Long, val failures: Int,
)

data class WebhookDelivery(
    val id: String, val subscriptionId: String, val teamId: String, val eventSeq: Long?, val eventType: String, val payload: String,
    val state: DeliveryState, val attempts: Int, val nextAttemptAt: Long, val lastStatus: Int?, val lastError: String?,
    val createdAt: Long, val deliveredAt: Long?, val test: Boolean,
)

/** A delivery to attempt now, with the subscription's target and its sealed secret. */
data class DueDelivery(val delivery: WebhookDelivery, val url: String, val sealedSecret: String)

/** What one HTTP attempt produced: a status, or a short error code (never a response body or a message from the network stack). */
data class SendResult(val status: Int?, val error: String?) {
    val delivered get() = status != null && status in 200..299
    /** Worth another attempt: no answer, a server error, or a receiver asking us to slow down. */
    val retryable get() = status == null || status >= 500 || status == 408 || status == 429
}

/** The network edge. An implementation refuses hosts [CloudAdministrationService.allowedApiHost] refuses, also after DNS. */
fun interface WebhookSender {
    suspend fun send(url: String, headers: Map<String, String>, body: String): SendResult
}

interface WebhookStore {
    suspend fun revalidate(actor: Principal)
    suspend fun list(teamId: String): List<WebhookSubscription>
    suspend fun find(teamId: String, id: String): WebhookSubscription?
    /** Fails with `webhook_limit` past [WebhookService.MAX_PER_TEAM]; the subscription starts at the journal's head, so it sees only what happens next. */
    suspend fun create(actor: Principal, subscription: WebhookSubscription, sealedSecret: String): WebhookSubscription
    /** `409 stale_version` unless [expectedVersion] is current; re-enabling restarts at the journal's head and clears the failure count. */
    suspend fun update(actor: Principal, id: String, expectedVersion: Int, url: String, types: Set<TeamEventType>, description: String, enabled: Boolean): WebhookSubscription
    suspend fun rotateSecret(actor: Principal, id: String, sealedSecret: String): WebhookSubscription
    suspend fun delete(actor: Principal, id: String)
    suspend fun deliveries(teamId: String, id: String, limit: Int): List<WebhookDelivery>
    /** Queues one `webhook.test` delivery, sent once and never retried. */
    suspend fun enqueueTest(actor: Principal, id: String, payload: String): WebhookDelivery

    /** Worker: turns journal events after each enabled subscription's cursor into deliveries; returns how many. */
    suspend fun fanOut(subscriptions: Int, eventsPerSubscription: Int): Int
    /** Worker: claims up to [limit] deliveries due now for [leaseMillis]; deliveries of disabled subscriptions are skipped. */
    suspend fun claimDue(limit: Int, leaseMillis: Long): List<DueDelivery>
    suspend fun record(id: String, state: DeliveryState, attempts: Int, nextAttemptAt: Long, result: SendResult, now: Long)
    /** Disables subscriptions whose last [WebhookService.DISABLE_AFTER] deliveries all failed for good. */
    suspend fun disableFailing(limit: Int): Int
    /** Gives up on pending deliveries older than [WebhookService.GIVE_UP_MILLIS] and deletes finished ones older than [WebhookService.LOG_RETENTION_MILLIS]. */
    suspend fun maintain(limit: Int): Int
}

class CreatedWebhook(val webhook: WebhookSubscription, val secret: String) {
    override fun toString() = "CreatedWebhook(webhook=$webhook, secret=[REDACTED])"
}

/** Management of subscriptions: administrators and administrators' `admin:cloud` keys only. */
class WebhookService(private val store: WebhookStore, private val sealer: SecretSealer, private val random: () -> ByteArray = { ByteArray(32).also(SecureRandom()::nextBytes) }) {
    private fun read(actor: Principal) { actor.requireCloudRead(); actor.requireCloudAdministrator() }
    private fun write(actor: Principal) { actor.requireCloudWrite(); actor.requireCloudAdministrator() }
    private fun missing() = TeamFailure("not_found", 404, "Webhook not found.")

    suspend fun list(actor: Principal): List<WebhookSubscription> { read(actor); store.revalidate(actor); return store.list(actor.teamId) }
    suspend fun get(actor: Principal, id: String): WebhookSubscription { read(actor); store.revalidate(actor); return store.find(actor.teamId, id) ?: throw missing() }
    suspend fun deliveries(actor: Principal, id: String, limit: Int = 50): List<WebhookDelivery> {
        get(actor, id)
        return store.deliveries(actor.teamId, id, limit.coerceIn(1, 200))
    }

    suspend fun create(actor: Principal, url: String, types: Set<TeamEventType>, description: String): CreatedWebhook {
        write(actor)
        validate(url, types, description)
        val secret = newSecret()
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val draft = WebhookSubscription(id, actor.teamId, url, types, description, true, null, actor.member.id, actor.tokenId, now, now, 1, 0, 0)
        return CreatedWebhook(store.create(actor, draft, sealer.seal(secret.encodeToByteArray(), context(id))), secret)
    }

    suspend fun update(actor: Principal, id: String, version: Int, url: String, types: Set<TeamEventType>, description: String, enabled: Boolean): WebhookSubscription {
        write(actor); validate(url, types, description)
        return store.update(actor, id, version, url, types, description, enabled)
    }

    /** A new secret, shown once; deliveries already queued are signed with it. */
    suspend fun rotateSecret(actor: Principal, id: String): CreatedWebhook {
        write(actor)
        val secret = newSecret()
        return CreatedWebhook(store.rotateSecret(actor, id, sealer.seal(secret.encodeToByteArray(), context(id))), secret)
    }

    suspend fun delete(actor: Principal, id: String) { write(actor); store.delete(actor, id) }

    /** The "test" button: one signed `webhook.test` delivery, so the receiver can check its URL and signature check. */
    suspend fun test(actor: Principal, id: String): WebhookDelivery {
        write(actor)
        val deliveryId = UUID.randomUUID().toString()
        val payload = payload(deliveryId, "webhook.test", System.currentTimeMillis(), """{"message":"This is a test delivery from TeamCode."}""")
        return store.enqueueTest(actor, id, payload)
    }

    private fun validate(url: String, types: Set<TeamEventType>, description: String) {
        val authority = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (url.length > MAX_URL || !url.startsWith("https://") || '#' in url || '@' in authority || url.any { it.isISOControl() || it.isWhitespace() } ||
            !CloudAdministrationService.allowedApiHost(url) || authority.isEmpty())
            throw TeamFailure("invalid_webhook", 400, "Use an https URL without credentials or a fragment, and not a link-local or cloud metadata address.")
        if (types.isEmpty()) throw TeamFailure("invalid_webhook", 400, "Choose at least one event type.")
        if (description.length > MAX_DESCRIPTION || description.any { it.isISOControl() }) throw TeamFailure("invalid_webhook", 400, "The description is at most $MAX_DESCRIPTION characters.")
    }

    private fun newSecret() = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random())

    companion object {
        const val MAX_PER_TEAM = 20
        const val MAX_URL = 2_048
        const val MAX_DESCRIPTION = 200
        const val MAX_ATTEMPTS = 8
        const val DISABLE_AFTER = 20
        const val GIVE_UP_MILLIS = 24 * 3_600_000L
        const val LOG_RETENTION_MILLIS = 14 * 24 * 3_600_000L
        /** Waits before attempt 2, 3, ...: 10 s, 30 s, 2 min, 10 min, 30 min, 2 h, 6 h. */
        val BACKOFF_MILLIS = longArrayOf(10_000, 30_000, 120_000, 600_000, 1_800_000, 7_200_000, 21_600_000)
        fun context(subscriptionId: String) = "webhook:$subscriptionId"

        /** The body of a delivery: `{id, type, createdAt, data}`, where `data` is the event as the SSE feed sends it. [deliveryId] and [type] are plain tokens, [dataJson] a JSON object. */
        fun payload(deliveryId: String, type: String, createdAt: Long, dataJson: String) =
            """{"id":"$deliveryId","type":"$type","createdAt":$createdAt,"data":$dataJson}"""

        /** `TeamCode-Signature: v1=<hex>`: HMAC-SHA256 of `<timestamp>.<body>` keyed with the subscription secret. */
        fun signature(secret: String, timestampSeconds: Long, body: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.encodeToByteArray(), "HmacSHA256"))
            return "v1=" + mac.doFinal("$timestampSeconds.$body".encodeToByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

/** The worker: one pass fans events out to subscriptions and attempts what is due. */
class DeliverWebhooksUseCase(private val store: WebhookStore, private val sealer: SecretSealer, private val sender: WebhookSender, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun run(): Int {
        store.fanOut(subscriptions = 50, eventsPerSubscription = 100)
        var attempted = 0
        for (due in store.claimDue(limit = 25, leaseMillis = LEASE_MILLIS)) {
            val delivery = due.delivery
            val secret = sealer.open(due.sealedSecret, WebhookService.context(delivery.subscriptionId))?.decodeToString()
            val now = clock()
            val result = if (secret == null) SendResult(null, "secret_unreadable") else {
                val seconds = now / 1000
                sender.send(due.url, mapOf(
                    "Content-Type" to "application/json", "User-Agent" to "TeamCode-Webhooks/1",
                    "TeamCode-Delivery" to delivery.id, "TeamCode-Event" to delivery.eventType, "TeamCode-Timestamp" to seconds.toString(),
                    "TeamCode-Signature" to WebhookService.signature(secret, seconds, delivery.payload),
                ), delivery.payload)
            }
            attempted++
            val attempts = delivery.attempts + 1
            when {
                result.delivered -> store.record(delivery.id, DeliveryState.DELIVERED, attempts, delivery.nextAttemptAt, result, clock())
                // A test is sent once; a refused or exhausted delivery is final.
                delivery.test || !result.retryable || attempts >= WebhookService.MAX_ATTEMPTS ->
                    store.record(delivery.id, DeliveryState.FAILED, attempts, delivery.nextAttemptAt, result, clock())
                else -> store.record(delivery.id, DeliveryState.PENDING, attempts, clock() + WebhookService.BACKOFF_MILLIS[attempts - 1], result, clock())
            }
        }
        store.disableFailing(20)
        return attempted
    }
    companion object { const val LEASE_MILLIS = 60_000L }
}
