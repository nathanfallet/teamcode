package me.nathanfallet.teamcode.domain.gateway

import me.nathanfallet.teamcode.domain.team.TeamFailure

/** A recognized pre-generation refusal of the subscription, rather than of the caller's request. */
enum class ProviderAccountAction(val code: String, val cooldownSeconds: Long) {
    ACCEPT_CLAUDE_TERMS("provider_terms_required", 60);

    /** TeamCode's own explanation; provider text and subscription identities never reach another member. */
    fun failure() = TeamFailure(code, 503,
        "No configured account served this request. A Claude subscription requires acceptance of the updated " +
            "Consumer Terms and Privacy Policy. Ask its owner to sign in at https://claude.ai and accept them, " +
            "then retry. TeamCode temporarily skips that account and tries other eligible accounts first.",
        retryable = false)
}
