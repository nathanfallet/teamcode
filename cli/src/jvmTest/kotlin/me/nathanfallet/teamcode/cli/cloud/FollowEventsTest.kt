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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `teamcode cloud events --follow`: which stream failures reconnect and which stop. */
class FollowEventsTest {
    /** Stands for the process exit; an Error so the follow loop's own `catch (Exception)` cannot swallow it. */
    private class Exited(val code: Int) : Error()

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
            val exited = assertFailsWith<Exited> { followEvents(it, "s1", 4, { }, exit = { code -> throw Exited(code) }) { notice -> notices += notice } }
            assertEquals(1, exited.code)
        }
        assertEquals(1, requests)
        assertTrue(notices.single().startsWith("The event after 4 cannot be read"), notices.toString())
    }
}
