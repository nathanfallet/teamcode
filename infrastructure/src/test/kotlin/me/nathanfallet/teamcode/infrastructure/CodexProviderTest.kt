package me.nathanfallet.teamcode.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.gateway.SseUsageMeter
import me.nathanfallet.teamcode.infrastructure.providers.*
import kotlin.test.*

class CodexProviderTest {
    private fun credentials(account: String = "workspace-1", profile: String = "") = ProviderCredentials(
        "header." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"https://api.openai.com/auth":{"chatgpt_account_id":"$account"}$profile}""".toByteArray()) + ".signature",
        "refresh-secret", Long.MAX_VALUE)
    @Test fun remoteIdentityMustMatchAccountHint() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("chatgpt.com", request.url.host)
            assertEquals("workspace-1", request.headers["ChatGPT-Account-Id"])
            respond("""{"account_id":"workspace-1","user_id":"user-1","plan_type":"pro"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(HttpTimeout) }
        http.use {
            val account = CodexAccountVerifier(it).verify("team", credentials())
            assertEquals(AccountIdentity("team", "codex", "user-1", "workspace-1"), account.identity)
            assertNull(account.email)
            assertEquals("grace@example.com", CodexAccountVerifier(it).verify("team", credentials(profile = ""","https://api.openai.com/profile":{"email":"grace@example.com"}""")).email,
                "Without an email in the usage document, the token's profile claim names the account")
        }
        val withEmail = HttpClient(MockEngine { respond("""{"account_id":"workspace-1","user_id":"user-1","email":"ada@example.com"}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) }) { install(HttpTimeout) }
        withEmail.use { assertEquals("ada@example.com", CodexAccountVerifier(it).verify("team", credentials(profile = ""","https://api.openai.com/profile":{"email":"other@example.com"}""")).email) }
        val mismatch = HttpClient(MockEngine { respond("""{"account_id":"different","user_id":"user-1"}""") }) { install(HttpTimeout) }
        mismatch.use { assertFailsWith<TeamFailure> { CodexAccountVerifier(it).verify("team", credentials()) } }
    }
    @Test fun subscriptionRequestKeepsToolsAndUsesOnlyItsProviderCredentials() = runBlocking {
        val body = Json.parseToJsonElement("""{"model":"gpt-6-luna","stream":true,"instructions":"brief","input":[],"tools":[{"type":"function","name":"lookup","parameters":{"type":"object"}}]}""").jsonObject
        val http = HttpClient(MockEngine { request ->
            assertEquals("https://chatgpt.com/backend-api/codex/responses", request.url.toString())
            assertEquals("workspace-1", request.headers["ChatGPT-Account-Id"])
            assertEquals("Bearer ${credentials().accessToken}", request.headers[HttpHeaders.Authorization])
            val sent = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(body["tools"], sent["tools"])
            assertEquals(JsonPrimitive(false), sent["store"])
            assertNull(request.headers["x-api-key"])
            respond("data: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { install(HttpTimeout) }
        http.use {
            val account = AccountRecord("id", AccountIdentity("team", "codex", "user-1", "workspace-1"), "Codex", 1, false, null, 0, AccountState.ACCESS_ONLY)
            CodexTransport(it).send(account, credentials(), body, emptyMap()) { response -> assertEquals(200, response.status.value) }
            assertFailsWith<TeamFailure> { CodexTransport(it).send(account, credentials(), JsonObject(body + ("store" to JsonPrimitive(true))), emptyMap()) {} }
        }
    }
    @Test fun responseUsageDoesNotDoubleCountCacheOrReasoning() {
        val meter = SseUsageMeter()
        val terminal = """data: {"type":"response.completed","response":{"usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":80},"output_tokens":10,"output_tokens_details":{"reasoning_tokens":8}}}}""" + "\n\ndata: [DONE]\n\n"
        terminal.toByteArray().forEach { meter.accept(byteArrayOf(it)) }
        assertEquals(150, meter.settledUnits)
        meter.accept(terminal.toByteArray())
        assertEquals(150, meter.settledUnits)
        val missing = SseUsageMeter()
        missing.accept("data: {\"type\":\"response.completed\",\"response\":{}}\n\n".toByteArray())
        assertNull(missing.settledUnits)
    }
    @Test fun subscriptionWindowsKeepDurationsAndModelScopesDistinct() {
        val value = Json.parseToJsonElement("""{"rate_limit":{"primary_window":{"used_percent":20,"limit_window_seconds":18000,"reset_at":2000},"secondary_window":{"used_percent":40,"limit_window_seconds":604800,"reset_at":3000}},"additional_rate_limits":[{"limit_name":"gpt-6-luna","rate_limit":{"primary_window":{"used_percent":99,"limit_window_seconds":18000,"reset_at":2000}}}]}""").jsonObject
        val quotas = CodexTransport.parseUsage("account", value, 1000)
        assertEquals(listOf("five_hour", "seven_day"), quotas.map { it.window })
        assertEquals(listOf(.2, .4), quotas.map { it.utilization })
    }
    @Test fun namedHeadersSupplySharedSessionAndScopedWeeklyExhaustion() {
        val headers = headersOf(
            "X-Codex-Primary-Used-Percent" to listOf("42"),
            "x-codex-primary-window-minutes" to listOf("10080"),
            "x-codex-primary-reset-at" to listOf("3000"),
            "x-codex-secondary-used-percent" to listOf("0"),
            "x-codex-secondary-window-minutes" to listOf("0"),
            "x-codex-gpt-6-limit-name" to listOf("GPT-6"),
            "x-codex-gpt-6-primary-used-percent" to listOf("104.2"),
            "x-codex-gpt-6-primary-window-minutes" to listOf("300"),
            "x-codex-gpt-6-primary-reset-at" to listOf("2000"),
            "x-codex-gpt-6-secondary-used-percent" to listOf("100"),
            "x-codex-gpt-6-secondary-window-minutes" to listOf("10080"),
            "x-codex-gpt-6-secondary-reset-at" to listOf("3000"),
        )
        val quotas = CodexTransport.headerQuotas("account", headers, 1000).associateBy { it.window }
        assertEquals(setOf("five_hour", "seven_day", "model:gpt-6:seven_day"), quotas.keys)
        assertEquals(.42, quotas.getValue("seven_day").utilization)
        assertEquals(1.0, quotas.getValue("five_hour").utilization)
        assertEquals(2_000_000, quotas.getValue("five_hour").resetsAtMillis)
        assertEquals(1.0, quotas.getValue("model:gpt-6:seven_day").utilization)
        assertTrue(CodexTransport.headerQuotas("account", headersOf("x-codex-primary-used-percent", "100"), 1000).isEmpty())
    }
    @Test fun namedUsageSessionFillsOnlyMissingSharedWindow() {
        val value = Json.parseToJsonElement("""{"rate_limit":{"primary_window":{"used_percent":20,"limit_window_seconds":604800,"reset_at":3000}},"additional_rate_limits":[{"limit_name":"gpt-6","rate_limit":{"primary_window":{"used_percent":99,"limit_window_seconds":18000,"reset_at":2000},"secondary_window":{"used_percent":105,"limit_window_seconds":604800,"reset_at":3000}}}]}""").jsonObject
        val quotas = CodexTransport.parseUsage("account", value, 1000).associateBy { it.window }
        assertEquals(.99, quotas.getValue("five_hour").utilization)
        assertEquals(1.0, quotas.getValue("model:gpt-6:seven_day").utilization)
    }
    @Test fun claudeRejectedStatusPersistsExhaustionWithoutUtilization() {
        val quotas = ClaudeTransport.headerQuotas("account", headersOf(
            "anthropic-ratelimit-unified-7d_oi-status" to listOf("rejected"),
            "anthropic-ratelimit-unified-7d_oi-reset" to listOf("3000"),
        ), 1000)
        assertEquals("model:fable", quotas.single().window)
        assertEquals(1.0, quotas.single().utilization)
        assertEquals(3_000_000, quotas.single().resetsAtMillis)
    }

    @Test fun usageProbeCarriesItsSnapshotAndOtherProvidersIgnoreCodexBindings() = runBlocking {
        var receivedAt = 0L
        HttpClient(MockEngine { request ->
            receivedAt = System.currentTimeMillis()
            assertEquals("https://chatgpt.com/backend-api/wham/usage", request.url.toString())
            assertEquals("workspace-1", request.headers["ChatGPT-Account-Id"])
            respond("""{"additional_rate_limits":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(HttpTimeout) }.use { http ->
            val account = AccountRecord("id", AccountIdentity("team", "codex", "user-1", "workspace-1"), "Codex", 1, false, null, 0, AccountState.ACCESS_ONLY)
            val update = CodexTransport(http).usageUpdate(account, credentials())
            assertTrue(update.replaceModelWindows)
            assertTrue(update.windows.isEmpty())
            assertTrue(update.observedAtMillis <= receivedAt)
            val stray = ClaudeTransport(http).quotaUpdate("id", "claude-opus", headersOf("x-codex-active-limit", "premium"), receivedAt)
            assertNull(stray.modelLimit)
            assertFalse(stray.replaceModelWindows)
        }
    }

}
