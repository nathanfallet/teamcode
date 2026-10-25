package me.nathanfallet.teamcode.companion.infrastructure

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonArray
import me.nathanfallet.teamcode.companion.domain.models.BackgroundTask
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
    @Test fun aTasksEventListsTheLiveBackgroundTasks() {
        val event = CloudEventView(7, CloudEventTypes.TASKS, 7_000, 1, buildJsonObject {
            putJsonArray("tasks") {
                addJsonObject { put("id", "b1"); put("type", "local_bash"); put("description", "npm test"); put("toolUseId", "toolu_9"); put("startedAt", 5_000) }
                addJsonObject { put("id", "a1"); put("type", "local_agent"); put("description", "Review"); put("subagentType", "Explore") }
                addJsonObject { put("description", "no id: left out") }
            }
            put("stoppable", true)
        }).domain()
        val tasks = assertIs<TranscriptEvent.Tasks>(event)
        assertTrue(tasks.stoppable)
        assertEquals(listOf(BackgroundTask("b1", "local_bash", "npm test", null, "toolu_9", 5_000), BackgroundTask("a1", "local_agent", "Review", "Explore", null, null)), tasks.tasks)
        assertTrue(transcriptItems(listOf(event)).isEmpty(), "The panel shows it, not the transcript")
        assertFalse(assertIs<TranscriptEvent.Tasks>(CloudEventView(8, CloudEventTypes.TASKS, 8_000, 1, buildJsonObject { }).domain()).stoppable)
    }
}
