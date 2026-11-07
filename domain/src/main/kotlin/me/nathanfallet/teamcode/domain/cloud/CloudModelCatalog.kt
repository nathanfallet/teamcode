package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.policy.ModelPolicy
import me.nathanfallet.teamcode.domain.policy.matchesModelPattern

/** A model a member may pick for a session: [id] is what the agent is started with, [label] what a person reads. */
data class CloudModelOption(val id: String, val label: String)

/**
 * The models the picker of a cloud or remote session offers (`GET /cloud/models`), per agent, best first, and the rule
 * that decides which of them a member may choose: the team's model policy, exactly as inference checks it. The list is
 * a suggestion, not a limit: a member may send any model id the policy allows, such as one released after this list.
 */
object CloudModelCatalog {
    private val CLAUDE = listOf(
        CloudModelOption("claude-opus-5-5", "Opus 5.5"), CloudModelOption("claude-sonnet-5-5", "Sonnet 5.5"), CloudModelOption("claude-haiku-5-5", "Haiku 5.5"),
        CloudModelOption("claude-opus-4-8", "Opus 4.8"), CloudModelOption("claude-sonnet-4-6", "Sonnet 4.6"), CloudModelOption("claude-haiku-4-5", "Haiku 4.5"),
    )
    /** The visible entries of Codex's own model cache (`models_cache.json`); hidden ones (`gpt-reserve`, `codex-auto-review`) are not offered. */
    private val CODEX = listOf(
        CloudModelOption("gpt-6.1-sol", "GPT-6.1-Sol"), CloudModelOption("gpt-6.1-terra", "GPT-6.1 Terra"),
        CloudModelOption("gpt-6-astra", "GPT-6-Astra"), CloudModelOption("gpt-6-sol", "GPT-6-Sol"), CloudModelOption("gpt-6-luna", "GPT-6-Luna"),
        CloudModelOption("gpt-5.6-sol", "GPT-5.6-Sol"), CloudModelOption("gpt-5.6-terra", "GPT-5.6-Terra"), CloudModelOption("gpt-5.6-luna", "GPT-5.6-Luna"),
    )

    /** Claude Code's own short names; the policy sees the model they stand for. */
    private val CLAUDE_ALIASES = setOf("opus", "sonnet", "haiku")

    /** The options of [agent] (`claude` or `codex`) that [policy] allows; empty for another agent. */
    fun options(agent: String, policy: ModelPolicy): List<CloudModelOption> =
        (when (agent) { "claude" -> CLAUDE; "codex" -> CODEX; else -> emptyList() }).filter { allowed(policy, it.id) }

    /** The model the policy is checked against: an alias of Claude Code stands for `claude-<alias>`. */
    fun resolved(model: String) = if (model.lowercase() in CLAUDE_ALIASES) "claude-${model.lowercase()}" else model

    fun allowed(policy: ModelPolicy, model: String): Boolean {
        val checked = resolved(model)
        return policy.deniedModels.none { matchesModelPattern(it, checked) } && policy.allowedModels.any { matchesModelPattern(it, checked) }
    }
}
