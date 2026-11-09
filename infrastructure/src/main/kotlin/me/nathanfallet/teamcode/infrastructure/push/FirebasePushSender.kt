package me.nathanfallet.teamcode.infrastructure.push

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import me.nathanfallet.flareon.core.credentials.GoogleCredentials
import me.nathanfallet.flareon.messaging.FcmService
import me.nathanfallet.flareon.messaging.models.*
import me.nathanfallet.teamcode.domain.notifications.*

/**
 * Sends with a team's own Firebase project (flareon, FCM HTTP v1), for a team that builds and signs its own apps. The
 * message is the one the relay sends: a generic visible alert on iOS (the notification service extension replaces it with
 * the decrypted text), a high-priority data message on Android, the encrypted payload in the data either way.
 */
class FirebasePushSender(credentials: GoogleCredentials, private val http: HttpClient = defaultClient()) : PushSender, AutoCloseable {
    private val fcm = FcmService(credentials, httpClient = http)

    override suspend fun send(message: PushMessage): PushResult = try {
        fcm.sendMessage(message(message))
        PushResult.SENT
    } catch (error: CancellationException) { throw error } catch (error: ResponseException) {
        when (error.response.status.value) {
            404 -> PushResult.UNREGISTERED
            408, 429 -> PushResult.retry("fcm_rate_limited")
            in 500..599 -> PushResult.retry("fcm_unavailable")
            else -> PushResult.rejected("fcm_${error.response.status.value}")
        }
    } catch (_: Exception) { PushResult.retry("fcm_unreachable") }

    override fun close() = http.close()

    companion object {
        const val GENERIC_TITLE = "TeamCode"
        const val GENERIC_BODY = "New activity in TeamCode"

        /** The FCM message of a push, shared in shape with the relay (docs/notifications.md, "What the provider sees"). */
        fun message(message: PushMessage): FcmMessage {
            val data = mapOf("p" to message.payload, "v" to "1")
            return if (message.platform == "ios") FcmMessage(
                token = message.token, data = data,
                apns = ApnsConfig(
                    headers = mapOf("apns-push-type" to "alert", "apns-priority" to "10", "apns-collapse-id" to message.collapseKey,
                        "apns-expiration" to (System.currentTimeMillis() / 1000 + message.ttlSeconds).toString()),
                    payload = ApnsPayload(Aps(alert = ApsAlert(title = GENERIC_TITLE, body = GENERIC_BODY), sound = "default", mutableContent = 1, threadId = message.collapseKey)),
                ),
            ) else FcmMessage(
                token = message.token, data = data,
                android = AndroidConfig(collapseKey = message.collapseKey, priority = AndroidPriority.HIGH, ttl = "${message.ttlSeconds}s"),
            )
        }

        /** A sender for a team's service account JSON key; one HTTP client for the token and the sends, closed with it. */
        fun fromServiceAccount(json: String): FirebasePushSender {
            val http = defaultClient()
            return FirebasePushSender(GoogleCredentials.fromJson(json, httpClient = http), http)
        }

        fun defaultClient() = HttpClient(CIO) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            install(HttpTimeout) { connectTimeoutMillis = 5_000; requestTimeoutMillis = 10_000; socketTimeoutMillis = 10_000 }
        }
    }
}
