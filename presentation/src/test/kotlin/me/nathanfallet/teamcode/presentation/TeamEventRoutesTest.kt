package me.nathanfallet.teamcode.presentation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.TeamEventView
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

/** The caller-wide SSE feed over a real local server (the test engine buffers a stream that never completes). */
class TeamEventRoutesTest {
    private val admin = Member("admin-id", "admin", Role.ADMIN, false, false)
    private val colleague = Member("colleague-id", "colleague", Role.MEMBER, false, false)
    private val other = Member("other-id", "other", Role.MEMBER, false, false)

    private class Journal : TeamEventStore {
        val events = mutableListOf<TeamEvent>()
        var oldestKept: Long? = null
        fun add(type: TeamEventType, owner: String, data: String = "{}") = events.add(TeamEvent(events.size + 1L, type, "subject-${events.size + 1}", owner, 5_000, data))
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun head(teamId: String) = events.size.toLong()
        override suspend fun oldest(teamId: String) = oldestKept ?: events.firstOrNull()?.seq
        override suspend fun read(teamId: String, audience: TeamEventAudience, after: Long, types: Set<TeamEventType>?, limit: Int): TeamEventPage {
            val visible = events.filter { it.seq > after && (types == null || it.type in types) && audience.sees(it.type, it.ownerId) }.take(limit)
            return TeamEventPage(visible, maxOf(events.size.toLong(), after), false)
        }
        override suspend fun purge(limit: Int) = 0
    }

    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) =
            receive(GenerationHead(200, "text/event-stream"), emptyFlow())
    }
    private fun team(): TeamService = object : TeamService by FakeTeam() {
        override suspend fun authenticate(token: String): Principal? = when (token) {
            "admin" -> Principal(admin, "admin-session", "default")
            "colleague" -> Principal(colleague, "colleague-session", "default")
            "other" -> Principal(other, "other-session", "default")
            "admin-cloud" -> Principal(admin, "admin-cloud-key", "default", setOf(ServiceScope.ADMIN_CLOUD))
            "admin-plain" -> Principal(admin, "admin-plain-key", "default", setOf(ServiceScope.CLOUD_READ))
            "inference-only" -> Principal(colleague, "inference-key", "default", setOf(ServiceScope.INFERENCE))
            "usage-only" -> Principal(colleague, "usage-key", "default", setOf(ServiceScope.USAGE_READ))
            else -> null
        }
    }

    /** Frames of the stream up to [count] events (comments and `retry` ignored); the connection is then closed. */
    private suspend fun HttpClient.read(token: String, count: Int, query: String = "", lastEventId: Long? = null): List<Pair<String, TeamEventView>> {
        val seen = mutableListOf<Pair<String, TeamEventView>>()
        withTimeout(15_000) {
            prepareGet("/api/v1/cloud/events/stream$query") {
                bearerAuth(token); lastEventId?.let { header("Last-Event-ID", it.toString()) }
            }.execute { response ->
                assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
                val channel = response.bodyAsChannel()
                var name: String? = null; var data: String? = null
                while (seen.size < count) {
                    val line = channel.readUTF8Line() ?: break
                    when {
                        line.startsWith("event: ") -> name = line.removePrefix("event: ")
                        line.startsWith("data: ") -> data = line.removePrefix("data: ")
                        line.isEmpty() && name != null && data != null -> {
                            if (name == "gap" || name == "denied") break
                            seen += name to Serialization.json.decodeFromString<TeamEventView>(data); name = null; data = null
                        }
                    }
                }
            }
        }
        return seen
    }

    @Test fun feedIsIsolatedResumableFilteredAndRefusesKeysWithoutScope() = runBlocking {
        val journal = Journal()
        journal.add(TeamEventType.SESSION_CREATED, "colleague-id", """{"sessionId":"s1"}""")
        journal.add(TeamEventType.CLOUD_TURN, "other-id", """{"phase":"started"}""")
        journal.add(TeamEventType.RUN_STARTED, "colleague-id", """{"runId":"r1"}""")
        journal.add(TeamEventType.CLOUD_TURN, "colleague-id", """{"phase":"completed"}""")
        journal.add(TeamEventType.RUN_STALE, "other-id")
        val service = TeamEventService(journal, sleep = { delay(20) })
        val server = embeddedServer(io.ktor.server.cio.CIO, port = 0, host = "127.0.0.1") {
            configureGateway(gateway, team = team(), publicOrigin = "http://localhost")
            routing { teamEventRoutes(service) }
        }.start()
        val origin = "http://localhost:${server.engine.resolvedConnectors().first().port}"
        val client = HttpClient(io.ktor.client.engine.cio.CIO) {
            expectSuccess = false
            defaultRequest { url(origin) }
        }
        try {
            // A member sees their own sessions and runs, with the event name, id and a typed body.
            val mine = client.read("colleague", 3, query = "?after=0")
            assertEquals(listOf("cloud.session.created", "run.started", "cloud.turn"), mine.map { it.first })
            assertEquals(listOf(1L, 3L, 4L), mine.map { it.second.seq })
            assertEquals(listOf("session", "run", "session"), mine.map { it.second.subject })
            assertTrue(mine.all { it.second.memberId == "colleague-id" }, "Nothing of another member")
            assertEquals("completed", mine.last().second.data["phase"]!!.jsonPrimitive.content)
            // An interactive administrator and an admin:cloud key (sessions only) see the team; an administrator's plain key only itself.
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), client.read("admin", 5, query = "?after=0").map { it.second.seq })
            assertEquals(listOf(1L, 2L, 4L), client.read("admin-cloud", 3, query = "?after=0").map { it.second.seq }, "admin:cloud has no usage:read, so no runs")
            // Resume: Last-Event-ID wins over ?after, and the missed events arrive once, in order, then the new ones.
            val resumed = async { client.read("colleague", 3, query = "?after=0", lastEventId = 3) }
            delay(300)
            journal.add(TeamEventType.SESSION_PAUSED, "colleague-id"); journal.add(TeamEventType.CLOUD_TURN, "other-id"); journal.add(TeamEventType.SESSION_ENDED, "colleague-id")
            assertEquals(listOf(4L, 6L, 8L), resumed.await().map { it.second.seq })
            // Without a cursor the stream starts at the newest event.
            val live = async { client.read("colleague", 1) }
            delay(300)
            journal.add(TeamEventType.CLOUD_TURN, "colleague-id")
            assertEquals(9L, live.await().single().second.seq)
            // Type filter, validated.
            assertEquals(listOf("cloud.turn", "cloud.turn"), client.read("colleague", 2, query = "?after=0&types=cloud.turn").map { it.first })
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/cloud/events/stream?types=cloud.nope") { bearerAuth("colleague") }.status)
            // Refusals are JSON errors before the stream starts.
            val ahead = client.get("/api/v1/cloud/events/stream") { bearerAuth("colleague"); header("Last-Event-ID", "999") }
            assertEquals(HttpStatusCode.BadRequest, ahead.status); assertTrue("invalid_cursor" in ahead.bodyAsText())
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/cloud/events/stream") { bearerAuth("colleague"); header("Last-Event-ID", "abc") }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/cloud/events/stream") { bearerAuth("inference-only") }.status, "A key without cloud:read")
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/cloud/events/stream") { bearerAuth("usage-only") }.status, "usage:read alone does not open the feed")
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/cloud/events/stream").status)
            // Purged history is announced.
            journal.oldestKept = 8
            val gap = withTimeout(15_000) {
                client.prepareGet("/api/v1/cloud/events/stream?after=2") { bearerAuth("admin") }.execute { response ->
                    val channel = response.bodyAsChannel()
                    var line = channel.readUTF8Line()
                    while (line != null && line != "event: gap") line = channel.readUTF8Line()
                    channel.readUTF8Line()
                }
            }
            assertEquals("data: {\"oldestSeq\":8}", gap)
        } finally {
            client.close(); server.stop(100, 500)
        }
    }
}
