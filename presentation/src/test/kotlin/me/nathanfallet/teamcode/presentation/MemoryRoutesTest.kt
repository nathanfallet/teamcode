package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.memory.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class MemoryRoutesTest {
    private class Store : MemoryStore {
        val created = mutableListOf<Pair<Principal, NewMemory>>()
        var lastIndex: List<Any?>? = null
        override suspend fun index(actor: Principal, scope: MemoryScope?, tags: List<String>, after: MemoryCursor?, limit: Int): MemoryPage {
            lastIndex = listOf(scope, tags, after, limit)
            return MemoryPage(listOf(MemoryIndexEntry("e1", MemoryScope.TEAM, "Title", "Summary", tags, 5)), MemoryCursor(5, "e1"))
        }
        override suspend fun search(actor: Principal, terms: List<String>, tags: List<String>, limit: Int) = listOf(MemoryIndexEntry("e2", MemoryScope.PERSONAL, terms.joinToString(), "S", tags, 6))
        override suspend fun read(actor: Principal, id: String) = error("unused")
        val statuses = mutableListOf<MemoryStatus>()
        val decisions = mutableListOf<Pair<String, Boolean>>()
        override suspend fun create(actor: Principal, entry: NewMemory, origin: MemoryOrigin, sessionId: String?, status: MemoryStatus): MemoryEntry {
            created += actor to entry; statuses += status
            return MemoryEntry("new", entry.scope, null, entry.title, entry.summary, entry.content, entry.tags, entry.refs, status, origin, "a", sessionId, null, null, 1, 1, 1, null, 0)
        }
        override suspend fun attachProposalWait(id: String, waitId: String) = Unit
        override suspend fun proposals(actor: Principal, limit: Int) = emptyList<MemoryEntry>()
        override suspend fun decide(actor: Principal, id: String, accept: Boolean): MemoryEntry {
            decisions += id to accept
            return MemoryEntry(id, MemoryScope.TEAM, null, "T", "S", "C", emptyList(), emptyList(), if (accept) MemoryStatus.ACTIVE else MemoryStatus.REJECTED, MemoryOrigin.AGENT, "a", null, null, null, 2, 1, 1, null, 0)
        }
        override suspend fun settle(limit: Int) = 0
        override suspend fun update(actor: Principal, id: String, update: MemoryUpdate) = error("unused")
        override suspend fun delete(actor: Principal, id: String) = Unit
    }
    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("unused")
    }
    private fun team(): TeamService {
        val interactive = FakeTeam()
        return object : TeamService by interactive {
            override suspend fun authenticate(token: String) = when (token) {
                "memory-key" -> interactive.authenticate("member")!!.copy(serviceScopes = setOf(ServiceScope.MEMORY))
                "other-key" -> interactive.authenticate("member")!!.copy(serviceScopes = setOf(ServiceScope.INFERENCE, ServiceScope.TODO_SESSION))
                else -> interactive.authenticate(token)
            }
        }
    }
    private val json = ContentType.Application.Json
    private val create = """{"title":"Exposed only","summary":"Queries use the DSL","content":"No raw SQL.","tags":["database"]}"""

    @Test fun onlyTheMemoryRightReachesTheMemoryAndAnAgentNeverWritesTheTeam() = testApplication {
        val store = Store()
        application { configureGateway(gateway, team = team(), memory = MemoryService(store)) }
        // A key without `memory`, whatever else it holds, is refused before the store; a member and a key with it are served.
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/memory/index") { bearerAuth("other-key") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/memory") { bearerAuth("other-key"); contentType(json); setBody(create) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/memory/any") { bearerAuth("other-key") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/memory/index?scope=team&tags=a,b&limit=5") { bearerAuth("member") }.status)
        assertEquals(listOf(MemoryScope.TEAM, listOf("a", "b"), null, 5), store.lastIndex)
        val page = client.get("/api/v1/memory/index") { bearerAuth("memory-key") }
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains(""""next":"5:e1""""))
        val search = client.get("/api/v1/memory/search?q=Exposed+DSL") { bearerAuth("memory-key") }
        assertTrue(search.bodyAsText().contains("exposed, dsl"))

        val personal = client.post("/api/v1/memory") { bearerAuth("memory-key"); contentType(json); setBody(create) }
        assertEquals(HttpStatusCode.Created, personal.status)
        assertEquals(MemoryScope.PERSONAL, store.created.single().second.scope)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/memory") { bearerAuth("memory-key"); contentType(json); setBody(create.replace("{", """{"scope":"team",""")) }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/memory") { bearerAuth("member"); contentType(json); setBody(create.replace("{", """{"scope":"team",""")) }.status)
        assertEquals(1, store.created.count { it.second.scope == MemoryScope.TEAM }, "only the member's team entry reached the store")
    }

    @Test fun inputsAreValidatedBeforeTheStore() = testApplication {
        val store = Store()
        application { configureGateway(gateway, team = team(), memory = MemoryService(store)) }
        for (path in listOf("/api/v1/memory/index?scope=nobody", "/api/v1/memory/index?after=garbage", "/api/v1/memory/index?limit=many", "/api/v1/memory/index?tags=UPPER", "/api/v1/memory/search?q=a", "/api/v1/memory/search"))
            assertEquals(HttpStatusCode.BadRequest, client.get(path) { bearerAuth("member") }.status, path)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/memory/bad%20id") { bearerAuth("member") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/memory") { bearerAuth("member"); contentType(json); setBody(create.replace("No raw SQL.", "password = correct-horse-battery")) }.status.let { if (it == HttpStatusCode.UnprocessableEntity) HttpStatusCode.BadRequest else it })
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/memory") { bearerAuth("member"); contentType(json); setBody(create.replace("Exposed only", "")) }.status)
        assertTrue(store.created.isEmpty())
        assertTrue(client.get("/api/v1/capabilities") { bearerAuth("member") }.bodyAsText().contains("memory.v1"))
    }

    @Test fun aCallThroughAnAgentRelayProposesTheTeamEntryAndOnlyAMemberDecides() = testApplication {
        val store = Store()
        val waits = object : MemoryProposalWaits {
            override suspend fun session(actor: Principal, requested: String?) = requested ?: throw TeamFailure("memory_proposal_session", 400, "no session")
            override suspend fun open(actor: Principal, sessionId: String, entry: MemoryEntry) = "wait-1"
        }
        application { configureGateway(gateway, team = team(), memory = MemoryService(store, waits)) }
        val team = create.replace("{", """{"scope":"team","sessionId":"s-1",""")
        // The member's own login, through the agent relay's header, proposes; without the header it writes directly.
        val proposed = client.post("/api/v1/memory") { bearerAuth("member"); header("x-teamcode-origin", "agent"); contentType(json); setBody(team) }
        assertEquals(HttpStatusCode.Created, proposed.status); assertTrue(proposed.bodyAsText().contains(""""status":"proposed""""))
        client.post("/api/v1/memory") { bearerAuth("member"); contentType(json); setBody(team) }
        assertEquals(listOf(MemoryStatus.PROPOSED, MemoryStatus.ACTIVE), store.statuses)
        // No session to ask about: refused before anything is stored.
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/memory") { bearerAuth("member"); header("x-teamcode-origin", "agent"); contentType(json); setBody(create.replace("{", """{"scope":"team",""")) }.status)
        // Deciding is for a signed-in member; keys, even with `memory`, are refused.
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/memory/proposals/p1/accept") { bearerAuth("memory-key") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/memory/proposals") { bearerAuth("memory-key") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/memory/proposals") { bearerAuth("member") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/memory/proposals/p1/reject") { bearerAuth("member") }.status)
        assertEquals(listOf("p1" to false), store.decisions)
    }
}
