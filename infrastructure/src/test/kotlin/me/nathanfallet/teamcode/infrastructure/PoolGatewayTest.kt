package me.nathanfallet.teamcode.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.policy.*
import me.nathanfallet.teamcode.infrastructure.fixtures.FixtureGatewayService
import me.nathanfallet.teamcode.infrastructure.gateway.*
import me.nathanfallet.teamcode.infrastructure.providers.ClaudeTransport
import me.nathanfallet.teamcode.infrastructure.providers.CodexTransport
import kotlin.test.*
import me.nathanfallet.teamcode.domain.routing.*
import kotlinx.serialization.json.*
import io.ktor.http.content.TextContent

class PoolGatewayTest {
    @Test fun providerCorrelationIsCapturedBeforeStreamingWithoutBeingExposedToTheCaller() = runBlocking {
        val store = FakeAdmission(account)
        HttpClient(MockEngine { respond(FixtureGatewayService.EVENTS.joinToString(""), headers = headersOf(
            HttpHeaders.ContentType to listOf("text/event-stream"), "request-id" to listOf("req_fixture_001"))) }) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http)), FakeModelPolicies())
            gateway.forward(request()) { head, chunks ->
                assertEquals(listOf("req_fixture_001"), store.providerRequests.map { it.second })
                assertFalse(head.headers.values.contains("req_fixture_001"))
                chunks.collect { }
            }
            assertTrue(store.didSettle)
        }
    }
    @Test fun everyRefusalIsLoggedWithItsCodeMemberAndReasonOnly() {
        val full = TeamFailure("queue_full", 503, "The TeamCode admission queue is full.\nRetry \"shortly\".", retryAfterSeconds = 15)
        assertEquals("TeamCode inference refused member=m-1 code=queue_full status=503 retryAfter=15 reason=\"The TeamCode admission queue is full. Retry 'shortly'.\"",
            refusalLog("m-1", full))
        assertEquals("TeamCode inference refused member=anonymous code=authentication_required status=401 reason=\"Sign in.\"",
            refusalLog(null, TeamFailure("authentication_required", 401, "Sign in.")))
        assertEquals("TeamCode inference refused member=m-1 code=unsupported_model status=400 reason=\"bad  code=forged\"",
            refusalLog("m-1", TeamFailure("unsupported_model", 400, "bad\r\u001bcode=forged")))
    }
    @Test fun transportCorrelationRejectsConflictsDuplicatesUnsafeAndOversizedValues() {
        HttpClient(MockEngine { error("No network request expected") }).use { http ->
            for (transport in listOf(ClaudeTransport(http), CodexTransport(http))) {
                assertEquals("req_fixture", transport.requestId(headersOf("request-id", "req_fixture")))
                assertEquals("req_fixture", transport.requestId(headersOf("x-request-id", "req_fixture")))
                assertNull(transport.requestId(headersOf("request-id" to listOf("req_one"), "x-request-id" to listOf("req_two"))))
                assertNull(transport.requestId(headersOf("request-id", listOf("req_one", "req_two"))))
                for (bad in listOf("https://untrusted.example/secret", "bad identifier", "x".repeat(129)))
                    assertNull(transport.requestId(headersOf("request-id", bad)))
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun temporaryAdmissionPressureWaitsAndKeepsWorkloadOutOfProviderHeaders() = runTest {
        val store = FakeAdmission(account)
        var polls = 0
        var dispatched = 0
        val queue = object : AdmissionQueueStore {
            override suspend fun enqueue(actor: Principal, input: AdmissionInput): AdmissionTicket {
                assertEquals(Workload.BACKGROUND, input.workload)
                assertEquals("claude-opus-test", input.context?.requestedModel)
                return AdmissionTicket("pending")
            }
            override suspend fun reserveQueued(actor: Principal, ticket: AdmissionTicket, input: AdmissionInput): Reservation {
                if (++polls < 3) throw TeamFailure("member_concurrency", 429, "Temporarily occupied")
                return store.reserve(actor, input.units, input.model, input.provider, input.excludedAccountIds, input.affinity)
            }
            override suspend fun renew(actor: Principal, ticket: AdmissionTicket, blocked: Boolean) { assertTrue(blocked) }
            override suspend fun release(actor: Principal, ticket: AdmissionTicket) = Unit
        }
        HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = UnconfinedTestDispatcher(testScheduler)
            addHandler { req ->
            dispatched++
            assertEquals(3, polls)
            assertNull(req.headers["x-teamcode-workload"])
            respond(FixtureGatewayService.EVENTS.joinToString(""), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        })) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http)), FakeModelPolicies(), queued = AwaitAdmissionUseCase(queue))
            gateway.forward(request().copy(headers = mapOf("x-teamcode-workload" to "background"))) { head, chunks ->
                assertEquals(200, head.status); chunks.collect { }
            }
            assertEquals(1, dispatched)
            assertEquals("invalid_workload", assertFailsWith<TeamFailure> {
                gateway.forward(request().copy(headers = mapOf("x-teamcode-workload" to "urgent"))) { _, _ -> fail("No dispatch") }
            }.code)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun boundedQueueTimeoutTriesConfiguredFallbackInsteadOfClaimingExhaustion() = runTest {
        val store = FakeAdmission(account)
        var released = 0
        val queue = object : AdmissionQueueStore {
            override suspend fun enqueue(actor: Principal, input: AdmissionInput) = AdmissionTicket(input.model)
            override suspend fun reserveQueued(actor: Principal, ticket: AdmissionTicket, input: AdmissionInput): Reservation {
                if (input.model == "claude-opus-test") throw TeamFailure("queue_turn", 429, "Not your turn")
                return store.reserve(actor, input.units, input.model, input.provider, input.excludedAccountIds, input.affinity)
            }
            override suspend fun renew(actor: Principal, ticket: AdmissionTicket, blocked: Boolean) { assertFalse(blocked) }
            override suspend fun release(actor: Principal, ticket: AdmissionTicket) { released++ }
        }
        HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = UnconfinedTestDispatcher(testScheduler)
            addHandler { req ->
            assertEquals("claude-haiku-test", Json.parseToJsonElement((req.body as TextContent).text).jsonObject["model"]!!.jsonPrimitive.content)
            respond(FixtureGatewayService.EVENTS.joinToString(""), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        })) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http)), FakeModelPolicies(),
                routes(RouteTarget("anthropic"), RouteTarget("anthropic", "claude-haiku-test")), queued = AwaitAdmissionUseCase(queue))
            gateway.forward(request()) { head, chunks -> assertEquals(200, head.status); chunks.collect { } }
            assertEquals(1, released)
        }
    }
    @Test fun claudeCodeOutputCeilingPassesThroughWithTheReservationHoldItAlwaysHad() = runBlocking {
        // Claude Code 2.1 asks for max_tokens 128000 on every Messages request; the provider validates the model's
        // own ceiling. TeamCode only bounds the field, and keeps the admission hold at its former 65536-token cap.
        var forwarded: JsonElement? = null
        HttpClient(MockEngine { req ->
            forwarded = Json.parseToJsonElement((req.body as TextContent).text).jsonObject["max_tokens"]
            respond(FixtureGatewayService.EVENTS.joinToString(""), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { install(HttpTimeout) }.use { http ->
            val store = FakeAdmission(account)
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http)), FakeModelPolicies())
            fun body(maximum: Long) = GenerationRequest("""{"model":"claude-opus-test","stream":true,"max_tokens":$maximum,"messages":[{"role":"user","content":"Hello"}]}""".toByteArray(), emptyMap(), actor)
            gateway.forward(body(128_000)) { head, chunks -> assertEquals(200, head.status); chunks.collect { } }
            assertEquals(JsonPrimitive(128_000), forwarded)
            gateway.forward(body(100_000)) { _, chunks -> chunks.collect { } }
            assertEquals(1, store.reservedUnits.distinct().size, "Above 65536 the hold no longer grows: ${store.reservedUnits}")
            for (invalid in listOf(0L, -1L, 1_048_577L))
                assertEquals("invalid_max_tokens", assertFailsWith<TeamFailure> { gateway.forward(body(invalid)) { _, _ -> fail("No dispatch") } }.code)
        }
    }
    private val actor = Principal(Member("member", "alice", Role.MEMBER, false, false), "token", "team")
    private val account = AccountRecord("account", AccountIdentity("team", "anthropic", "provider-id", "org-id"), "Max", 1, false, null, 0, AccountState.READY)
    private fun request() = GenerationRequest("""{"model":"claude-opus-test","stream":true,"max_tokens":100,"messages":[{"role":"user","content":"Hello"}]}""".toByteArray(), emptyMap(), actor)
    @Test fun translationIsOffByDefaultInBothDirectionsWithoutReservationOrProviderTraffic() = runBlocking {
        val policy = object : ModelPolicyStore {
            override suspend fun get(teamId: String) = ModelPolicy()
            override suspend fun save(actor: Principal, policy: ModelPolicy) = policy
        }
        HttpClient(MockEngine { fail("Disabled translation must not reach a provider") }) { install(HttpTimeout) }.use { http ->
            for (protocol in WireProtocol.entries) {
                val store = FakeAdmission(account)
                val target = if (protocol == WireProtocol.MESSAGES) RouteTarget("codex", "gpt-test") else RouteTarget("anthropic", "claude-test")
                val body = if (protocol == WireProtocol.MESSAGES) request() else GenerationRequest(
                    """{"model":"gpt-test","input":"Hello","stream":true}""".toByteArray(), emptyMap(), actor, protocol)
                val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http), CodexTransport(http)), policy, routes(target))
                val error = assertFailsWith<TeamFailure> { gateway.forward(body) { _, _ -> fail("No response expected") } }
                assertEquals("translation_disabled", error.code); assertEquals(422, error.status)
                assertNull(store.selectedProvider); assertTrue(store.charges.isEmpty())
            }
        }
    }
    @Test fun disablingTranslationSkipsAnExistingCrossProviderAffinityAndRetainsNativeFallback() = runBlocking {
        val store = FakeAdmission(account)
        val policy = FakeModelPolicies()
        val seen = mutableListOf<String>()
        val responses = "data: {\"type\":\"response.created\",\"response\":{\"id\":\"test\"}}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
        HttpClient(MockEngine { req ->
            seen += req.url.host
            respond(if (req.url.host == "chatgpt.com") responses else FixtureGatewayService.EVENTS.joinToString(""), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http), CodexTransport(http)), policy,
                routes(RouteTarget("codex", "gpt-test"), RouteTarget("anthropic")))
            val tagged = request().copy(headers = mapOf("x-claude-code-session-id" to "flag-session"))
            gateway.forward(tagged) { _, chunks -> chunks.collect {} }
            assertEquals("codex", store.affinities.values.single().provider)
            policy.translation = false
            gateway.forward(tagged) { _, chunks -> chunks.collect {} }
            assertEquals(listOf("chatgpt.com", "api.anthropic.com"), seen)
            assertEquals("anthropic", store.affinities.values.single().provider)
        }
    }
    @Test fun streamsExactlyOnceAndSettlesAllUsageCategories() = runBlocking {
        val store = FakeAdmission(account)
        var calls = 0
        val events = FixtureGatewayService.EVENTS.joinToString("").replace("\"input_tokens\":1", "\"input_tokens\":10,\"cache_read_input_tokens\":20,\"cache_creation_input_tokens\":30")
        val http = HttpClient(MockEngine { req ->
            calls++
            assertEquals("Bearer provider-secret", req.headers[HttpHeaders.Authorization])
            assertTrue(req.headers["anthropic-beta"]!!.contains("oauth-2025-04-20"))
            respond(events, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { install(HttpTimeout) }
        http.use {
            val source = object : CredentialSource { override suspend fun fresh(account: AccountRecord, rejectedAccessToken: String?) = ProviderCredentials("provider-secret", "refresh-secret", Long.MAX_VALUE) }
            val gateway = PoolGateway(store, source, listOf(ClaudeTransport(it)), FakeModelPolicies())
            val output = StringBuilder()
            gateway.forward(request()) { head, chunks -> assertEquals(200, head.status); chunks.collect { output.append(it.decodeToString()) } }
            assertEquals(events, output.toString())
            assertEquals(1, calls)
            assertEquals(65, store.settled)
            assertEquals(0, gateway.status().active)
        }
    }
    @Test fun committedDisconnectIsNeverReplayedAndKeepsUnknownReservation() = runBlocking {
        val store = FakeAdmission(account)
        var calls = 0
        val http = HttpClient(MockEngine { calls++; respond(FixtureGatewayService.EVENTS.first(), headers = headersOf(HttpHeaders.ContentType, "text/event-stream")) }) { install(HttpTimeout) }
        http.use {
            val source = object : CredentialSource { override suspend fun fresh(account: AccountRecord, rejectedAccessToken: String?) = ProviderCredentials("provider-secret", "refresh-secret", Long.MAX_VALUE) }
            val gateway = PoolGateway(store, source, listOf(ClaudeTransport(it)), FakeModelPolicies())
            val cancelled = assertFailsWith<CancellationException> { gateway.forward(request()) { _, chunks -> chunks.collect { throw CancellationException("downstream disconnected") } } }
            assertEquals("downstream disconnected", cancelled.message)
            assertEquals(1, calls)
            assertTrue(store.didSettle)
            assertNull(store.settled)
            assertEquals(listOf(false), store.settlementCompletions)
            assertEquals(0, gateway.status().active)
        }
    }
    @Test fun observedTerminalUsageCountsWhenTheClientClosesBeforeHttpEofWithoutReplaying() = runBlocking {
        for (protocol in WireProtocol.entries) {
            val provider = if (protocol == WireProtocol.MESSAGES) "anthropic" else "codex"
            val store = FakeAdmission(account.copy(identity = account.identity.copy(provider = provider)))
            var calls = 0
            val events = if (protocol == WireProtocol.MESSAGES) FixtureGatewayService.EVENTS.joinToString("") else
                "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
            HttpClient(MockEngine {
                calls++; respond(events, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }) { install(HttpTimeout) }.use { http ->
                val transport = if (protocol == WireProtocol.MESSAGES) ClaudeTransport(http) else CodexTransport(http)
                val body = if (protocol == WireProtocol.MESSAGES) request() else GenerationRequest(
                    """{"model":"gpt-test","stream":true,"input":"synthetic"}""".toByteArray(), emptyMap(), actor, protocol)
                val gateway = PoolGateway(store, credentials, listOf(transport), FakeModelPolicies())
                val closed = assertFailsWith<CancellationException> {
                    gateway.forward(body) { _, chunks -> chunks.collect { throw CancellationException("client closed after terminal event") } }
                }
                assertEquals("client closed after terminal event", closed.message)
                assertEquals(1, calls)
                assertEquals(6, store.settled)
                assertEquals(listOf(true), store.settlementCompletions)
            }
        }
    }
    @Test fun providerRejectionDoesNotExposeCredentialsOrBurnAllowance() = runBlocking {
        val store = FakeAdmission(account)
        val http = HttpClient(MockEngine { respond("provider-secret", HttpStatusCode.TooManyRequests, headersOf("retry-after", "10")) }) { install(HttpTimeout) }
        http.use {
            val source = object : CredentialSource { override suspend fun fresh(account: AccountRecord, rejectedAccessToken: String?) = ProviderCredentials("provider-secret", "refresh-secret", Long.MAX_VALUE) }
            val gateway = PoolGateway(store, source, listOf(ClaudeTransport(it)), FakeModelPolicies())
            val error = assertFailsWith<TeamFailure> { gateway.forward(request()) { _, _ -> fail("Rejected attempts must not commit output") } }
            assertEquals(429, error.status)
            assertFalse(error.message.orEmpty().contains("provider-secret"))
            assertEquals(1, store.charges.size)
            assertTrue(store.charges.all { it == 0L })
            assertEquals(0, store.settled)
            assertTrue(store.cooled)
        }
    }
    @Test fun codexStreamWithoutContentTypeUsesResponsesContract() = runBlocking {
        val codex = account.copy(identity = account.identity.copy(provider = "codex"))
        val store = FakeAdmission(codex)
        val events = """data: {"type":"response.completed","response":{"usage":{"input_tokens":10,"output_tokens":2}}}""" + "\n\n"
        val http = HttpClient(MockEngine { respond(events, headers = Headers.Empty) }) { install(HttpTimeout) }
        http.use {
            val source = object : CredentialSource { override suspend fun fresh(account: AccountRecord, rejectedAccessToken: String?) = ProviderCredentials("provider-secret", "refresh-secret", Long.MAX_VALUE) }
            val gateway = PoolGateway(store, source, listOf(CodexTransport(it)), FakeModelPolicies())
            val request = GenerationRequest("""{"model":"gpt-6-luna","stream":true,"instructions":"brief","input":[]}""".toByteArray(), emptyMap(), actor, WireProtocol.RESPONSES)
            val output = StringBuilder()
            gateway.forward(request) { head, chunks ->
                assertEquals("text/event-stream", head.contentType)
                chunks.collect { output.append(it.decodeToString()) }
            }
            assertEquals(events, output.toString())
            assertEquals(20, store.settled)
            assertEquals("codex", store.selectedProvider)
        }
    }
    private fun routes(vararg targets: RouteTarget) = object : RoutingStore {
        override suspend fun list(teamId: String) = listOf(RouteRule("test", "*", targets.toList()))
        override suspend fun save(actor: Principal, rule: RouteRule) = error("unused")
        override suspend fun delete(actor: Principal, id: String, version: Long) = error("unused")
    }
    private val credentials = object : CredentialSource {
        override suspend fun fresh(account: AccountRecord, rejectedAccessToken: String?) = ProviderCredentials("provider-secret", "refresh-secret", Long.MAX_VALUE)
    }
    @Test fun definiteRejectionFallsBackAndTranslatesWithoutLosingToolConversation() = runBlocking {
        val store = FakeAdmission(account)
        val calls = mutableListOf<String>()
        val events = listOf(
            """{"type":"response.created","response":{"id":"resp_1"}}""",
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"call_2","name":"calculate"}}""",
            """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"x\":3}"}""",
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","call_id":"call_2","name":"calculate","arguments":"{\"x\":3}"}}""",
            """{"type":"response.completed","response":{"usage":{"input_tokens":10,"output_tokens":2}}}""",
        ).joinToString("") { "data: $it\n\n" }
        val http = HttpClient(MockEngine { req ->
            calls += req.url.host
            if (req.url.host == "api.anthropic.com") respond("rate limited", HttpStatusCode.TooManyRequests)
            else {
                val body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
                assertEquals("gpt-6-astra", body["model"]!!.jsonPrimitive.content)
                assertEquals("function_call_output", body["input"]!!.jsonArray.last().jsonObject["type"]!!.jsonPrimitive.content)
                assertEquals("call_1", body["input"]!!.jsonArray.last().jsonObject["call_id"]!!.jsonPrimitive.content)
                respond(events, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        }) { install(HttpTimeout) }
        http.use {
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(it), CodexTransport(it)), FakeModelPolicies(), routes(RouteTarget("anthropic"), RouteTarget("codex", "gpt-6-astra")))
            val body = """{"model":"claude-opus-test","stream":true,"max_tokens":100,"messages":[{"role":"user","content":"calculate"},{"role":"assistant","content":[{"type":"tool_use","id":"call_1","name":"calculate","input":{"x":2}}]},{"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"4"}]}],"tools":[{"name":"calculate","input_schema":{"type":"object"}}]}"""
            val output = StringBuilder()
            gateway.forward(request().copy(body = body.toByteArray())) { head, chunks ->
                assertEquals("codex", head.headers["x-teamcode-provider"])
                chunks.collect { output.append(it.decodeToString()) }
            }
            assertEquals(2, calls.size)
            assertTrue(output.contains("tool_use"))
            assertTrue(output.contains("call_2"))
            assertTrue(output.contains("message_stop"))
            assertEquals<List<Long?>>(listOf(0L, 20L), store.charges)
        }
    }
    @Test fun exhaustsPreferredPoolBeforeCrossProviderFallbackWithoutLeakingUpstream429() = runBlocking {
        val store = FakeAdmission(account, accountsPerProvider = 7)
        var calls = 0
        HttpClient(MockEngine { req ->
            calls++
            assertEquals("api.anthropic.com", req.url.host, "A preferred account remains; do not switch providers yet")
            if (calls < 7) respond("rate limited", HttpStatusCode.TooManyRequests)
            else respond(FixtureGatewayService.EVENTS.joinToString(""), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http), CodexTransport(http)), FakeModelPolicies(), routes(RouteTarget("anthropic"), RouteTarget("codex", "gpt-6-astra")))
            var responses = 0
            gateway.forward(request()) { head, chunks -> responses++; assertEquals(200, head.status); chunks.collect {} }
            assertEquals(1, responses)
            assertEquals(7, calls)
            assertEquals(List(6) { 0L }, store.charges.take(6))
            assertEquals(6L, store.charges.last())
        }
    }

    @Test fun modelScopedRejectionAllowsAnotherModelOnTheSameAccount() = runBlocking {
        val store = FakeAdmission(account)
        val seen = mutableListOf<String>()
        HttpClient(MockEngine { req ->
            val model = Json.parseToJsonElement((req.body as TextContent).text).jsonObject["model"]!!.jsonPrimitive.content
            seen += model
            if (model == "gpt-6-astra") respond("limited", HttpStatusCode.TooManyRequests, headersOf(
                "x-codex-base_model_inference-limit-name" to listOf("A display label"),
                "x-codex-active-limit" to listOf("base_model_inference"),
                "x-codex-base_model_inference-secondary-used-percent" to listOf("100"),
                "x-codex-base_model_inference-secondary-window-minutes" to listOf("10080"),
                "x-codex-base_model_inference-secondary-reset-at" to listOf((System.currentTimeMillis() / 1000 + 86400).toString()),
            )) else respond("data: {\"type\":\"response.created\",\"response\":{\"id\":\"test\"}}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, credentials, listOf(CodexTransport(http)), FakeModelPolicies(), routes(RouteTarget("codex", "gpt-6-astra"), RouteTarget("codex", "gpt-6-sol")))
            gateway.forward(request()) { head, chunks -> assertEquals(200, head.status); chunks.collect {} }
            assertEquals(listOf("gpt-6-astra", "gpt-6-sol"), seen)
            assertFalse(store.cooled, "A model-scoped rejection must not cool the entire account")
            assertEquals("codex-0", store.observations.first().first)
            assertEquals(ModelLimitObservation("gpt-6-astra", "base_model_inference"), store.observations.first().second.modelLimit,
                "The mapping must use the resolved target model, not the incoming Claude model")
            assertEquals<List<Long?>>(listOf(0L, 6L), store.charges)
        }
    }
    @Test fun persistedExhaustionPreventsAccountCooldownWhenRejectionHeadersOmitUsage() = runBlocking {
        val store = FakeAdmission(account, knownExhaustedModels = setOf("gpt-6-astra"))
        var calls = 0
        HttpClient(MockEngine {
            calls++
            if (calls == 1) respond("limited", HttpStatusCode.TooManyRequests)
            else respond("data: {\"type\":\"response.created\",\"response\":{\"id\":\"test\"}}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, credentials, listOf(CodexTransport(http)), FakeModelPolicies(), routes(RouteTarget("codex", "gpt-6-astra"), RouteTarget("codex", "gpt-6-sol")))
            gateway.forward(request()) { head, chunks -> assertEquals(200, head.status); chunks.collect {} }
            assertEquals(2, calls)
            assertFalse(store.cooled)
        }
    }
    @Test fun forbiddenResponseUsesTemporaryCooldownWithoutRevokingCredentials() = runBlocking {
        val store = FakeAdmission(account)
        HttpClient(MockEngine { respond("forbidden", HttpStatusCode.Forbidden) }) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http)), FakeModelPolicies())
            assertFailsWith<TeamFailure> { gateway.forward(request()) { _, _ -> fail() } }
            assertTrue(store.cooled)
            assertFalse(store.reconnect)
        }
    }
    @Test fun rejectedUnexpiredTokenRefreshesOnceBeforeReplayingTheRejectedRequest() = runBlocking {
        val store = FakeAdmission(account)
        val tokens = mutableListOf<String>()
        val rejectedTokens = mutableListOf<String?>()
        val source = object : CredentialSource {
            override suspend fun fresh(account: AccountRecord, rejectedAccessToken: String?): ProviderCredentials {
                rejectedTokens += rejectedAccessToken
                return ProviderCredentials(if (rejectedAccessToken == null) "old-token" else "new-token", "refresh", Long.MAX_VALUE)
            }
        }
        HttpClient(MockEngine { req ->
            tokens += req.headers[HttpHeaders.Authorization]!!
            if (tokens.size == 1) respond("revoked", HttpStatusCode.Unauthorized)
            else respond(FixtureGatewayService.EVENTS.joinToString(""), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, source, listOf(ClaudeTransport(http)), FakeModelPolicies())
            gateway.forward(request()) { head, chunks -> assertEquals(200, head.status); chunks.collect {} }
            assertEquals(listOf(null, "old-token"), rejectedTokens)
            assertEquals(listOf("Bearer old-token", "Bearer new-token"), tokens)
            assertFalse(store.cooled)
            assertEquals<List<Long?>>(listOf(6L), store.charges)
        }
    }
    @Test fun repeatedUnauthorizedIsBoundedAndAmbiguousRetryKeepsItsReservation() = runBlocking {
        for (second in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.InternalServerError)) {
            val store = FakeAdmission(account)
            var calls = 0
            HttpClient(MockEngine { calls++; respond("rejected", if (calls == 1) HttpStatusCode.Unauthorized else second) }) { install(HttpTimeout) }.use { http ->
                val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http)), FakeModelPolicies())
                if (second == HttpStatusCode.Unauthorized) {
                    assertFailsWith<TeamFailure> { gateway.forward(request()) { _, _ -> fail() } }
                    assertEquals(0L, store.settled)
                    assertTrue(store.reconnect)
                } else {
                    gateway.forward(request()) { head, chunks -> assertEquals(502, head.status); chunks.collect {} }
                    assertNull(store.settled, "A rejected first attempt must not refund a later ambiguous attempt")
                }
                assertEquals(2, calls)
            }
        }
    }
    @Test fun successfulFallbackRemainsStableUntilTheConfiguredRouteChanges() = runBlocking {
        val store = FakeAdmission(account)
        val seen = mutableListOf<String>()
        val responses = "data: {\"type\":\"response.created\",\"response\":{\"id\":\"test\"}}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
        HttpClient(MockEngine { req ->
            seen += req.url.host
            if (seen.size == 1) respond("exhausted", HttpStatusCode.TooManyRequests)
            else respond(if (req.url.host == "chatgpt.com") responses else FixtureGatewayService.EVENTS.joinToString(""), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http), CodexTransport(http)), FakeModelPolicies(), routes(RouteTarget("anthropic"), RouteTarget("codex", "gpt-6-astra")))
            val tagged = request().copy(headers = mapOf("x-claude-code-session-id" to "parent-session"))
            gateway.forward(tagged) { _, chunks -> chunks.collect {} }
            gateway.forward(tagged) { _, chunks -> chunks.collect {} }
            assertEquals(listOf("api.anthropic.com", "chatgpt.com", "chatgpt.com"), seen)
            // A later native-only option must not be stranded behind an incompatible saved target.
            val nativeOnlyBody = Json.parseToJsonElement(tagged.body.decodeToString()).jsonObject
            val nativeOnly = tagged.copy(body = JsonObject(nativeOnlyBody + ("future_field" to JsonPrimitive("preserve"))).toString().toByteArray())
            gateway.forward(nativeOnly) { _, chunks -> chunks.collect {} }
            assertEquals("api.anthropic.com", seen.last())
            val incompatible = PoolGateway(store, credentials, listOf(CodexTransport(http)), FakeModelPolicies(), routes(RouteTarget("codex", "gpt-6-astra")))
            val count = seen.size
            assertEquals("translation_unsupported", assertFailsWith<TeamFailure> { incompatible.forward(nativeOnly) { _, _ -> fail() } }.code)
            assertEquals(count, seen.size)
            val reconfigured = PoolGateway(store, credentials, listOf(ClaudeTransport(http), CodexTransport(http)), FakeModelPolicies(), routes(RouteTarget("anthropic")))
            reconfigured.forward(tagged) { _, chunks -> chunks.collect {} }
            assertEquals("api.anthropic.com", seen.last())
        }
    }
    @Test fun boundedSearchIsNotMisreportedAsExhaustedCapacity() = runBlocking {
        val store = FakeAdmission(account, accountsPerProvider = 65)
        var calls = 0
        HttpClient(MockEngine { calls++; respond("rate limited", HttpStatusCode.TooManyRequests) }) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http)), FakeModelPolicies())
            val error = assertFailsWith<TeamFailure> { gateway.forward(request()) { _, _ -> fail("No provider response should be committed") } }
            assertEquals(503, error.status)
            assertEquals("routing_attempt_limit", error.code)
            assertEquals(64, calls)
            assertEquals(64, store.charges.size)
            assertTrue(store.charges.all { it == 0L })
        }
    }
    @Test fun ambiguousServerErrorDoesNotTryFallback() = runBlocking {
        val store = FakeAdmission(account)
        var calls = 0
        HttpClient(MockEngine { calls++; respond("secret diagnostic", HttpStatusCode.InternalServerError) }) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http), CodexTransport(http)), FakeModelPolicies(), routes(RouteTarget("anthropic"), RouteTarget("codex", "gpt-6-astra")))
            gateway.forward(request()) { head, chunks -> assertEquals(502, head.status); chunks.collect { assertFalse(it.decodeToString().contains("secret diagnostic")) } }
            assertEquals(1, calls)
            assertEquals<List<Long?>>(listOf(null), store.charges)
        }
    }
    @Test fun forbiddenResolvedModelCannotBypassPolicyAndNativeFieldsStayOpaque() = runBlocking {
        var calls = 0
        HttpClient(MockEngine { req ->
            calls++
            val body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
            assertEquals("preserved", body["future_field"]!!.jsonPrimitive.content)
            respond(FixtureGatewayService.EVENTS.joinToString(""), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { install(HttpTimeout) }.use { http ->
            val denied = PoolGateway(FakeAdmission(account), credentials, listOf(ClaudeTransport(http)), FakeModelPolicies(), routes(RouteTarget("anthropic", "claude-fable-test")))
            assertEquals("model_not_allowed", assertFailsWith<TeamFailure> { denied.forward(request()) { _, _ -> fail() } }.code)
            assertEquals(0, calls)
            val native = PoolGateway(FakeAdmission(account), credentials, listOf(ClaudeTransport(http)), FakeModelPolicies())
            val body = Json.parseToJsonElement(request().body.decodeToString()).jsonObject
            native.forward(request().copy(body = JsonObject(body + ("future_field" to JsonPrimitive("preserved"))).toString().toByteArray())) { _, chunks -> chunks.collect {} }
            assertEquals(1, calls)
        }
    }
    @Test fun usageMeterHandlesChunkBoundariesAndMissingFinalUsage() {
        val bytes = FixtureGatewayService.EVENTS.joinToString("").toByteArray()
        val meter = SseUsageMeter()
        bytes.forEach { meter.accept(byteArrayOf(it)) }
        assertEquals(6, meter.settledUnits)
        val missing = SseUsageMeter()
        missing.accept(FixtureGatewayService.EVENTS.first().toByteArray())
        assertNull(missing.settledUnits)
    }
    @Test fun responsesCustomToolsRoundTripThroughTheGatewayAndCallerHistory() = runBlocking {
        val store = FakeAdmission(account)
        val code = "text(\"Café 🌍\");\n"
        var calls = 0
        var alias: String? = null
        HttpClient(MockEngine { req ->
            calls++
            assertEquals("api.anthropic.com", req.url.host)
            val sent = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
            val name = sent["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content
            if (calls == 1) {
                alias = name
                val arguments = buildJsonObject { put("input", code) }.toString()
                val events = listOf(
                    Json.parseToJsonElement("""{"type":"message_start","message":{"id":"test","usage":{"input_tokens":1,"output_tokens":0}}}"""),
                    buildJsonObject { put("type", "content_block_start"); put("index", 0); putJsonObject("content_block") { put("type", "tool_use"); put("id", "call_custom"); put("name", name); put("input", JsonObject(emptyMap())) } },
                    buildJsonObject { put("type", "content_block_delta"); put("index", 0); putJsonObject("delta") { put("type", "input_json_delta"); put("partial_json", arguments) } },
                    Json.parseToJsonElement("""{"type":"content_block_stop","index":0}"""),
                    Json.parseToJsonElement("""{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":2}}"""),
                    Json.parseToJsonElement("""{"type":"message_stop"}"""),
                )
                respond(events.joinToString("") { "data: $it\n\n" }, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
                assertEquals(alias, name)
                val messages = sent["messages"]!!.jsonArray
                val previousCall = messages[1].jsonObject["content"]!!.jsonArray.single().jsonObject
                assertEquals(name, previousCall["name"]!!.jsonPrimitive.content)
                assertEquals(code, previousCall["input"]!!.jsonObject["input"]!!.jsonPrimitive.content)
                assertEquals("call_custom", messages[2].jsonObject["content"]!!.jsonArray.single().jsonObject["tool_use_id"]!!.jsonPrimitive.content)
                respond(FixtureGatewayService.EVENTS.joinToString(""), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        }) { install(HttpTimeout) }.use { http ->
            val policy = object : ModelPolicyStore {
                override suspend fun get(teamId: String) = ModelPolicy(allowedEfforts = listOf("none"), experimentalCrossProviderTranslation = true)
                override suspend fun save(actor: Principal, policy: ModelPolicy) = policy
            }
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http)), policy, routes(RouteTarget("anthropic", "claude-haiku-test")))
            val initial = Json.parseToJsonElement("""{"model":"gpt-6-luna","stream":true,"reasoning":{"effort":"none","context":"all_turns"},"input":[{"type":"additional_tools","role":"developer","tools":[{"type":"namespace","name":"functions","tools":[{"type":"custom","name":"exec","format":{"type":"text"}}]}]},{"role":"user","content":"Execute the local tool."}]}""").jsonObject
            suspend fun execute(body: JsonObject): JsonObject {
                val output = StringBuilder()
                gateway.forward(GenerationRequest(body.toString().toByteArray(), emptyMap(), actor, WireProtocol.RESPONSES)) { head, chunks ->
                    assertEquals("anthropic", head.headers["x-teamcode-provider"])
                    chunks.collect { output.append(it.decodeToString()) }
                }
                return output.lines().filter { it.startsWith("data: ") }.map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }.last()["response"]!!.jsonObject
            }
            val result = execute(initial)
            val call = result["output"]!!.jsonArray.single().jsonObject
            assertEquals("custom_tool_call", call["type"]!!.jsonPrimitive.content)
            assertEquals("exec", call["name"]!!.jsonPrimitive.content)
            assertEquals("functions", call["namespace"]!!.jsonPrimitive.content)
            assertEquals(code, call["input"]!!.jsonPrimitive.content)
            val followup = initial["input"]!!.jsonArray + result["output"]!!.jsonArray + buildJsonObject { put("type", "custom_tool_call_output"); put("call_id", "call_custom"); put("output", "Café 🌍") }
            assertEquals("completed", execute(JsonObject(initial + ("input" to JsonArray(followup))))["status"]!!.jsonPrimitive.content)
            assertEquals(2, calls)
            assertEquals<List<Long?>>(listOf(11L, 6L), store.charges)
        }
    }
}
private class FakeAdmission(private val account: AccountRecord, private val accountsPerProvider: Int = 1, private val knownExhaustedModels: Set<String> = emptySet()) : AdmissionStore {
    val affinities = mutableMapOf<RequestAffinity, RouteTarget>()
    override suspend fun preferredTarget(actor: Principal, affinity: RequestAffinity): RouteTarget? = affinities[affinity]
    override suspend fun statistics() = AdmissionStatistics(0, 0)
    val providerRequests = mutableListOf<Pair<String, String>>()
    override suspend fun recordProviderRequest(reservation: Reservation, requestId: String) { providerRequests += reservation.id to requestId }
    val charges = mutableListOf<Long?>()
    val settlementCompletions = mutableListOf<Boolean>()
    val observations = mutableListOf<Pair<String, QuotaUpdate>>()
    var settled: Long? = null
    var didSettle = false
    var cooled = false
    var reconnect = false
    var selectedProvider: String? = null
    val reservedUnits = mutableListOf<Long>()
    override suspend fun reserve(actor: Principal, units: Long, model: String, provider: String, excludedAccountIds: Set<String>, affinity: RequestAffinity?): Reservation {
        reservedUnits += units
        val id = (0 until accountsPerProvider).map { "$provider-$it" }.firstOrNull { it !in excludedAccountIds }
            ?: throw TeamFailure("pool_unavailable", 429, "No eligible account.")
        selectedProvider = provider
        affinity?.let { affinities[it] = RouteTarget(provider, model) }
        return Reservation("request-$id", account.copy(id = id, identity = account.identity.copy(provider = provider)), units)
    }
    override suspend fun settle(reservation: Reservation, actualUnits: Long?, completed: Boolean) {
        didSettle = true; settled = actualUnits; charges += actualUnits; settlementCompletions += completed
    }
    override suspend fun capacity(actor: Principal, memberId: String): MemberCapacity = error("unused")
    override suspend fun configure(actor: Principal, policy: MemberPolicy): MemberPolicy = error("unused")
    override suspend fun poolBudgets(actor: Principal) = emptyList<PoolBudget>()
    override suspend fun configurePool(actor: Principal, budget: PoolBudget) = budget
    override suspend fun quotas(actor: Principal) = emptyList<AccountQuota>()
    override suspend fun observe(accountId: String, observations: List<AccountQuota>) = Unit
    override suspend fun observe(accountId: String, update: QuotaUpdate) { observations += accountId to update }
    override suspend fun isQuotaExhausted(accountId: String, model: String) = model in knownExhaustedModels
    override suspend fun coolDown(accountId: String, untilMillis: Long, reconnect: Boolean) { cooled = true; this.reconnect = reconnect }
}

private class FakeModelPolicies(var translation: Boolean = true) : ModelPolicyStore {
    override suspend fun get(teamId: String) = ModelPolicy(experimentalCrossProviderTranslation = translation)
    override suspend fun save(actor: Principal, policy: ModelPolicy) = policy
}
