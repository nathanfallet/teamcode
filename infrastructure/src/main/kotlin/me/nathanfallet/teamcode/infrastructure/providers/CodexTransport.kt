package me.nathanfallet.teamcode.infrastructure.providers

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** ChatGPT subscription transport, separate from the metered OpenAI API. */
class CodexTransport(private val http: HttpClient) : ProviderTransport {
    override val provider = "codex"
    override val protocol = WireProtocol.RESPONSES
    override suspend fun refresh(previous: ProviderCredentials): ProviderCredentials = renew(previous).credentials
    override suspend fun renew(previous: ProviderCredentials): RefreshedCredentials = http.preparePost("https://auth.openai.com/oauth/token") {
        contentType(ContentType.Application.Json)
        timeout { requestTimeoutMillis = 15_000 }
        setBody(buildJsonObject {
            put("grant_type", "refresh_token"); put("refresh_token", previous.refreshToken)
            put("client_id", "app_EMoamEEZ73f0CkXaXp7hrann")
        }.toString())
    }.execute { response ->
        if (response.status.value != 200) throw TeamFailure("refresh_failed", 503, "Codex refresh failed.")
        val value = response.boundedJson()
        val access = (value["access_token"] as? JsonPrimitive)?.contentOrNull
        val refresh = (value["refresh_token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: previous.refreshToken
        val seconds = (value["expires_in"] as? JsonPrimitive)?.longOrNull
        if (access.isNullOrBlank() || seconds == null || seconds !in 1..31_536_000) throw TeamFailure("refresh_failed", 503, "Codex returned incomplete credentials.")
        RefreshedCredentials(ProviderCredentials(access, refresh, System.currentTimeMillis() + seconds * 1000),
            CodexTokenClaims.reported((value["id_token"] as? JsonPrimitive)?.contentOrNull, access))
    }
    internal suspend fun usageDocument(accountId: String, credentials: ProviderCredentials): JsonObject = http.prepareGet("https://chatgpt.com/backend-api/wham/usage") {
        bearerAuth(credentials.accessToken); header("ChatGPT-Account-Id", accountId)
        header(HttpHeaders.Accept, "application/json")
        timeout { requestTimeoutMillis = 15_000 }
    }.execute { response ->
        if (response.status.value != 200) throw TeamFailure("provider_verification_failed", 422, "Codex could not read this account's subscription usage.")
        response.boundedJson()
    }
    override suspend fun usage(account: AccountRecord, credentials: ProviderCredentials) = parseUsage(account.id, usageDocument(account.identity.organizationId, credentials), System.currentTimeMillis())
    override suspend fun usageUpdate(account: AccountRecord, credentials: ProviderCredentials): QuotaUpdate {
        // Timestamp before the request: a slow probe must not erase newer inference observations.
        val started = System.currentTimeMillis()
        return usageSnapshot(account.id, usageDocument(account.identity.organizationId, credentials), started)
    }
    override suspend fun send(account: AccountRecord, credentials: ProviderCredentials, body: JsonObject, headers: Map<String, String>, receive: suspend (HttpResponse) -> Unit) {
        if (body["store"] != null && body["store"] != JsonPrimitive(false)) throw TeamFailure("unsupported_storage", 400, "The Codex subscription endpoint requires store=false.")
        http.preparePost("https://chatgpt.com/backend-api/codex/responses") {
            bearerAuth(credentials.accessToken)
            header("ChatGPT-Account-Id", account.identity.organizationId)
            header(HttpHeaders.UserAgent, headers["user-agent"] ?: "TeamCode/0.1.0")
            header(HttpHeaders.Accept, "text/event-stream")
            header(HttpHeaders.AcceptEncoding, "identity")
            header("originator", "codex_cli_rs")
            contentType(ContentType.Application.Json)
            timeout { requestTimeoutMillis = 10 * 60_000; socketTimeoutMillis = 300_000 }
            setBody(JsonObject(body + ("store" to JsonPrimitive(false))).toString())
        }.execute { receive(it) }
    }
    override fun quotas(accountId: String, headers: Headers, now: Long): List<AccountQuota> = headerQuotas(accountId, headers, now)
    override fun quotaUpdate(accountId: String, model: String, headers: Headers, now: Long) =
        QuotaUpdate(headerQuotas(accountId, headers, now), now, modelLimit = activeLimit(model, headers))
    companion object {
        private fun slug(value: String?): String? = value?.trim()?.lowercase()?.takeIf { Regex("[a-z0-9][a-z0-9_-]{0,63}").matches(it) }
        fun activeLimit(model: String, headers: Headers): ModelLimitObservation? {
            val name = model.lowercase().takeIf { Regex("[a-z0-9][a-z0-9._/:-]{0,127}").matches(it) } ?: return null
            val limit = slug(headers["x-codex-active-limit"]) ?: return null
            return ModelLimitObservation(name, limit)
        }
        fun headerQuotas(accountId: String, headers: Headers, now: Long): List<AccountQuota> = normalize(buildList {
            val pattern = Regex("^x-codex-(?:(.+)-)?(primary|secondary)-used-percent$")
            for (name in headers.names()) {
                val match = pattern.matchEntire(name.lowercase()) ?: continue
                val rawSlug = match.groupValues[1].takeIf { it.isNotEmpty() }
                val family = rawSlug?.let(::slug)
                if (rawSlug != null && family == null) continue
                val prefix = name.lowercase().removeSuffix("-used-percent")
                bucket(accountId, headers[name]?.toDoubleOrNull(),
                    headers["$prefix-window-minutes"]?.toDoubleOrNull()?.times(60),
                    headers["$prefix-reset-at"]?.toLongOrNull(), now, family)?.let(::add)
            }
        })
        fun parseUsage(accountId: String, value: JsonObject, now: Long): List<AccountQuota> = normalize(buildList {
            fun read(limit: JsonObject?, family: String? = null) {
                limit?.values?.forEach { element ->
                    val w = element as? JsonObject ?: return@forEach
                    bucket(accountId, (w.quotaField("used_percent", "usedPercentage", "utilization") as? JsonPrimitive)?.doubleOrNull,
                        (w.quotaField("limit_window_seconds", "window_seconds") as? JsonPrimitive)?.doubleOrNull,
                        (w.quotaField("reset_at", "resetAt") as? JsonPrimitive)?.longOrNull, now, family)?.let(::add)
                }
            }
            read(value.quotaField("rate_limit", "rate_limits") as? JsonObject)
            when (val additional = value["additional_rate_limits"]) {
                is JsonArray -> additional.forEach { element ->
                    val entry = element as? JsonObject ?: return@forEach
                    val family = family(entry)
                    if (family != null) read(entry["rate_limit"] as? JsonObject ?: entry, family)
                }
                is JsonObject -> additional.forEach { (name, entry) -> slug(name.lowercase().removePrefix("codex_"))?.let { read((entry as? JsonObject)?.get("rate_limit") as? JsonObject ?: entry as? JsonObject, it) } }
                else -> Unit
            }
        })
        private fun family(entry: JsonObject): String? =
            slug((entry["metered_feature"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.removePrefix("codex_"))
                ?: slug((entry["limit_name"] as? JsonPrimitive)?.contentOrNull)

        /** Missing or malformed additional limits are not evidence that an old cap was removed. */
        fun usageSnapshot(accountId: String, value: JsonObject, started: Long): QuotaUpdate {
            fun validEntry(entry: JsonElement): Boolean {
                val objectValue = entry as? JsonObject ?: return false
                val limit = objectValue["rate_limit"] as? JsonObject ?: objectValue
                val windows = limit.filterKeys { it.endsWith("_window") }
                if (windows.isEmpty()) return false
                return windows.values.all { element ->
                    if (element == JsonNull) true else {
                        val window = element as? JsonObject
                        val used = (window?.quotaField("used_percent", "usedPercentage", "utilization") as? JsonPrimitive)?.doubleOrNull
                        val seconds = (window?.quotaField("limit_window_seconds", "window_seconds") as? JsonPrimitive)?.doubleOrNull
                        used != null && used.isFinite() && used >= 0 && seconds != null && seconds.isFinite() && seconds >= 0
                    }
                }
            }
            val complete = when (val additional = value["additional_rate_limits"]) {
                is JsonArray -> additional.size <= 128 && additional.all { it is JsonObject && family(it) != null && validEntry(it) }
                is JsonObject -> additional.size <= 128 && additional.all { (name, entry) -> slug(name.lowercase().removePrefix("codex_")) != null && validEntry(entry) }
                else -> false
            }
            return QuotaUpdate(parseUsage(accountId, value, started), started, replaceModelWindows = complete)
        }
        // TeamClaude's subscription observations: the named 5h window is the shared
        // session allowance when the unnamed family only states a weekly allowance.
        // An explicit shared 5h reading wins. Named weekly limits remain model-scoped.
        private fun normalize(windows: List<AccountQuota>): List<AccountQuota> {
            val shared = windows.filterNot { it.window.startsWith("model:") }
            val session = if (shared.none { it.window == "five_hour" }) windows
                .filter { it.window.endsWith(":five_hour") }.maxByOrNull { it.utilization }
                ?.copy(window = "five_hour") else null
            return shared + listOfNotNull(session) + windows.filter { it.window.startsWith("model:") && it.window.endsWith(":seven_day") }
        }
        private fun bucket(account: String, percent: Double?, seconds: Double?, reset: Long?, now: Long, family: String? = null): AccountQuota? {
            if (seconds == null || !seconds.isFinite()) return null
            // Same 10% rounding tolerance as TeamClaude, never classify by primary/secondary position.
            val window = when (seconds) { in 16_200.0..19_800.0 -> "five_hour"; in 544_320.0..665_280.0 -> "seven_day"; else -> return null }
            val resets = reset?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
            if (percent == null || !percent.isFinite() || percent < 0 || (resets != null && resets <= now)) return null
            // Upstream may keep counting past 100%; persistence stores bounded utilization.
            return AccountQuota(account, if (family == null) window else "model:$family:$window", (percent / 100).coerceAtMost(1.0), now, resets)
        }
    }
}
