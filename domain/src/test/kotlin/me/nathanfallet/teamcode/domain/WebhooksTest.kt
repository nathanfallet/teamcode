package me.nathanfallet.teamcode.domain

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.cloud.SecretSealer
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.webhooks.*
import kotlin.test.*

/** An in-memory store for the rules: the SQL behaviour is proven on MySQL by `WebhookPersistenceTest`. */
class MemoryWebhookStore : WebhookStore {
    val subscriptions = linkedMapOf<String, WebhookSubscription>()
    val secrets = mutableMapOf<String, String>()
    val deliveries = linkedMapOf<String, WebhookDelivery>()
    val recorded = mutableListOf<Pair<DeliveryState, SendResult>>()
    override suspend fun revalidate(actor: Principal) = Unit
    override suspend fun list(teamId: String) = subscriptions.values.filter { it.teamId == teamId }
    override suspend fun find(teamId: String, id: String) = subscriptions[id]?.takeIf { it.teamId == teamId }
    override suspend fun create(actor: Principal, subscription: WebhookSubscription, sealedSecret: String): WebhookSubscription {
        subscriptions[subscription.id] = subscription; secrets[subscription.id] = sealedSecret; return subscription
    }
    override suspend fun update(actor: Principal, id: String, expectedVersion: Int, url: String, types: Set<TeamEventType>, description: String, enabled: Boolean?): WebhookSubscription {
        val current = subscriptions.getValue(id)
        if (current.version != expectedVersion) throw TeamFailure("stale_version", 409, "Changed.")
        return current.copy(url = url, types = types, description = description, enabled = enabled ?: current.enabled, version = current.version + 1).also { subscriptions[id] = it }
    }
    override suspend fun rotateSecret(actor: Principal, id: String, sealedSecret: String) = subscriptions.getValue(id).also { secrets[id] = sealedSecret }
    override suspend fun delete(actor: Principal, id: String) { subscriptions.remove(id) }
    override suspend fun deliveries(teamId: String, id: String, limit: Int) = deliveries.values.filter { it.subscriptionId == id }.take(limit)
    override suspend fun enqueueTest(actor: Principal, id: String, payload: String): WebhookDelivery {
        val delivery = WebhookDelivery("test-1", id, actor.teamId, null, "webhook.test", payload, DeliveryState.PENDING, 0, 0, null, null, 0, null, true)
        deliveries[delivery.id] = delivery; return delivery
    }
    override suspend fun fanOut(subscriptions: Int, eventsPerSubscription: Int) = 0
    override suspend fun claimDue(limit: Int, leaseMillis: Long) = deliveries.values.filter { it.state == DeliveryState.PENDING }.map { DueDelivery(it, subscriptions.getValue(it.subscriptionId).url, secrets.getValue(it.subscriptionId)) }
    override suspend fun record(id: String, state: DeliveryState, attempts: Int, nextAttemptAt: Long, result: SendResult, now: Long) {
        deliveries[id] = deliveries.getValue(id).copy(state = state, attempts = attempts, nextAttemptAt = nextAttemptAt, lastStatus = result.status, lastError = result.error)
        recorded += state to result
    }
    override suspend fun disableFailing(limit: Int) = 0
    override suspend fun maintain(limit: Int) = 0
}

class WebhooksTest {
    private val sealer = object : SecretSealer {
        override fun seal(plaintext: ByteArray, context: String) = "sealed:$context:" + plaintext.decodeToString()
        override fun open(envelope: String, context: String) = envelope.removePrefix("sealed:$context:").takeIf { envelope.startsWith("sealed:$context:") }?.encodeToByteArray()
    }
    private val admin = Member("admin", "admin", Role.ADMIN, false, false)
    private val member = Member("member", "member", Role.MEMBER, false, false)
    private fun principal(who: Member, scopes: Set<ServiceScope>? = null) = Principal(who, "token", "team", scopes)
    private val types = setOf(TeamEventType.CLOUD_TURN, TeamEventType.RUN_STALE)

    @Test fun signatureIsAnHmacOfTimestampAndBody() {
        assertEquals("v1=7aa818774b12d07b487b77188a423aa42da8d52da13f1fface316ecdf0a91f53", WebhookService.signature("whsec_test", 1_700_000_000, """{"id":"d1"}"""))
        assertNotEquals(WebhookService.signature("whsec_test", 1_700_000_000, "a"), WebhookService.signature("whsec_test", 1_700_000_001, "a"))
    }

