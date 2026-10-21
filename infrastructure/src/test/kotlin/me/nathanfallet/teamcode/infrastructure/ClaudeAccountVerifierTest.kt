package me.nathanfallet.teamcode.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.domain.accounts.ProviderCredentials
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.providers.ClaudeAccountVerifier
import kotlin.test.*

class ClaudeAccountVerifierTest {
    @Test fun identityComesFromProviderAndRequiresMax() = runTest {
        var max = true
        val http = HttpClient(MockEngine { request ->
            assertEquals("https://api.anthropic.com/api/oauth/profile", request.url.toString())
            assertEquals("Bearer synthetic-access", request.headers[HttpHeaders.Authorization])
            respond("""{"account":{"uuid":"provider-account","display_name":"Verified","email":"ada@example.com","has_claude_max":$max},"organization":{"uuid":"provider-organization"}}""", HttpStatusCode.OK)
        }) { install(HttpTimeout); followRedirects = false }
        http.use {
            val verifier = ClaudeAccountVerifier(it)
            val credentials = ProviderCredentials("synthetic-access", "synthetic-refresh", 10000)
            val account = verifier.verify("team", credentials)
            assertEquals("provider-account", account.identity.accountId)
            assertEquals("provider-organization", account.identity.organizationId)
            assertEquals("ada@example.com", account.email)
            max = false
            assertEquals("max_subscription_required", assertFailsWith<TeamFailure> { verifier.verify("team", credentials) }.code)
        }
    }
    @Test fun anEmailThatDoesNotLookLikeOneIsDroppedWithoutFailingVerification() = runTest {
        val http = HttpClient(MockEngine { respond("""{"account":{"uuid":"a","display_name":"Verified","email":"not an email","has_claude_max":true},"organization":{"uuid":"o"}}""", HttpStatusCode.OK) }) { install(HttpTimeout) }
        http.use { assertNull(ClaudeAccountVerifier(it).verify("team", ProviderCredentials("synthetic-access", "synthetic-refresh", 10000)).email) }
    }
    @Test fun unexpectedProviderBodiesAreBoundedAndNeverReturned() = runTest {
        val http = HttpClient(MockEngine { respond("secret".repeat(12000), HttpStatusCode.OK) }) { install(HttpTimeout) }
        http.use {
            val failure = assertFailsWith<TeamFailure> { ClaudeAccountVerifier(it).verify("team", ProviderCredentials("synthetic-access", "synthetic-refresh", 10000)) }
            assertFalse(failure.message!!.contains("secret"))
        }
    }
}
