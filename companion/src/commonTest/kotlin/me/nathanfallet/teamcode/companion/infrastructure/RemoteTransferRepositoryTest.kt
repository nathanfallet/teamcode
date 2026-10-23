package me.nathanfallet.teamcode.companion.infrastructure

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakePlatform
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.TransferDirection
import me.nathanfallet.teamcode.companion.domain.models.TransferRequest
import me.nathanfallet.teamcode.companion.domain.models.TransferState
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.companion.infrastructure.repositories.AccountCalls
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteTransferRepository
import kotlin.test.*

/** Transfers over a scripted server: what is sent, the mapping, and refusals kept with their codes. */
class RemoteTransferRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val answers = ArrayDeque<Pair<HttpStatusCode, String>>()
    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit) = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val (status, body) = answers.removeFirstOrNull() ?: (HttpStatusCode.ServiceUnavailable to "{}")
                respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val transfers = RemoteTransferRepository(AccountCalls(TeamCodeClientProvider(engine, credentials(), FakePlatform), InMemoryAccountRepository(listOf(account("a1")))))

    private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = answers.addLast(status to body)
    private fun transferJson(id: String, state: String, extra: String = "") =
        """{"id":"$id","sessionId":"s1","fromMemberId":"m1","toMemberId":"m2","state":"$state","initiatedBy":{"initiatorId":"m1","credentialId":"c","effectiveMemberId":"m1"},
            "resume":true,"force":false,"pendingInputs":"cancel","createdAt":1,"updatedAt":2,"expiresAt":99$extra}"""

    @Test fun aTransferSendsItsKeyAndOptionsAndMapsTheAnswer() = runTest {
        json(transferJson("t1", "accepted"), HttpStatusCode.Accepted)
        val transfer = transfers.transfer("a1", "s1", TransferRequest("m2", resume = true, force = true), "key-1")
        assertEquals(TransferState.ACCEPTED, transfer.state); assertTrue(transfer.resume); assertEquals(99L, transfer.expiresAt)
        val request = requests.single()
        assertEquals("/api/v1/cloud/sessions/s1/transfer", request.url.encodedPath)
        assertEquals("key-1", request.headers["Idempotency-Key"])
        val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue(body.contains("\"toMemberId\":\"m2\"") && body.contains("\"resume\":true") && body.contains("\"force\":true"), body)
    }

    @Test fun anIneligibleRecipientIsRefusedWithEveryProblem() = runTest {
        json("""{"error":{"type":"recipient_ineligible","message":"The recipient cannot own this session: profile_unassigned, model_not_allowed."}}""", HttpStatusCode.UnprocessableEntity)
        val refusal = assertFailsWith<CompanionException.TransferRefused> { transfers.transfer("a1", "s1", TransferRequest("m2"), "k") }
        assertEquals("recipient_ineligible", refusal.code)
        assertEquals(listOf("profile_unassigned", "model_not_allowed"), refusal.problems)
        json("""{"error":{"type":"transfer_pending","message":"x"}}""", HttpStatusCode.Conflict)
        assertEquals("transfer_pending", assertFailsWith<CompanionException.TransferRefused> { transfers.cancel("a1", "t1") }.code)
        // A session or transfer that is gone stays "not found", never a refusal.
        json("""{"error":{"type":"not_found","message":"x"}}""", HttpStatusCode.NotFound)
        assertFailsWith<CompanionException.NotFound> { transfers.accept("a1", "t1", null) }
    }

    @Test fun theListAsksForOneDirectionAndKeepsAFailureCode() = runTest {
        json("""{"transfers":[${transferJson("t2", "failed", ",\"failure\":{\"code\":\"recipient_no_quota\",\"message\":\"x\"}")}, ${transferJson("t1", "later_state")}],"nextBefore":"t1"}""")
        val page = transfers.transfers("a1", TransferDirection.OUTGOING, before = "t3")
        assertEquals(listOf(TransferState.FAILED, TransferState.UNKNOWN), page.transfers.map { it.state })
        assertEquals("recipient_no_quota", page.transfers[0].problemCode)
        assertEquals("t1", page.nextBefore)
        val url = requests.single().url
        assertEquals("outgoing", url.parameters["direction"]); assertEquals("t3", url.parameters["before"]); assertNull(url.parameters["scope"])
    }

    @Test fun recipientsComeFromTheirOwnListAndARefusalKeepsItsCode() = runTest {
        json("""{"recipients":[{"id":"m1","username":"ada","administrator":true},{"id":"m2","username":"bob","administrator":false}]}""")
        val recipients = transfers.recipients("a1")
        assertEquals(listOf("ada" to true, "bob" to false), recipients.map { it.username to it.administrator })
        assertTrue(recipients.all { it.available })
        assertEquals("/api/v1/cloud/transfer-recipients", requests.single().url.encodedPath)
        json("""{"error":{"type":"transfer_disabled","message":"x"}}""", HttpStatusCode.Forbidden)
        assertEquals("transfer_disabled", assertFailsWith<CompanionException.TransferRefused> { transfers.recipients("a1") }.code)
    }

    @Test fun membersAreAvailableOnlyWhenEnabledAndSetUp() = runTest {
        json("""[{"id":"m1","username":"ada","role":"ADMIN","disabled":false,"passwordChangeRequired":false},
            {"id":"m2","username":"bob","role":"MEMBER","disabled":true,"passwordChangeRequired":false},
            {"id":"m3","username":"cy","role":"MEMBER","disabled":false,"passwordChangeRequired":true}]""")
        val members = transfers.members("a1")
        assertEquals(listOf(true, false, false), members.map { it.available })
        assertEquals(listOf(true, false, false), members.map { it.administrator })
    }
}
