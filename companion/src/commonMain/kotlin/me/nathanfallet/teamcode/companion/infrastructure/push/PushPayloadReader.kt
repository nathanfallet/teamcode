package me.nathanfallet.teamcode.companion.infrastructure.push

import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.api.models.PushPayload
import me.nathanfallet.teamcode.companion.domain.models.PushMessage

/** The decrypted JSON of a push, read as the API's `PushPayload`; unknown fields are ignored. */
object PushPayloadReader {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(text: String): PushMessage = json.decodeFromString(PushPayload.serializer(), text).run {
        PushMessage(id, origin, memberId, title, body, sessionId, waitId)
    }
}
