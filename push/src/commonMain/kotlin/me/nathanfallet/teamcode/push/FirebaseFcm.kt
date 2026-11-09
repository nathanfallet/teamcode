package me.nathanfallet.teamcode.push

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import me.nathanfallet.flareon.core.credentials.GoogleCredentials
import me.nathanfallet.flareon.messaging.FcmService
import me.nathanfallet.flareon.messaging.models.*
import me.nathanfallet.teamcode.api.models.PushRelay
import me.nathanfallet.teamcode.api.models.PushRelaySendRequest
import me.nathanfallet.teamcode.api.models.PushRelaySendResponse

/**
 * FCM through flareon, with the Cloud Run service account (no key file): the same message as a team's own Firebase
 * project sends (docs/notifications.md, "What the provider sees"). The provider's answer is reduced to a status and a
 * short code; its body is never kept.
 */
class FirebaseFcm(credentials: GoogleCredentials, private val http: HttpClient = defaultClient(), projectId: String? = null) : FcmSender, AutoCloseable {
    private val fcm = FcmService(credentials, projectId, http)

    override suspend fun send(request: PushRelaySendRequest): PushRelaySendResponse = try {
        fcm.sendMessage(message(request))
        PushRelaySendResponse("sent")
    } catch (error: CancellationException) { throw error } catch (error: ResponseException) {
        val retryAfter = error.response.headers["Retry-After"]?.toLongOrNull()?.coerceIn(1, 3_600)
        when (val status = error.response.status.value) {
            404 -> PushRelaySendResponse("unregistered")
            // The relay's own credentials: a token being refreshed, or a project misconfiguration the operator fixes.
            401 -> PushRelaySendResponse("retry", error = "fcm_auth")
            408, 429 -> PushRelaySendResponse("retry", retryAfter, "fcm_rate_limited")
            in 500..599 -> PushRelaySendResponse("retry", retryAfter, "fcm_unavailable")
            else -> PushRelaySendResponse("rejected", error = "fcm_$status")
        }
    }

    override fun close() = http.close()

    companion object {
        fun message(request: PushRelaySendRequest): FcmMessage {
            val data = mapOf(PushRelay.DATA_PAYLOAD to request.payload, PushRelay.DATA_VERSION to "1")
            return if (request.platform == "ios") FcmMessage(
                token = request.token, data = data,
                apns = ApnsConfig(
                    headers = mapOf("apns-push-type" to "alert", "apns-priority" to "10", "apns-collapse-id" to request.collapseKey,
                        "apns-expiration" to (Clock.System.now().epochSeconds + request.ttlSeconds).toString()),
                    payload = ApnsPayload(Aps(alert = ApsAlert(title = PushRelay.GENERIC_TITLE, body = PushRelay.GENERIC_BODY), sound = "default",
                        mutableContent = 1, threadId = request.collapseKey)),
                ),
            ) else FcmMessage(
                token = request.token, data = data,
                android = AndroidConfig(collapseKey = request.collapseKey, priority = AndroidPriority.HIGH, ttl = "${request.ttlSeconds}s"),
            )
        }

        fun defaultClient() = HttpClient {
            expectSuccess = true
            followRedirects = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            install(HttpTimeout) { connectTimeoutMillis = 5_000; requestTimeoutMillis = 10_000; socketTimeoutMillis = 10_000 }
        }
    }
}
