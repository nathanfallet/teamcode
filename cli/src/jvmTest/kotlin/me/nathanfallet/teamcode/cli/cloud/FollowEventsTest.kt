package me.nathanfallet.teamcode.cli.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `teamcode cloud logs --follow`: which stream failures reconnect and which stop. */
class FollowEventsTest {
    @Test fun anUnreadableEventStopsInsteadOfReplayingForever() = runTest {
        var requests = 0
        val notices = mutableListOf<String>()
        val client = TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
            HttpClient(MockEngine {
                requests++
                respond(ByteReadChannel("event: message.agent\ndata: not json\n\n"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }) { configure(this) }
        }
        client.use {
            // Returns instead of exiting, so `attach` still detaches; `logs --follow` then exits with status 1.
            assertFalse(followEvents(it, "s1", 4, { }, notice = { notice -> notices += notice }))
        }
        assertEquals(1, requests)
        assertTrue(notices.single().startsWith("The event after 4 cannot be read") && "--after" in notices.single(), notices.toString())
    }
}
