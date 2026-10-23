package me.nathanfallet.teamcode.companion.infrastructure

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.teamcode.api.models.CloudEventTypes
import me.nathanfallet.teamcode.api.models.CloudEventView
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.infrastructure.repositories.domain
import me.nathanfallet.teamcode.companion.presentation.transcript.transcriptItems
import kotlin.test.*

/** The app shows final messages only: live text deltas are ignored, and the final message still shows. */
class CloudMappingLiveTextTest {
    @Test fun liveTextDeltasAreIgnoredAndTheFinalMessageShows() {
        val delta = CloudEventView(3, CloudEventTypes.AGENT_MESSAGE_DELTA, 3, 1, buildJsonObject { put("messageId", "msg_1"); put("index", 0); put("text", "Hal") }).domain()
        assertIs<TranscriptEvent.Unknown>(delta)
        val final = CloudEventView(4, CloudEventTypes.AGENT_MESSAGE, 4, 1, buildJsonObject { put("text", "Half done."); put("messageId", "msg_1"); put("index", 0) }).domain()
        assertEquals(TranscriptEvent.AgentMessage(4, 4, "Half done.", null), final)
        assertEquals(listOf("Agent"), transcriptItems(listOf(delta, final)).map { it::class.simpleName })
    }
}
