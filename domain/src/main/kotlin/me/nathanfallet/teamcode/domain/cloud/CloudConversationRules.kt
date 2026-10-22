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
        // Lookaheads are bounded, so a crafted string (`sk-sk-sk-…`) never makes a scan backtrack quadratically.
        Regex("(?<![A-Za-z0-9_-])sk-(?:ant-)?(?=[A-Za-z0-9_-]{0,256}[0-9])[A-Za-z0-9_-]{16,}"),
        Regex("(?<![A-Za-z0-9_])(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}"),
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
        Regex("(?<![A-Za-z0-9_-])tc(?:ex|sk|dv|ss)?_(?=[A-Za-z0-9_-]{0,256}[0-9])[A-Za-z0-9_-]{16,}"),
        Regex("(?:AKIA|ASIA)[A-Z0-9]{16}"),
        Regex("xox[abprs]-[A-Za-z0-9-]{10,}"),
        // Anchored at a word start and possessive: `eyJeyJeyJ…` without a dot is scanned once, not once per `eyJ`.
        Regex("(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}+\\.[A-Za-z0-9_-]{8,}+\\.[A-Za-z0-9_-]{8,}"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"),
    )
    private val authorization = Regex("(?i)\\b(bearer|basic|token)\\s+([A-Za-z0-9._~+/=-]{12,})")
    /** A name ending in a secret word (`GITHUB_TOKEN`, `"api_key"`, not `max_tokens`) assigned a value of 8 characters or more. */
    private val assignment = Regex("(?i)\\b([A-Z0-9_]*(?:SECRET|TOKEN|PASSWORD|PASSWD|API_?KEY|PRIVATE_KEY|CREDENTIALS?))(\"?\\s*[=:]\\s*)(\"[^\"]{8,}\"|'[^']{8,}'|[^\\s\"',;]{8,})")
    /** The scheme is bounded, so a long run of scheme characters without `://` is scanned in linear time. */
    private val urlCredentials = Regex("(?i)([a-z][a-z0-9+.-]{0,31}://)[^\\s/@:]+:[^\\s/@]+@")

    /**
     * A plain word (`configuration`, `Authentication.`) after `token`, `basic` or `bearer` is prose. Anything else is
     * treated as a credential: a digit, a symbol, inner capitals, or letters too long for a word (a passphrase).
     */
    private fun looksLikeCredential(value: String): Boolean {
        val word = value.trimEnd('.')
        return word.length > MAX_WORD || word.any { !it.isLetter() } || word.drop(1).any { it.isUpperCase() }
    }
    private const val MAX_WORD = 16

    /** The kind of each pattern of [secrets], in order, as [detect] names it. */
    private val secretKinds = listOf("provider_api_key", "github_token", "github_token", "teamcode_credential", "aws_access_key", "slack_token", "jwt", "private_key")

    /**
     * The kind of the first credential found in [text], or null: the shapes [mask] hides, plus authorization values
     * and URLs with credentials. Name-based assignments (`API_KEY = "…"`) are not reported: they are ordinary code in a
     * page and say nothing about the value. Used to refuse publishing a file rather than to rewrite it.
     */
    fun detect(text: String): String? {
        secrets.forEachIndexed { index, pattern -> if (pattern.containsMatchIn(text)) return secretKinds[index] }
        if (authorizationValue.findAll(text).any { looksLikeSecretValue(it.groupValues[1], it.groupValues[2]) }) return "authorization_header"
        if (urlCredentials.containsMatchIn(text)) return "url_credentials"
        return null
    }

    /** On one line only: a whole page holds prose (`Basic cross-browser support`) and Markdown headings (`Token` over `-----`). */
    private val authorizationValue = Regex("(?i)\\b(bearer|basic|token)[ \\t]+([A-Za-z0-9._~+/=-]{12,})")

    /**
     * Stricter than [looksLikeCredential], since a hit refuses a whole file. A `Basic` value counts only when it is
     * base64 (padded or not) of a `user:password` pair. A `Bearer` or `Token` value counts when it is at least 12
     * characters with letters and digits, at least 20 with both cases, or at least 16 digits, and is neither a path (`/api/v1/items`,
     * `https://…`), nor an identifier made of lowercase words and numbers (`refresh_interval_v2`,
     * `2024-10-22-rotation-notes`), nor a camel- or Pascal-case name (`getAccessToken2`), which pages
     * legitimately contain.
     */
    private fun looksLikeSecretValue(scheme: String, value: String): Boolean {
        val word = value.trimEnd('.')
        if (scheme.equals("basic", ignoreCase = true)) return basicPair(word)
        if ("://" in word || PATH.matches(word)) return false
        if (word.length < 12 || identifier(word)) return false
        // A long run of digits alone is a numeric token; a shorter one is a number.
        if (word.none { it.isLetter() }) return word.length >= 16 && word.all { it.isDigit() }
        if (word.none { it.isDigit() } && (word.length < 20 || word.none { it.isUpperCase() } || word.none { it.isLowerCase() })) return false
        val segments = word.split('.', '_', '-', '/', '~', '+', '=').filter { it.isNotEmpty() }
        val wordy = segments.size > 1 && segments.all { segment ->
            segment.all { it.isDigit() } || (segment.all { it.isLowerCase() || it.isDigit() } && segment.count { it.isDigit() } <= 2)
        }
        return !wordy
    }

    /** A path: `/api/v1/items`, `./dist`, or one lowercase segment (`/artifacts`), not `/9j4AAQSkZJRg…` (base64). */
    private val PATH = Regex("\\.{0,2}/(?:[A-Za-z0-9._~-]+/)+[A-Za-z0-9._~-]*|\\.{0,2}/[a-z0-9._~-]*")
    private val NAME_PART = Regex("[A-Z]+(?![a-z])|[A-Z]?[a-z]+|[0-9]+")

    /**
     * A camel- or Pascal-case name (`getAccessToken2`, `handleOAuth2Callback`, `RefreshTokenSchedulerService`): words of
     * three letters or more, acronyms and short numbers. A random token splits into short, mixed pieces instead.
     */
    private fun identifier(word: String): Boolean {
        val parts = NAME_PART.findAll(word).map { it.value }.toList()
        if (parts.size < 2 || parts.joinToString("") != word) return false
        return parts.all { part -> when {
            part.all { it.isDigit() } -> part.length <= 2
            part.all { it.isUpperCase() } -> part.length <= 5
            else -> part.length >= 3
        } }
    }

    /** Base64, padded or not, of printable text holding a colon: what `Authorization: Basic` carries. */
    private fun basicPair(word: String): Boolean {
        val bare = word.trimEnd('=')
        if (bare.length % 4 == 1 || word.length - bare.length > 2 || bare.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "+/") }) return false
        val decoded = try { java.util.Base64.getDecoder().decode(bare + "=".repeat((4 - bare.length % 4) % 4)) } catch (_: IllegalArgumentException) { return false }
        val text = try { java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(decoded)).toString() }
            catch (_: java.nio.charset.CharacterCodingException) { return false }
        return ':' in text && text.none { it.isISOControl() }
    }

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