    @Test fun onlyAdministratorsAndTheirCloudAdminKeysManage() = runBlocking {
        val service = WebhookService(MemoryWebhookStore(), sealer)
        val url = "https://hooks.example.test/teamcode"
        assertEquals("forbidden", assertFailsWith<TeamFailure> { service.create(principal(member), url, types, "") }.code)
        assertEquals("forbidden", assertFailsWith<TeamFailure> { service.list(principal(member)) }.code)
        assertEquals("forbidden", assertFailsWith<TeamFailure> { service.create(principal(admin, setOf(ServiceScope.CLOUD_WRITE)), url, types, "") }.code, "An administrator's plain key acts as its owner, not as the team")
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { service.create(principal(admin, setOf(ServiceScope.TEAM_READ)), url, types, "") }.code, "team:read never writes")
        assertEquals("forbidden", assertFailsWith<TeamFailure> { service.list(principal(admin, setOf(ServiceScope.TEAM_READ))) }.code, "and does not manage subscriptions")
        assertEquals(1, service.create(principal(admin, setOf(ServiceScope.ADMIN_CLOUD)), url, types, "").webhook.version)
        assertEquals(1, service.create(principal(admin), url, types, "").webhook.version)
        assertEquals(2, service.list(principal(admin)).size)
    }

    @Test fun urlsAndTypesAreValidated() = runBlocking {
        val service = WebhookService(MemoryWebhookStore(), sealer)
        suspend fun refused(url: String, set: Set<TeamEventType> = types, description: String = "") =
            assertEquals("invalid_webhook", assertFailsWith<TeamFailure> { service.create(principal(admin), url, set, description) }.code, url)
        refused("http://hooks.example.test/x")
        refused("https://user:pass@hooks.example.test/x")
        refused("https://hooks.example.test/x#frag")
        refused("https://hooks.example.test/a b")
        refused("https://169.254.169.254/latest")
        refused("https://metadata.google.internal/")
        refused("https://2852039166/") // decimal spelling of 169.254.169.254
        refused("https://", types)
        refused("https://hooks.example.test/x", emptySet())
        refused("https://hooks.example.test/x", types, "x".repeat(201))
        refused("https://hooks.example.test/" + "a".repeat(2_100))
    }

    /** Audit L1: an administrator's key cannot aim webhooks at the server's own network unless the operator allows it. */
    @Test fun privateTargetsNeedTheOperatorsOptIn() = runBlocking {
        val service = WebhookService(MemoryWebhookStore(), sealer)
        val private = listOf("https://10.1.2.3:8443/hook", "https://127.0.0.1/hook", "https://localhost/hook", "https://api.localhost/hook",
            "https://[::1]/hook", "https://[fd12::1]/hook", "https://192.168.1.1/hook", "https://172.16.0.1/hook", "https://100.64.0.1/hook",
            "https://0x7f000001/hook", "https://2130706433/hook", "https://127.1/hook", "https://[::ffff:10.0.0.1]/hook", "https://[64:ff9b::a00:1]/hook")
        for (url in private)
            assertEquals("webhook_host_not_allowed", assertFailsWith<TeamFailure> { service.create(principal(admin, setOf(ServiceScope.ADMIN_CLOUD)), url, types, "") }.code, url)
        service.create(principal(admin), "https://93.184.215.14/hook", types, "a public literal")
        service.create(principal(admin), "https://hooks.example.test/hook", types, "a name, checked when resolved")
        val created = service.create(principal(admin), "https://hooks.example.test/hook", types, "")
        assertEquals("webhook_host_not_allowed", assertFailsWith<TeamFailure> { service.update(principal(admin), created.webhook.id, 1, "https://10.0.0.5/x", types, "", null) }.code)

        val internal = WebhookService(MemoryWebhookStore(), sealer, allowPrivateTargets = true)
        for (url in private) internal.create(principal(admin), url, types, "allowed by the operator")
        assertEquals("invalid_webhook", assertFailsWith<TeamFailure> { internal.create(principal(admin), "https://169.254.169.254/latest", types, "") }.code, "Metadata stays refused")
    }

    @Test fun theSecretIsShownOnceAndStoredSealed() = runBlocking {
        val store = MemoryWebhookStore()
        val service = WebhookService(store, sealer, random = { ByteArray(32) { 7 } })
        val created = service.create(principal(admin), "https://hooks.example.test/x", types, "")
        assertTrue(created.secret.startsWith("whsec_") && created.secret.length > 40)
        assertFalse("REDACTED" !in created.toString(), "toString hides the secret")
        assertEquals("sealed:webhook:${created.webhook.id}:${created.secret}", store.secrets.getValue(created.webhook.id))
        val rotated = WebhookService(store, sealer, random = { ByteArray(32) { 9 } }).rotateSecret(principal(admin), created.webhook.id)
        assertNotEquals(created.secret, rotated.secret)
        assertEquals(1, service.list(principal(admin)).size)
        assertFalse(service.list(principal(admin)).first().toString().contains(rotated.secret))
    }

    private class Recorder(val answers: MutableList<SendResult>) : WebhookSender {
        val calls = mutableListOf<Triple<String, Map<String, String>, String>>()
        override suspend fun send(url: String, headers: Map<String, String>, body: String): SendResult { calls += Triple(url, headers, body); return answers.removeFirst() }
    }

    private fun queued(store: MemoryWebhookStore, test: Boolean = false, attempts: Int = 0): WebhookDelivery {
        val sub = WebhookSubscription("sub", "team", "https://hooks.example.test/x", types, "", true, null, "admin", null, 0, 0, 1, 0, 0)
        store.subscriptions["sub"] = sub; store.secrets["sub"] = sealer.seal("whsec_s3cret".encodeToByteArray(), WebhookService.context("sub"))
        return WebhookDelivery("d1", "sub", "team", if (test) null else 5, "cloud.turn", """{"id":"d1"}""", DeliveryState.PENDING, attempts, 0, null, null, 0, null, test).also { store.deliveries["d1"] = it }
    }

    @Test fun aDeliveryIsSignedAndMarkedDelivered() = runBlocking {
        val store = MemoryWebhookStore(); queued(store)
        val sender = Recorder(mutableListOf(SendResult(204, null)))
        DeliverWebhooksUseCase(store, sealer, sender, clock = { 1_700_000_000_500 }).run()
        val (url, headers, body) = sender.calls.single()
        assertEquals("https://hooks.example.test/x", url); assertEquals("""{"id":"d1"}""", body)
        assertEquals("1700000000", headers["TeamCode-Timestamp"]); assertEquals("d1", headers["TeamCode-Delivery"]); assertEquals("cloud.turn", headers["TeamCode-Event"])
        assertEquals(WebhookService.signature("whsec_s3cret", 1_700_000_000, body), headers["TeamCode-Signature"])
        assertEquals(DeliveryState.DELIVERED, store.deliveries.getValue("d1").state)
    }

    @Test fun failuresRetryWithBackoffThenGiveUp() = runBlocking {
        val store = MemoryWebhookStore(); queued(store)
        var now = 1_000_000L
        val sender = Recorder(MutableList(20) { SendResult(503, null) })
        val worker = DeliverWebhooksUseCase(store, sealer, sender, clock = { now })
        for (attempt in 1 until WebhookService.MAX_ATTEMPTS) {
            worker.run()
            val d = store.deliveries.getValue("d1")
            assertEquals(DeliveryState.PENDING, d.state); assertEquals(attempt, d.attempts)
            assertEquals(now + WebhookService.BACKOFF_MILLIS[attempt - 1], d.nextAttemptAt, "attempt $attempt waits ${WebhookService.BACKOFF_MILLIS[attempt - 1]} ms")
            now = d.nextAttemptAt
        }
        worker.run()
        assertEquals(DeliveryState.FAILED, store.deliveries.getValue("d1").state); assertEquals(WebhookService.MAX_ATTEMPTS, store.deliveries.getValue("d1").attempts)
        assertEquals(WebhookService.MAX_ATTEMPTS, sender.calls.size)
        assertEquals(WebhookService.MAX_ATTEMPTS - 1, WebhookService.BACKOFF_MILLIS.size)
    }

    @Test fun refusalsAndTestsAreFinalButTimeoutsAndRateLimitsRetry() = runBlocking {
        for ((result, final) in listOf(SendResult(400, null) to true, SendResult(404, null) to true, SendResult(301, null) to true, SendResult(null, "host_not_allowed") to false,
            SendResult(null, "timeout") to false, SendResult(429, null) to false, SendResult(408, null) to false, SendResult(500, null) to false)) {
            val store = MemoryWebhookStore(); queued(store)
            DeliverWebhooksUseCase(store, sealer, Recorder(mutableListOf(result))).run()
            val expected = if (final) DeliveryState.FAILED else DeliveryState.PENDING
            // A host the sender refuses is retryable by the rules (DNS may change); it is bounded by the attempts and 24 h.
            assertEquals(expected, store.deliveries.getValue("d1").state, "$result")
        }
        val store = MemoryWebhookStore(); queued(store, test = true)
        DeliverWebhooksUseCase(store, sealer, Recorder(mutableListOf(SendResult(500, null)))).run()
        assertEquals(DeliveryState.FAILED, store.deliveries.getValue("d1").state, "A test is sent once")
    }

    @Test fun anUnreadableSecretIsRecordedNotSent() = runBlocking {
        val store = MemoryWebhookStore(); queued(store); store.secrets["sub"] = "garbage"
        val sender = Recorder(mutableListOf())
        DeliverWebhooksUseCase(store, sealer, sender).run()
        assertTrue(sender.calls.isEmpty()); assertEquals("secret_unreadable", store.recorded.single().second.error)
    }

    @Test fun theTestButtonQueuesASignedTestEvent() = runBlocking {
        val store = MemoryWebhookStore(); val service = WebhookService(store, sealer)
        val hook = service.create(principal(admin), "https://hooks.example.test/x", types, "").webhook
        val test = service.test(principal(admin), hook.id)
        assertTrue(test.test && test.eventType == "webhook.test" && """"type":"webhook.test"""" in test.payload && "test delivery" in test.payload)
        assertEquals("forbidden", assertFailsWith<TeamFailure> { service.test(principal(member), hook.id) }.code)
    }
}
