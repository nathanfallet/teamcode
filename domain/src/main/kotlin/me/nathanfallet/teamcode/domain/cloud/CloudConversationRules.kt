package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.team.TeamFailure

/** Bounds and masking for transcripts and inputs. */
object CloudConversationRules {
    /** Longest message a member may send, in characters. */
    const val MAX_MESSAGE_CHARS = 32_000
    /** Longest string kept in an event; longer strings are cut and marked. */
    const val MAX_EVENT_STRING_CHARS = 16_000
    /** Largest encoded event; a larger one is replaced by a `truncated` marker. */
    const val MAX_EVENT_BYTES = 60_000
    const val MAX_EVENTS_PER_BATCH = 200
    const val MAX_PENDING_INPUTS = 100
    const val MAX_ATTEMPTS = 3
    const val MAX_PAGE = 200
    const val MAX_RUNNER_WAIT_MILLIS = 20_000L
    const val ATTACHMENT_LEASE_MILLIS = 60_000L
    /** A delivery not acknowledged within this time is offered again (the runner deduplicates by input id). */
    const val REDELIVERY_MILLIS = 60_000L
    const val TRUNCATION_MARK = "…[truncated]"
    const val MASK = "[REDACTED]"

    /**
     * Credential shapes masked in every stored string: provider API keys, GitHub tokens, TeamCode credentials,
     * cloud keys, JWTs, bearer and basic authorization values, private keys, and `key=value` assignments of
     * names that look secret.
     */
    private val secrets = listOf(
        // Prefixed shapes start at a word boundary and contain a digit, so `task-management-…` or `etc_…` stay readable.
        Regex("(?<![A-Za-z0-9_-])sk-(?:ant-)?(?=[A-Za-z0-9_-]*[0-9])[A-Za-z0-9_-]{16,}"),
        Regex("(?<![A-Za-z0-9_])(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}"),
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
        Regex("(?<![A-Za-z0-9_-])tc(?:ex|sk|dv|ss)?_(?=[A-Za-z0-9_-]*[0-9])[A-Za-z0-9_-]{16,}"),
        Regex("(?:AKIA|ASIA)[A-Z0-9]{16}"),
        Regex("xox[abprs]-[A-Za-z0-9-]{10,}"),
        Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"),
    )
    private val authorization = Regex("(?i)\\b(bearer|basic|token)\\s+([A-Za-z0-9._~+/=-]{12,})")
    /** A name ending in a secret word (`GITHUB_TOKEN`, `"api_key"`, not `max_tokens`) assigned a value of 8 characters or more. */
    private val assignment = Regex("(?i)\\b([A-Z0-9_]*(?:SECRET|TOKEN|PASSWORD|PASSWD|API_?KEY|PRIVATE_KEY|CREDENTIALS?))(\"?\\s*[=:]\\s*)(\"[^\"]{8,}\"|'[^']{8,}'|[^\\s\"',;]{8,})")
    private val urlCredentials = Regex("(?i)(\\b[a-z][a-z0-9+.-]*://)[^\\s/@:]+:[^\\s/@]+@")

    /** A plain word (`configuration`, `Management`) after `token` or `basic` is prose; a credential has a digit, a symbol or inner capitals. */
    private fun looksLikeCredential(value: String) = value.any { !it.isLetter() } || value.drop(1).any { it.isUpperCase() }

    fun mask(text: String): String {
        var masked = text
        for (pattern in secrets) masked = pattern.replace(masked, MASK)
        masked = authorization.replace(masked) { if (looksLikeCredential(it.groupValues[2])) "${it.groupValues[1]} $MASK" else it.value }
        masked = assignment.replace(masked) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        masked = urlCredentials.replace(masked) { "${it.groupValues[1]}$MASK@" }
        return masked
    }

    /** Masks, then cuts a string to [limit] characters with a visible mark. */
    fun bound(text: String, limit: Int = MAX_EVENT_STRING_CHARS): String {
        val masked = mask(text)
        return if (masked.length <= limit) masked else masked.take(limit - TRUNCATION_MARK.length) + TRUNCATION_MARK
    }

    fun validateMessage(text: String) {
        if (text.isBlank() || text.length > MAX_MESSAGE_CHARS || text.any { it.isISOControl() && it != '\n' && it != '\t' && it != '\r' })
            throw TeamFailure("invalid_message", 400, "Send a message of 1–$MAX_MESSAGE_CHARS characters without control characters.")
    }

    fun validatePage(limit: Int, after: Long?, before: Long?) {
        if (limit !in 1..MAX_PAGE) throw TeamFailure("invalid_limit", 400, "Use a page size between 1 and $MAX_PAGE.")
        if (after != null && before != null) throw TeamFailure("invalid_cursor", 400, "Send after or before, not both.")
        if ((after != null && after < 0) || (before != null && before < 1)) throw TeamFailure("invalid_cursor", 400, "Use a non-negative event sequence.")
    }
}
