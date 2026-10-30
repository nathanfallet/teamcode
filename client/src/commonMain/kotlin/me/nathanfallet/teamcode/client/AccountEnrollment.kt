package me.nathanfallet.teamcode.client

import me.nathanfallet.teamcode.api.models.AccountEnrollmentPolicyView

/**
 * The kinds of subscription account a TeamCode client offers to add, in the order every selector shows them (the same
 * menu as TeamClaude's login). [supported] is false for a kind the server cannot hold yet: it is listed so the menu is
 * final, and choosing it explains why ([API_KEY_UNSUPPORTED]) instead of silently doing something else. [flag] is the
 * CLI's `--type` value; [provider] the server's provider name.
 */
enum class AccountType(val flag: String, val label: String, val provider: String?, val supported: Boolean = true) {
    ANTHROPIC_SUBSCRIPTION("anthropic-subscription", "Anthropic subscription (Claude Pro, Max, Team, Enterprise)", "anthropic"),
    CODEX_SUBSCRIPTION("codex-subscription", "Codex subscription (ChatGPT Plus, Pro, Team)", "codex"),
    ANTHROPIC_API("anthropic-api", "Anthropic API key (Claude Console, console.anthropic.com)", null, supported = false),
    /** A pay-as-you-go OpenRouter API key, pasted rather than signed in: the server verifies it and nothing refreshes it. */
    OPENROUTER_API("openrouter-api", "OpenRouter API key (openrouter.ai/keys)", "openrouter");

    /** The provider sign-in of a supported kind; null for a kind the server cannot hold. */
    val enrollment: OAuthEnrollmentProvider? get() = when (this) {
        ANTHROPIC_SUBSCRIPTION -> ClaudeOAuthEnrollment
        CODEX_SUBSCRIPTION -> CodexOAuthEnrollment
        ANTHROPIC_API, OPENROUTER_API -> null
    }

    companion object {
        val flags = entries.map { it.flag }
        /** `--provider` predates `--type`: claude and claude-max meant the Anthropic subscription. */
        fun fromProvider(name: String) = if (name == "codex") CODEX_SUBSCRIPTION else ANTHROPIC_SUBSCRIPTION
    }
}

const val API_KEY_UNSUPPORTED = "Anthropic API key accounts are not supported by TeamCode servers yet: the pool holds subscription sign-ins (refreshable OAuth credentials) only. Add an Anthropic or Codex subscription instead."

/** The capability of a server that answers `GET /api/v1/policy/account-enrollment`. */
const val ACCOUNT_ENROLLMENT_POLICY_FEATURE = "accounts.enrollment-policy.v1"

/** Whether the caller may add subscription accounts, decided before any provider sign-in. */
enum class AccountEnrollmentAccess {
    ALLOWED,
    /** Only administrators may add accounts on this server. */
    ADMINISTRATORS_ONLY,
    /** Every member may, so the refusal is about this session (a service key, a pending password change). */
    SESSION_CANNOT_ENROLL,
}

/**
 * Asks the server before any provider sign-in, as it would decide. A server that announces
 * [ACCOUNT_ENROLLMENT_POLICY_FEATURE] says whether the caller may ([policy], a call every member may make); an older one
 * only accepts administrators, which the caller's own [role] tells.
 */
suspend fun accountEnrollmentAccess(features: Collection<String>, policy: suspend () -> AccountEnrollmentPolicyView, role: suspend () -> String): AccountEnrollmentAccess {
    if (ACCOUNT_ENROLLMENT_POLICY_FEATURE !in features)
        return if (role().uppercase() in setOf("OWNER", "ADMIN")) AccountEnrollmentAccess.ALLOWED else AccountEnrollmentAccess.ADMINISTRATORS_ONLY
    val answer = policy()
    return when {
        answer.allowed == true -> AccountEnrollmentAccess.ALLOWED
        answer.policy == "members" -> AccountEnrollmentAccess.SESSION_CANNOT_ENROLL
        else -> AccountEnrollmentAccess.ADMINISTRATORS_ONLY
    }
}
