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
import kotlinx.coroutines.CancellationException

/** Provider login runs on the user's computer; only the resulting credentials are enrolled on TeamCode. */
class OAuthEnrollmentClient(private val provider: OAuthEnrollmentProvider, createHttpClient: (io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient) : AutoCloseable {
    private val http = createHttpClient {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000 }
    }
    suspend fun exchange(code: String, state: String, verifier: String, operationId: String): EnrollAccountRequest = try {
        http.preparePost(provider.tokenEndpoint) {
            contentType(ContentType.Application.Json)
            setBody(provider.exchangeBody(code, state, verifier).toString())
        }.execute { response ->
            val status = response.status.value
            if (status != 200) throw OAuthEnrollmentException(
                if (status == 408 || status == 429 || status >= 500) OAuthEnrollmentException.Reason.EXCHANGE_UNAVAILABLE else OAuthEnrollmentException.Reason.EXCHANGE_REJECTED,
                provider.displayName, "${provider.displayName} sign-in failed. Start a new sign-in attempt.", status)
            val bytes = response.bodyAsChannel().readRemaining(65_537).readByteArray()
            if (bytes.size > 65_536) invalidTokenResponse()
            val value = (try { Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }
                catch (_: IllegalArgumentException) { null }) ?: invalidTokenResponse()
            val access = (value["access_token"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val refresh = (value["refresh_token"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val seconds = (value["expires_in"] as? JsonPrimitive)?.longOrNull
            if (access.isNullOrBlank() || refresh.isNullOrBlank() || seconds == null || seconds !in 1..31_536_000) invalidTokenResponse()
            EnrollAccountRequest(access, refresh, Clock.System.now().toEpochMilliseconds() + seconds * 1000, operationId, provider.provider, freshSignIn = true)
        }
    } catch (error: CancellationException) { throw error }
    catch (error: OAuthEnrollmentException) { throw error }
    // Network and TLS failures name the provider endpoint, not the TeamCode server; never keep their messages.
    catch (error: Exception) {
        throw OAuthEnrollmentException(OAuthEnrollmentException.Reason.EXCHANGE_UNREACHABLE, provider.displayName, "${provider.displayName} sign-in service is unreachable.", cause = error)
    }

    private fun invalidTokenResponse(): Nothing =
        throw OAuthEnrollmentException(OAuthEnrollmentException.Reason.INVALID_TOKEN_RESPONSE, provider.displayName, "Invalid token response.")
    override fun close() = http.close()
}
