package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.EnrollAccountRequest
import kotlin.time.Clock

/** Provider login runs on the user's computer; only the resulting credentials are enrolled on TeamCode. */
class OAuthEnrollmentClient(private val provider: OAuthEnrollmentProvider, createHttpClient: (io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient) : AutoCloseable {
    private val http = createHttpClient {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000 }
    }
    suspend fun exchange(code: String, state: String, verifier: String, operationId: String): EnrollAccountRequest = http.preparePost(provider.tokenEndpoint) {
        contentType(ContentType.Application.Json)
        setBody(provider.exchangeBody(code, state, verifier).toString())
    }.execute { response ->
        if (response.status.value != 200) throw TeamCodeException(response.status.value, "${provider.displayName} sign-in failed. Start a new sign-in attempt.")
        val bytes = response.bodyAsChannel().readRemaining(65_537).readByteArray()
        require(bytes.size <= 65_536) { "Invalid token response." }
        val value = try { Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }
            catch (_: IllegalArgumentException) { null }
        requireNotNull(value) { "Invalid token response." }
        val access = (value["access_token"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val refresh = (value["refresh_token"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val seconds = (value["expires_in"] as? JsonPrimitive)?.longOrNull
        require(!access.isNullOrBlank() && !refresh.isNullOrBlank() && seconds != null && seconds in 1..31_536_000) { "Invalid token response." }
        EnrollAccountRequest(access, refresh, Clock.System.now().toEpochMilliseconds() + seconds * 1000, operationId, provider.provider, freshSignIn = true)
    }
    override fun close() = http.close()
}
