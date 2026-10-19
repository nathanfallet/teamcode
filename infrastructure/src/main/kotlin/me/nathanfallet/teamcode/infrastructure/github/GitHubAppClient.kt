package me.nathanfallet.teamcode.infrastructure.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.github.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** GitHub's web (OAuth) and REST origins; tests point them at a mock engine. */
data class GitHubEndpoints(val web: String = "https://github.com", val api: String = "https://api.github.com")

/** Thrown for any GitHub answer TeamCode does not act on. Carries no response content: it may echo secrets. */
class GitHubApiException(val status: Int) : Exception("GitHub returned HTTP $status.")

/**
 * GitHub App calls: device and web authorization, user-token refresh and revocation, App verification with a JWT.
 * Response bodies are bounded and never surface in errors; only GitHub App user tokens (`ghu_`) are accepted.
 */
class GitHubAppClient(
    private val endpoints: GitHubEndpoints = GitHubEndpoints(),
    engine: HttpClientEngine? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : GitHubPort, AutoCloseable {
    private val setup: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 20_000 }
    }
    private val http = if (engine != null) HttpClient(engine, setup) else HttpClient(CIO, setup)

    override suspend fun verifyApp(appId: Long, clientId: String, privateKey: String): GitHubAppMetadata {
        val jwt = appJwt(clientId, rsaPrivateKey(privateKey), clock() / 1000)
        val (status, body) = send(HttpMethod.Get, "${endpoints.api}/app") { bearerAuth(jwt); restHeaders() }
        if (status == 401 || status == 404)
            throw TeamFailure("github_app_rejected", 422, "GitHub did not accept the private key for this client ID. Check the App's client ID and private key.")
        if (status != 200 || body == null) throw GitHubApiException(status)
        val id = body.long("id"); val returnedClientId = body.string("client_id")
        if (id != appId || (returnedClientId != null && returnedClientId != clientId))
            throw TeamFailure("github_app_mismatch", 422, "The private key belongs to another GitHub App than this App ID and client ID.")
        return GitHubAppMetadata(appId, clientId, body.string("slug")?.take(100), body.string("name")?.take(120))
    }

    override suspend fun startDevice(clientId: String): GitHubDeviceAuthorization {
        val (status, body) = form("${endpoints.web}/login/device/code", listOf("client_id" to clientId))
        body?.string("error")?.let { throw oauthFailure(it) }
        if (status != 200 || body == null) throw GitHubApiException(status)
        val deviceCode = body.string("device_code"); val userCode = body.string("user_code"); val uri = body.string("verification_uri")
        val expiresIn = body.long("expires_in"); val interval = body.long("interval") ?: 5
        if (deviceCode.isNullOrBlank() || deviceCode.length > 512 || userCode == null || !userCodePattern.matches(userCode) ||
            uri == null || !uri.startsWith(endpoints.web + "/") || uri.length > 512 || expiresIn == null || expiresIn !in 1..3600 || interval !in 1..600)
            throw GitHubApiException(status)
        return GitHubDeviceAuthorization(deviceCode, userCode, uri, expiresIn.toInt(), interval.toInt())
    }

    override suspend fun pollDevice(clientId: String, deviceCode: String): GitHubDevicePoll {
        val (status, body) = form("${endpoints.web}/login/oauth/access_token", listOf(
            "client_id" to clientId, "device_code" to deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
        if (status != 200 || body == null) throw GitHubApiException(status)
        return when (val error = body.string("error")) {
            null -> GitHubDevicePoll.Authorized(tokens(body))
            "authorization_pending" -> GitHubDevicePoll.Pending
            "slow_down" -> GitHubDevicePoll.SlowDown((body.long("interval") ?: 10).toInt().coerceIn(1, 600))
            "expired_token", "incorrect_device_code" -> GitHubDevicePoll.Expired
            "access_denied" -> GitHubDevicePoll.Denied
            else -> throw oauthFailure(error)
        }
    }

    override fun authorizationUrl(clientId: String, redirectUri: String, state: String, codeChallenge: String): String =
        URLBuilder("${endpoints.web}/login/oauth/authorize").apply {
            parameters.append("client_id", clientId); parameters.append("redirect_uri", redirectUri); parameters.append("state", state)
            parameters.append("code_challenge", codeChallenge); parameters.append("code_challenge_method", "S256")
        }.buildString()

    override suspend fun exchangeCode(credentials: GitHubAppCredentials, code: String, redirectUri: String, codeVerifier: String): GitHubTokens {
        val (status, body) = form("${endpoints.web}/login/oauth/access_token", listOf(
            "client_id" to credentials.clientId, "client_secret" to credentials.clientSecret, "code" to code,
            "redirect_uri" to redirectUri, "code_verifier" to codeVerifier))
        if (status != 200 || body == null) throw GitHubApiException(status)
        body.string("error")?.let { throw oauthFailure(it) }
        return tokens(body)
    }

    override suspend fun refresh(credentials: GitHubAppCredentials, refreshToken: String): GitHubRefresh {
        val (status, body) = form("${endpoints.web}/login/oauth/access_token", listOf(
            "client_id" to credentials.clientId, "client_secret" to credentials.clientSecret,
            "grant_type" to "refresh_token", "refresh_token" to refreshToken))
        if (status != 200 || body == null) throw GitHubApiException(status)
        return when (val error = body.string("error")) {
            null -> GitHubRefresh.Refreshed(tokens(body))
            // Only a refusal of the refresh token itself ends the link; a wrong client secret is the administrator's to fix.
            "bad_refresh_token" -> GitHubRefresh.Rejected
            else -> throw oauthFailure(error)
        }
    }

    override suspend fun user(accessToken: String): GitHubIdentity {
        val (status, body) = send(HttpMethod.Get, "${endpoints.api}/user") { bearerAuth(accessToken); restHeaders() }
        if (status != 200 || body == null) throw GitHubApiException(status)
        val id = body.long("id"); val login = body.string("login")
        if (id == null || id <= 0 || login == null || !loginPattern.matches(login)) throw GitHubApiException(status)
        return GitHubIdentity(id, login)
    }

    override suspend fun revoke(credentials: GitHubAppCredentials, accessToken: String) {
        val basic = Base64.getEncoder().encodeToString("${credentials.clientId}:${credentials.clientSecret}".encodeToByteArray())
        val (status, _) = send(HttpMethod.Delete, "${endpoints.api}/applications/${credentials.clientId.encodeURLPathPart()}/token") {
            header(HttpHeaders.Authorization, "Basic $basic"); restHeaders()
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("access_token", accessToken) }.toString())
        }
        // 404: the token is already invalid, which is the goal.
        if (status != 204 && status != 404) throw GitHubApiException(status)
    }

    override fun close() = http.close()

    private fun tokens(body: JsonObject): GitHubTokens {
        val access = body.string("access_token")
        if (access.isNullOrBlank() || access.length > 2048) throw GitHubApiException(200)
        // GitHub App user-to-server tokens start with ghu_. Anything else (an OAuth App's gho_) is refused.
        if (!access.startsWith("ghu_")) throw TeamFailure("github_app_misconfigured", 409, "The configured client ID does not issue GitHub App user tokens. Configure a GitHub App, not an OAuth App.")
        val now = clock()
        val refresh = body.string("refresh_token")?.takeIf { it.isNotBlank() && it.length <= 2048 }
        return GitHubTokens(access, body.long("expires_in")?.takeIf { it > 0 }?.let { now + it * 1000 }, refresh,
            body.long("refresh_token_expires_in")?.takeIf { it > 0 && refresh != null }?.let { now + it * 1000 })
    }

    private fun oauthFailure(error: String) = when (error) {
        "device_flow_disabled" -> TeamFailure("github_device_flow_disabled", 409, "Device flow is disabled for the team's GitHub App. Link from the dashboard, or ask an administrator to enable it in the App settings.")
        "incorrect_client_credentials" -> TeamFailure("github_app_misconfigured", 409, "GitHub refused the App's client credentials. An administrator must update the client secret.")
        "redirect_uri_mismatch" -> TeamFailure("github_app_misconfigured", 409, "The TeamCode callback URL is not a callback URL of the GitHub App. An administrator must add it.")
        "bad_verification_code" -> TeamFailure("github_link_not_found", 404, "The GitHub authorization expired or was already used. Start again.")
        "unverified_user_email" -> TeamFailure("github_link_denied", 400, "Verify your primary email address on GitHub, then start again.")
        else -> GitHubApiException(200)
    }

    private fun HttpRequestBuilder.restHeaders() {
        accept(ContentType.parse("application/vnd.github+json"))
        header("X-GitHub-Api-Version", "2022-11-28")
        header(HttpHeaders.UserAgent, "TeamCode")
    }

    private suspend fun form(url: String, fields: List<Pair<String, String>>) = send(HttpMethod.Post, url) {
        accept(ContentType.Application.Json)
        header(HttpHeaders.UserAgent, "TeamCode")
        setBody(FormDataContent(Parameters.build { fields.forEach { (key, value) -> append(key, value) } }))
    }

    /** Status and JSON object (null when the body is not one). Bodies over 64 KiB are refused. */
    private suspend fun send(method: HttpMethod, url: String, configure: HttpRequestBuilder.() -> Unit): Pair<Int, JsonObject?> =
        http.prepareRequest(url) { this.method = method; configure() }.execute { response ->
            val bytes = response.bodyAsChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
            if (bytes.size > MAX_BODY_BYTES) throw GitHubApiException(response.status.value)
            val json = try { Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject } catch (_: IllegalArgumentException) { null }
            response.status.value to json
        }

    companion object {
        private const val MAX_BODY_BYTES = 65_536
        private val userCodePattern = Regex("[A-Z0-9-]{4,32}")
        private val loginPattern = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
        private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.long(name: String) = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

        /** A 9-minute App JWT (GitHub allows 10), issued a minute in the past against clock drift. */
        internal fun appJwt(clientId: String, key: PrivateKey, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".encodeToByteArray())
            val payload = encoder.encodeToString(buildJsonObject { put("iat", nowSeconds - 60); put("exp", nowSeconds + 540); put("iss", clientId) }.toString().encodeToByteArray())
            val signature = Signature.getInstance("SHA256withRSA").apply { initSign(key); update("$header.$payload".encodeToByteArray()) }.sign()
            return "$header.$payload.${encoder.encodeToString(signature)}"
        }

        /** GitHub hands out PKCS#1 keys (`BEGIN RSA PRIVATE KEY`); PKCS#8 (`BEGIN PRIVATE KEY`) is accepted too. */
        fun rsaPrivateKey(pem: String): PrivateKey {
            val invalid = TeamFailure("invalid_github_app", 400, "The private key must be the App's PEM RSA private key.")
            val type = Regex("-----BEGIN (RSA )?PRIVATE KEY-----").find(pem) ?: throw invalid
            val body = pem.substring(type.range.last + 1).substringBefore("-----END").filterNot { it.isWhitespace() }
            return try {
                val der = Base64.getDecoder().decode(body)
                val pkcs8 = if (type.groupValues[1].isNotEmpty()) pkcs1ToPkcs8(der) else der
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            } catch (_: Exception) { throw invalid }
        }

        private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
            val rsaEncryption = byteArrayOf(0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01)
            val algorithm = der(0x30, rsaEncryption + byteArrayOf(0x05, 0x00))
            return der(0x30, byteArrayOf(0x02, 0x01, 0x00) + algorithm + der(0x04, pkcs1))
        }
        private fun der(tag: Int, content: ByteArray): ByteArray {
            val size = content.size
            val length = when {
                size < 0x80 -> byteArrayOf(size.toByte())
                size < 0x100 -> byteArrayOf(0x81.toByte(), size.toByte())
                size < 0x10000 -> byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
                else -> byteArrayOf(0x83.toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte())
            }
            return byteArrayOf(tag.toByte()) + length + content
        }
    }
}
