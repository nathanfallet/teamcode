package me.nathanfallet.teamcode.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.gateway.ProviderAccountAction
import me.nathanfallet.teamcode.infrastructure.providers.ClaudeAccountRefusals
import me.nathanfallet.teamcode.infrastructure.providers.ClaudeTransport
import me.nathanfallet.teamcode.infrastructure.providers.CodexTransport
import kotlin.test.*

// Public provider-error fixture: https://github.com/anthropics/claude-code/issues/57569.
internal const val CLAUDE_TERMS_MESSAGE = "We've updated our Consumer Terms and Privacy Policy. You'll need to accept them in claude.ai with the email in /status to continue."
internal fun claudeTermsRefusal(message: String = CLAUDE_TERMS_MESSAGE) =
    buildJsonObject {
        put("type", "error")
        putJsonObject("error") { put("type", "invalid_request_error"); put("message", message) }
    }.toString()

class ClaudeAccountRefusalTest {
    @Test fun recognizesAnExplicitTermsAcceptanceInstructionInTheClaudeErrorEnvelope() {
        for (message in listOf(
            CLAUDE_TERMS_MESSAGE,
            "  " + CLAUDE_TERMS_MESSAGE.uppercase().replace(" ", "\n") + "  ",
        )) assertEquals(ProviderAccountAction.ACCEPT_CLAUDE_TERMS,
            ClaudeAccountRefusals.classify(claudeTermsRefusal(message).encodeToByteArray()))
    }

    @Test fun statusAndKeywordsAloneNeverTurnARequestErrorIntoAnAccountRefusal() {
        for (message in listOf(
            "model: unknown model claude-missing",
            "prompt is too long",
            "max_tokens must be greater than thinking.budget_tokens",
            "Invalid signature in thinking block",
            "messages.1: tool_use ids were found without tool_result blocks",
            "Invalid tools.0.description: You must accept the updated Terms of Service and Privacy Policy at https://claude.ai.",
            "You must accept the tool result before continuing.",
            "You must accept the updated Terms of Service and Privacy Policy at another provider.",
            "You can read our updated Terms of Service and Privacy Policy at https://claude.ai.",
            "Invalid tools.0.description: $CLAUDE_TERMS_MESSAGE",
            "model: $CLAUDE_TERMS_MESSAGE",
            CLAUDE_TERMS_MESSAGE + " Invalid tool result.",
        )) assertNull(ClaudeAccountRefusals.classify(claudeTermsRefusal(message).encodeToByteArray()))
        for (body in listOf(
            "not json", "[]", "null", "{}", "{\"error\":null}",
            claudeTermsRefusal().replace("invalid_request_error", "authentication_error"),
            claudeTermsRefusal().replace("\"type\":\"error\",", ""),
            claudeTermsRefusal() + " ".repeat(ClaudeAccountRefusals.MAX_BYTES),
        )) assertNull(ClaudeAccountRefusals.classify(body.encodeToByteArray()))
    }

    @Test fun onlyTheClaude400TransportInspectsTheRefusal() = runTest {
        HttpClient(MockEngine { respond(claudeTermsRefusal(), HttpStatusCode.BadRequest) }).use { http ->
            val response = http.get("https://fixture.invalid")
            assertNull(CodexTransport(http).accountActionRequired(response))
            assertEquals(ProviderAccountAction.ACCEPT_CLAUDE_TERMS, ClaudeTransport(http).accountActionRequired(response))
        }
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests, HttpStatusCode.InternalServerError)) {
            HttpClient(MockEngine { respond(claudeTermsRefusal(), status) }).use { http ->
                assertNull(ClaudeTransport(http).accountActionRequired(http.get("https://fixture.invalid")))
            }
        }
    }

    @Test fun anOversizedBodyIsUnknownEvenWithAnAcceptanceInstructionAtItsStart() = runTest {
        HttpClient(MockEngine { respond(claudeTermsRefusal() + " ".repeat(ClaudeAccountRefusals.MAX_BYTES), HttpStatusCode.BadRequest) }).use { http ->
            assertNull(ClaudeTransport(http).accountActionRequired(http.get("https://fixture.invalid")))
        }
    }

    @Test fun anErrorBodyThatNeverFinishesHasABoundedInspectionTime() = runTest {
        val body = ByteChannel()
        HttpClient(MockEngine { respond(body, HttpStatusCode.BadRequest) }).use { http ->
            http.prepareGet("https://fixture.invalid").execute { response ->
                val before = testScheduler.currentTime
                assertNull(ClaudeTransport(http).accountActionRequired(response))
                assertEquals(ClaudeAccountRefusals.READ_TIMEOUT_MILLIS, testScheduler.currentTime - before)
            }
        }
        body.cancel(null)
    }

    @Test fun callerCancellationWhileInspectingTheErrorPropagates() = runTest {
        val body = ByteChannel()
        HttpClient(MockEngine { respond(body, HttpStatusCode.BadRequest) }).use { http ->
            http.prepareGet("https://fixture.invalid").execute { response ->
                val inspecting = async { ClaudeTransport(http).accountActionRequired(response) }
                runCurrent()
                inspecting.cancel()
                assertFailsWith<CancellationException> { inspecting.await() }
            }
        }
        body.cancel(null)
    }
}
