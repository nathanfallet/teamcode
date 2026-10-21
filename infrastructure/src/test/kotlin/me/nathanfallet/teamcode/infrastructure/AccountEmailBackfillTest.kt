package me.nathanfallet.teamcode.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.*
import java.util.Base64
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.infrastructure.database.AccountEmailBackfill
import me.nathanfallet.teamcode.infrastructure.providers.*
import kotlin.test.*

class AccountEmailBackfillTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val previous = ProviderCredentials("old-access-secret", "old-refresh-secret", 1)
    private fun jwt(claims: String) = "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".s"
    private fun idToken(user: String = "user-1", workspace: String = "workspace-1", email: String = "grace@example.com") =
        jwt("""{"https://api.openai.com/auth":{"chatgpt_account_id":"$workspace","chatgpt_user_id":"$user"},"https://api.openai.com/profile":{"email":"$email"}}""")
    private fun claude(email: String? = null) = AccountRecord("account-1", AccountIdentity("team", "anthropic", "claude-account", "claude-org"), "Claude", 1, false, null, 0, AccountState.READY, email)
    private fun codex(email: String? = null) = AccountRecord("account-2", AccountIdentity("team", "codex", "user-1", "workspace-1"), "Codex", 1, false, null, 0, AccountState.READY, email)

    private class Harness(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) {
        val requests = mutableListOf<String>()
        val written = mutableListOf<Pair<String, String>>()
        val logs = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob())
        val http = HttpClient(MockEngine { request -> requests += request.url.encodedPath; handler(request) }) { install(HttpTimeout) }
        val backfill = AccountEmailBackfill(listOf(ClaudeTransport(http), CodexTransport(http)),
            mapOf("anthropic" to ClaudeAccountVerifier(http), "codex" to CodexAccountVerifier(http)),
            { _, id, email -> written += id to email; true }, scope, { logs += it })
        suspend fun settle() { scope.coroutineContext[Job]!!.children.toList().joinAll() }
    }

    @Test fun aCodexRefreshReadsTheAddressFromItsIdToken() = runBlocking {
        val h = Harness { respond("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"id_token":"${idToken()}"}""", headers = json) }
        val renewed = h.backfill.renew(codex(), previous); h.settle()
        assertEquals("new-access", renewed.accessToken)
        assertEquals(listOf("account-2" to "grace@example.com"), h.written)
        assertEquals(listOf("/oauth/token"), h.requests, "The token response is enough: no extra provider call")
    }

    @Test fun aClaudeRefreshTakesTheAddressFromTheTokenResponseOtherwiseFromTheProfile() = runBlocking {
        val inline = Harness { respond("""{"access_token":"a","refresh_token":"r","expires_in":3600,"account":{"uuid":"claude-account","email_address":"ada@example.com"},"organization":{"uuid":"claude-org"}}""", headers = json) }
        inline.backfill.renew(claude(), previous); inline.settle()
        assertEquals(listOf("account-1" to "ada@example.com"), inline.written)
        assertEquals(listOf("/v1/oauth/token"), inline.requests)

        val profile = Harness { request ->
            if (request.url.encodedPath == "/api/oauth/profile") {
                assertEquals("Bearer new-access", request.headers[HttpHeaders.Authorization], "The profile is read with the renewed token")
                respond("""{"account":{"uuid":"claude-account","display_name":"Ada","email":"ada@example.com","has_claude_max":true},"organization":{"uuid":"claude-org"}}""", headers = json)
            } else respond("""{"access_token":"new-access","refresh_token":"r","expires_in":3600}""", headers = json)
        }
        profile.backfill.renew(claude(), previous); profile.settle()
        assertEquals(listOf("account-1" to "ada@example.com"), profile.written)
        assertEquals(listOf("/v1/oauth/token", "/api/oauth/profile"), profile.requests)
    }

    @Test fun anAccountThatHasAnAddressIsNeitherLookedUpNorOverwritten() = runBlocking {
        val h = Harness { respond("""{"access_token":"a","refresh_token":"r","expires_in":3600,"account":{"uuid":"claude-account","email_address":"other@example.com"},"organization":{"uuid":"claude-org"}}""", headers = json) }
        h.backfill.renew(claude("ada@example.com"), previous); h.settle()
        assertTrue(h.written.isEmpty())
        assertEquals(listOf("/v1/oauth/token"), h.requests)
    }

    @Test fun aFailingProfileNeverBreaksTheRefreshAndIsNotRetried() = runBlocking {
        val h = Harness { request ->
            if (request.url.encodedPath == "/api/oauth/profile") respond("down", HttpStatusCode.InternalServerError)
            else respond("""{"access_token":"new-access","refresh_token":"r","expires_in":3600}""", headers = json)
        }
        assertEquals("new-access", h.backfill.renew(claude(), previous).accessToken)
        h.settle()
        assertEquals("new-access", h.backfill.renew(claude(), previous).accessToken)
        h.settle()
        assertTrue(h.written.isEmpty())
        assertEquals(1, h.requests.count { it == "/api/oauth/profile" }, "One attempt per account")
        assertEquals(1, h.logs.size)
    }

    @Test fun aFailedSampleLookupDoesNotBlockTheFreeAnswerOfALaterRefresh() = runBlocking {
        val h = Harness { request ->
            if (request.url.encodedPath == "/api/oauth/profile") respond("down", HttpStatusCode.InternalServerError)
            else respond("""{"access_token":"a","refresh_token":"r","expires_in":3600,"account":{"uuid":"claude-account","email_address":"ada@example.com"},"organization":{"uuid":"claude-org"}}""", headers = json)
        }
        h.backfill.schedule(claude(), previous); h.settle()
        assertTrue(h.written.isEmpty())
        assertTrue(h.logs.single().contains("account-1") && h.logs.none { "@" in it || "secret" in it })
        h.backfill.renew(claude(), previous); h.settle()
        assertEquals(listOf("account-1" to "ada@example.com"), h.written)
    }

    @Test fun anotherIdentityIsRefusedWithoutLoggingSecretsOrAddresses() = runBlocking {
        val codexH = Harness { respond("""{"access_token":"new-access-secret","refresh_token":"r","expires_in":3600,"id_token":"${idToken(user = "someone-else")}"}""", headers = json) }
        codexH.backfill.renew(codex(), previous); codexH.settle()
        val claudeH = Harness { respond("""{"access_token":"new-access-secret","refresh_token":"r","expires_in":3600,"account":{"uuid":"intruder","email_address":"ada@example.com"},"organization":{"uuid":"claude-org"}}""", headers = json) }
        claudeH.backfill.renew(claude(), previous); claudeH.settle()
        val viaProfile = Harness { request ->
            if (request.url.encodedPath == "/api/oauth/profile") respond("""{"account":{"uuid":"intruder","email":"ada@example.com","has_claude_max":true},"organization":{"uuid":"claude-org"}}""", headers = json)
            else respond("""{"access_token":"new-access-secret","refresh_token":"r","expires_in":3600}""", headers = json)
        }
        viaProfile.backfill.renew(claude(), previous); viaProfile.settle()
        for (h in listOf(codexH, claudeH, viaProfile)) {
            assertTrue(h.written.isEmpty())
            assertEquals(1, h.logs.size)
            assertTrue(h.logs.none { "@" in it || "secret" in it })
        }
    }
}
