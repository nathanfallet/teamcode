package me.nathanfallet.teamcode.domain.routing

import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.policy.matchesModelPattern

data class RouteTarget(val provider: String, val model: String? = null)
data class RouteRule(val id: String, val pattern: String, val targets: List<RouteTarget>, val priority: Int = 0, val enabled: Boolean = true, val version: Long = 0)
interface RoutingStore {
    suspend fun list(teamId: String): List<RouteRule>
    suspend fun save(actor: Principal, rule: RouteRule): RouteRule
    suspend fun delete(actor: Principal, id: String, version: Long)
}
class ResolveRouteUseCase {
    companion object {
        /** Subscription providers and the pay-as-you-go OpenRouter gateway; a route can name no other. */
        val PROVIDERS = setOf("anthropic", "codex", "openrouter")
    }
    fun validate(rule: RouteRule) {
        if (!Regex("[a-zA-Z0-9_-]{1,36}").matches(rule.id) ||
            !Regex("[a-zA-Z0-9*._:/-]{1,120}").matches(rule.pattern) || rule.targets.size !in 1..4 ||
            rule.targets.any { it.provider !in PROVIDERS || it.model?.let { model -> !Regex("[a-zA-Z0-9_.:/-]{1,120}").matches(model) } == true } ||
            rule.priority !in -100..100 || rule.version < 0) throw TeamFailure("invalid_route", 400, "Provide a model pattern and one to four explicit provider targets.")
    }
    fun resolve(rules: List<RouteRule>, model: String, protocol: WireProtocol): List<RouteTarget> {
        val rule = rules.filter { it.enabled }.sortedWith(compareByDescending<RouteRule> { it.priority }.thenBy { it.id }).firstOrNull {
            matchesModelPattern(it.pattern, model)
        }
        if (rule != null) {
            validate(rule)
            return rule.targets.map { it.copy(model = it.model ?: model) }.distinct()
        }
        return listOf(RouteTarget(if (protocol == WireProtocol.MESSAGES) "anthropic" else "codex", model))
    }
}

/** A semantic conversation for cross-provider routes. JSON schemas/arguments stay exact JSON text. */
data class Conversation(
    val instructions: List<String>, val turns: List<Turn>, val tools: List<ToolDefinition>,
    val toolChoice: ToolChoice, val parallelTools: Boolean?, val effort: String?,
    val maxOutputTokens: Long?, val temperature: Double?, val topP: Double?, val stops: List<String>,
    val sidecars: Map<WireProtocol, String> = emptyMap(),
    val verbosity: String? = null,
)
data class Turn(val role: String, val content: List<Part>)
sealed interface Part {
    data class Text(val text: String) : Part
    data class Image(val url: String, val detail: String? = null) : Part
    data class ToolCall(val id: String, val name: String, val arguments: String, val namespace: String? = null, val inputFormat: ToolInputFormat = ToolInputFormat.JSON) : Part
    data class ToolResult(val id: String, val content: List<Part>, val isError: Boolean = false, val inputFormat: ToolInputFormat = ToolInputFormat.JSON) : Part
}
enum class ToolInputFormat { JSON, TEXT }
data class ToolGrammar(val syntax: String, val definition: String)
data class ToolDefinition(val name: String, val description: String?, val schema: String, val strict: Boolean?, val namespace: String? = null, val inputFormat: ToolInputFormat = ToolInputFormat.JSON, val grammar: ToolGrammar? = null)
sealed interface ToolChoice {
    data object Auto : ToolChoice
    data object None : ToolChoice
    data object Required : ToolChoice
    data class Named(val name: String, val namespace: String? = null, val inputFormat: ToolInputFormat = ToolInputFormat.JSON) : ToolChoice
}
