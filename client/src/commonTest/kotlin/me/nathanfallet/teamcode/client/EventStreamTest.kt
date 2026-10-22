package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.api.models.TeamEventView
import kotlin.test.*

/** The session stream and the caller-wide feed, both read by the shared SSE reader, against a scripted server. */
class EventStreamTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(vararg bodies: String, status: HttpStatusCode = HttpStatusCode.OK): TeamCodeClient {
        val queue = ArrayDeque(bodies.toList())
        return TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
            HttpClient(MockEngine { request -> requests += request; stream(queue.removeFirst(), status) }) { configure(this) }
        }
    }

    private fun MockRequestHandleScope.stream(body: String, status: HttpStatusCode) = respond(
        ByteReadChannel(body), status,
        headersOf(HttpHeaders.ContentType, if (status.isSuccess()) "text/event-stream" else "application/json"),
    )

    private fun teamEvent(seq: Long, type: String = "cloud.turn", sessionId: String = "s1") =
        "id: $seq\nevent: $type\ndata: {\"seq\":$seq,\"type\":\"$type\",\"subject\":\"session\",\"subjectId\":\"$sessionId\"," +
            "\"memberId\":\"m1\",\"at\":1700000000000,\"data\":{\"sessionId\":\"$sessionId\",\"phase\":\"completed\"}}\n\n"

    private fun cloudEvent(seq: Long, type: String = "message.agent") =
        "id: $seq\nevent: $type\ndata: {\"seq\":$seq,\"type\":\"$type\",\"at\":1700000000000,\"epoch\":1,\"data\":{\"text\":\"line $seq\"}}\n\n"

    @Test fun theTeamFeedDeliversEventsInOrderAndSkipsKeepAlives() = runTest {
        val seen = mutableListOf<TeamEventView>()
        val body = "retry: 2000\n\n" + teamEvent(4) + ": keep-alive\n\n" + teamEvent(5, "cloud.session.paused") +
            teamEvent(6, "cloud.session.waiting") + "event: denied\ndata: {\"code\":\"unauthorized\",\"message\":\"Denied.\"}\n\n"
        client(body).use { client ->
            assertEquals(CloudStreamEnd.DENIED, client.followTeamEvents(after = 3, types = setOf("cloud.turn", "cloud.session.paused", "cloud.session.waiting")) { seen += it })
        }
        assertEquals(listOf(4L, 5L, 6L), seen.map { it.seq })
        // A type this client does not know yet is still handed over: the list of types is open.
        assertEquals("cloud.session.waiting", seen.last().type)
        assertEquals("s1", seen.first().subjectId)
        val request = requests.single()
        assertEquals("/api/v1/cloud/events/stream", request.url.encodedPath)
        assertEquals("3", request.headers["Last-Event-ID"])
        assertEquals("cloud.session.paused,cloud.session.waiting,cloud.turn", request.url.parameters["types"])
        assertEquals("text/event-stream", request.headers[HttpHeaders.Accept])
        assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
    }

    @Test fun theTeamFeedStartsFromNowWithoutACursor() = runTest {
        client("event: denied\ndata: {}\n\n").use { client -> client.followTeamEvents(after = null) { } }
        val request = requests.single()
        assertNull(request.headers["Last-Event-ID"])
        assertNull(request.url.parameters["types"])
    }

    @Test fun aGapIsReportedBeforeTheEventsThatFollowIt() = runTest {
        val order = mutableListOf<String>()
        val body = "retry: 2000\n\nevent: gap\ndata: {\"oldestSeq\":120}\n\n" + teamEvent(120) + teamEvent(121) + "event: denied\ndata: {}\n\n"
        client(body).use { client ->
            client.followTeamEvents(after = 7, onGap = { order += "gap $it" }) { order += "event ${it.seq}" }
        }
        assertEquals(listOf("gap 120", "event 120", "event 121"), order)
    }

    @Test fun aCutFeedResumesAfterTheLastHandledEventWithoutLossOrDuplicate() = runTest {
        // The first connection dies in the middle of event 3; the server replays from the cursor it is sent.
        val cut = teamEvent(1) + teamEvent(2) + teamEvent(3).take(30)
        val resumed = teamEvent(3) + teamEvent(4) + "event: denied\ndata: {}\n\n"
        val seen = mutableListOf<Long>()
        var cursor = 0L
        client(cut, resumed).use { client ->
            var end: CloudStreamEnd? = null
            var attempts = 0
            while (end == null) {
                attempts++
                end = try {
                    client.followTeamEvents(after = cursor) { event -> seen += event.seq; cursor = event.seq }
                } catch (error: TeamCodeException) {
                    assertEquals(0, error.statusCode)
                    null
                }
            }
            assertEquals(2, attempts)
            assertEquals(CloudStreamEnd.DENIED, end)
        }
        assertEquals(listOf(1L, 2L, 3L, 4L), seen)
        assertEquals(listOf("0", "2"), requests.map { it.headers["Last-Event-ID"] })
    }

    @Test fun aRefusedFeedCarriesTheApiErrorCode() = runTest {
        client("""{"error":{"type":"invalid_cursor","message":"Ahead."}}""", status = HttpStatusCode.BadRequest).use { client ->
            val error = assertFailsWith<TeamCodeException> { client.followTeamEvents(after = 999) { fail("No event is expected.") } }
            assertEquals(400, error.statusCode)
            assertEquals("invalid_cursor", error.code)
        }
    }

    @Test fun invalidCursorsAndTypesAreRejectedBeforeAnyRequest() = runTest {
        client().use { client ->
            assertFailsWith<IllegalArgumentException> { client.followTeamEvents(after = -1) { } }
            assertFailsWith<IllegalArgumentException> { client.followTeamEvents(after = null, types = setOf("cloud.turn&after=0")) { } }
            assertFailsWith<IllegalArgumentException> { client.followTeamEvents(after = null, types = (1..65).map { "type$it" }.toSet()) { } }
            // Control frames, never event types.
            assertFailsWith<IllegalArgumentException> { client.followTeamEvents(after = null, types = setOf("gap")) { } }
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun theSessionStreamStillEndsAndDeniesAsBefore() = runTest {
        val seen = mutableListOf<Long>()
        val ended = "retry: 2000\n\n" + cloudEvent(3) + ": keep-alive\n\n" + cloudEvent(4, "turn") + "event: end\ndata: {}\n\n"
        val denied = cloudEvent(5) + "event: denied\ndata: {\"code\":\"cloud_permission_denied\",\"message\":\"Denied.\"}\n\n"
        client(ended, denied).use { client ->
            assertEquals(CloudStreamEnd.ENDED, client.followCloudEvents("s1", 2) { seen += it.seq })
            assertEquals(CloudStreamEnd.DENIED, client.followCloudEvents("s1", 4) { seen += it.seq })
        }
        assertEquals(listOf(3L, 4L, 5L), seen)
        assertEquals(listOf("/api/v1/cloud/sessions/s1/events/stream"), requests.map { it.url.encodedPath }.distinct())
        assertEquals(listOf("2", "4"), requests.map { it.headers["Last-Event-ID"] })
    }

    @Test fun theSessionStreamJoinsDataLinesAndIgnoresUnnamedFrames() = runTest {
        val texts = mutableListOf<String>()
        val body = "data: {\"ignored\":true}\n\n" +
            "id: 9\nevent: message.agent\ndata: {\"seq\":9,\"type\":\"message.agent\",\n" +
            "data:\"at\":1,\"epoch\":1,\"data\":{\"text\":\"two lines\"}}\n\n" + "event: end\ndata: {}\n\n"
        client(body).use { client -> assertEquals(CloudStreamEnd.ENDED, client.followCloudEvents("s1", 0) { texts += it.data.toString() }) }
        assertEquals(listOf("{\"text\":\"two lines\"}"), texts)
    }

    @Test fun aClosedSessionStreamThrowsSoTheCallerReconnects() = runTest {
        client(cloudEvent(1)).use { client ->
            val error = assertFailsWith<TeamCodeException> { client.followCloudEvents("s1", 0) { } }
            assertEquals(0, error.statusCode)
            assertNull(error.code) // Transient: reconnect.
        }
    }

    @Test fun theReaderBoundsTheDataOfOneEvent() = runTest {
        val body = "event: message.agent\n" + "data: ${"x".repeat(100)}\n".repeat(20) + "\n"
        val error = assertFailsWith<TeamCodeException> { readServerSentEvents(ByteReadChannel(body), maxData = 1_000) { true } }
        assertEquals(0, error.statusCode)
        assertEquals(TeamCodeClient.MALFORMED_EVENT, error.code)
    }

    @Test fun invalidUtf8IsDecodedWithAReplacementCharacter() = runTest {
        val body = "event: message.agent\ndata: ".encodeToByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + "\n\n".encodeToByteArray()
        val frames = mutableListOf<ServerSentEvent>()
        readServerSentEvents(ByteReadChannel(body)) { frames += it; false }
        assertEquals("\uFFFD(", frames.single().data)
    }

    @Test fun theReaderParsesFieldsWithoutASpaceOrAValue() = runTest {
        val frames = mutableListOf<ServerSentEvent>()
        readServerSentEvents(ByteReadChannel("id:7\nevent:gap\ndata\ndata:{}\n\nevent:\ndata: unnamed\n\nevent: stop\n\n")) { frames += it; it.event != "stop" }
        assertEquals("gap", frames[0].event)
        assertEquals("\n{}", frames[0].data)
        // An empty event name is no name: the frame is an unnamed one.
        assertNull(frames[1].event)
        assertEquals(3, frames.size)
    }

    @Test fun anOverLongLineIsAMalformedEvent() = runTest {
        val error = assertFailsWith<TeamCodeException> { readServerSentEvents(ByteReadChannel("data: ${"x".repeat(200)}\n\n"), maxLine = 100) { true } }
        assertEquals(0, error.statusCode)
        assertEquals(TeamCodeClient.MALFORMED_EVENT, error.code)
    }

    @Test fun aMalformedGapOrEventIsReportedAsMalformedEvent() = runTest {
        client("event: gap\ndata: {}\n\n", "event: cloud.turn\ndata: not json\n\n").use { client ->
            // A distinct code: reconnecting would replay the same event, so the caller must not retry blindly.
            assertEquals(TeamCodeClient.MALFORMED_EVENT, assertFailsWith<TeamCodeException> { client.followTeamEvents(after = 1) { } }.code)
            val error = assertFailsWith<TeamCodeException> { client.followTeamEvents(after = 1) { } }
            assertEquals(TeamCodeClient.MALFORMED_EVENT, error.code)
            // The decoder's message quotes the payload: it is not kept, so the exception stays safe to print.
            assertNull(error.cause)
        }
    }

    @Test fun theTeamFeedHasNoEndEvent() = runTest {
        // `end` belongs to a session's stream; on the feed it is an ordinary type without data, and an unnamed frame is skipped.
        client("event: end\ndata: \n\n" + "data: {\"unnamed\":true}\n\n" + teamEvent(2) + "event: denied\ndata: {}\n\n").use { client ->
            val seen = mutableListOf<Long>()
            assertEquals(CloudStreamEnd.DENIED, client.followTeamEvents(after = 1) { seen += it.seq })
            assertEquals(listOf(2L), seen)
        }
    }

    @Test fun aConnectionFailureReachesTheCallerAsTheEngineReportsIt() = runTest {
        TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
            HttpClient(MockEngine { throw IllegalStateException("offline") }) { configure(this) }
        }.use { client ->
            assertEquals("offline", assertFailsWith<IllegalStateException> { client.followTeamEvents(after = 1) { } }.message)
        }
    }

    @Test fun theHandlerOwnFailureIsNotDisguisedAsANetworkFailure() = runTest {
        client(teamEvent(2)).use { client ->
            val error = assertFailsWith<IllegalStateException> { client.followTeamEvents(after = 1) { error("handler bug") } }
            assertEquals("handler bug", error.message)
        }
    }
}
