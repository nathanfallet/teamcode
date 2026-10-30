package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class OpenRouterRoutingTest {
    private val routing = ResolveRouteUseCase()

    @Test fun aRuleCanTargetOpenRouterWithASlashedAndTaggedModel() {
        val rule = RouteRule("or", "google/*", listOf(RouteTarget("openrouter")))
        routing.validate(rule)
        assertEquals(listOf(RouteTarget("openrouter", "google/gemini-3-pro:free")), routing.resolve(listOf(rule), "google/gemini-3-pro:free", WireProtocol.MESSAGES))
        // An alias maps a short name to the full slug, and Claude Code keeps asking for the short one.
        val alias = RouteRule("fast", "gemini", listOf(RouteTarget("openrouter", "google/gemini-3-flash")))
        routing.validate(alias)
        assertEquals(listOf(RouteTarget("openrouter", "google/gemini-3-flash")), routing.resolve(listOf(alias), "gemini", WireProtocol.MESSAGES))
    }
    @Test fun noRuleStillMeansTheSubscriptionProvidersAndNeverOpenRouter() {
        assertEquals(listOf(RouteTarget("anthropic", "google/gemini-3-pro")), routing.resolve(emptyList(), "google/gemini-3-pro", WireProtocol.MESSAGES))
        assertEquals(listOf(RouteTarget("codex", "gpt-6-luna")), routing.resolve(emptyList(), "gpt-6-luna", WireProtocol.RESPONSES))
    }
    @Test fun anUnknownProviderOrAnUnsafeModelIsStillRefused() {
        for (bad in listOf(RouteRule("x", "*", listOf(RouteTarget("openai"))), RouteRule("x", "*", listOf(RouteTarget("openrouter", "a b"))),
            RouteRule("x", "*", listOf(RouteTarget("openrouter", "../etc"))).copy(pattern = "a b"))) {
            assertEquals("invalid_route", assertFailsWith<TeamFailure> { routing.validate(bad) }.code)
        }
    }
}
