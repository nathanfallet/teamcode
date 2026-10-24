package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.team.TeamFailure

/** Bounds and masking for transcripts and inputs. */
object CloudConversationRules {
    /**
     * Whether [authorId]'s instructions are kept from the agent of [session] because it holds the owner's GitHub token
     * (audit M2), before the owner's link is checked: a non-owner, in `owner` mode, on a cloud session.
     */
    fun ownerOnly(session: CloudSession, authorId: String) =
        authorId != session.ownerId && session.kind != CloudSessionKind.REMOTE && session.githubAccess == CloudGitHubAccess.OWNER

    /**
     * An input that only stops something (an interrupt, a bare permission denial as the transport encodes it) and so may
     * reach an owner-only agent from anyone with `message`.
     */
    fun onlyStops(input: CloudInput) = input.kind == CloudInputKind.INTERRUPT ||
        (input.kind == CloudInputKind.PERMISSION && input.text != null && BARE_DENIAL.matches(input.text))
    private val BARE_DENIAL = Regex("""\{"requestId":"(?:[^"\\]|\\.)*","behavior":"deny"\}""")

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
    fun detect(text: CharSequence): String? {
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

    fun mask(text: CharSequence): String = mask(text) { it }

    /** [mask] with every pass reading the text through [view], which lets a test count the characters each pass reads. */
    internal fun mask(text: CharSequence, view: (CharSequence) -> CharSequence): String {
        var masked = text.toString()
        for (pattern in secrets) masked = pattern.replace(view(masked), MASK)
        masked = authorization.replace(view(masked)) { if (looksLikeCredential(it.groupValues[2])) "${it.groupValues[1]} $MASK" else it.value }
        masked = assignment.replace(view(masked)) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        masked = urlCredentials.replace(view(masked)) { "${it.groupValues[1]}$MASK@" }
        return masked
    }

    /** Masks, then cuts a string to [limit] characters with a visible mark. */
    fun bound(text: String, limit: Int = MAX_EVENT_STRING_CHARS): String {
        val masked = mask(text)
        return if (masked.length <= limit) masked else masked.take(limit - TRUNCATION_MARK.length) + TRUNCATION_MARK
    }

    /** A message that carries [files] may have an empty text: the host adds the files' paths. */
    fun validateMessage(text: String, files: Boolean = false) {
        if ((text.isBlank() && !(files && text.isEmpty())) || text.length > MAX_MESSAGE_CHARS || text.any { it.isISOControl() && it != '\n' && it != '\t' && it != '\r' })
            throw TeamFailure("invalid_message", 400, "Send a message of 1–$MAX_MESSAGE_CHARS characters without control characters (it may be empty when it carries files).")
    }

    /** Request ids are the agent's own (Claude Code uses UUIDs); answers are short: a label index or a sentence each. */
    fun validatePermissionAnswer(answer: PermissionAnswer) {
        if (!isPermissionRequestId(answer.requestId))
            throw TeamFailure("invalid_permission_answer", 400, "Send the requestId of a permission.request event.")
        val texts = listOfNotNull(answer.message) + answer.answers.mapNotNull { it.text }
        if (answer.answers.size > MAX_PERMISSION_ANSWERS || texts.any { it.isBlank() || it.length > MAX_PERMISSION_TEXT_CHARS || it.any { c -> c.isISOControl() && c != '\n' && c != '\t' && c != '\r' } })
            throw TeamFailure("invalid_permission_answer", 400, "Send at most $MAX_PERMISSION_ANSWERS answers and texts of 1–$MAX_PERMISSION_TEXT_CHARS characters.")
        if (answer.answers.isNotEmpty() && !answer.allow) throw TeamFailure("invalid_permission_answer", 400, "Answers go with behavior allow.")
        if (answer.answers.map { it.question }.toSet().size != answer.answers.size || answer.answers.any { it.question !in 0 until MAX_PERMISSION_INDEX })
            throw TeamFailure("invalid_permission_answer", 400, "Answer each question once, by its index (0 to ${MAX_PERMISSION_INDEX - 1}).")
        if (answer.answers.any { (it.options.isEmpty()) == (it.text == null) || it.options.toSet().size != it.options.size || it.options.any { option -> option !in 0 until MAX_PERMISSION_INDEX } })
            throw TeamFailure("invalid_permission_answer", 400, "Answer a question with distinct option indexes (0 to ${MAX_PERMISSION_INDEX - 1}) or with a text, not both.")
    }

    /**
     * Who may answer [request], and whether [answer] fits it. Approving or denying a tool use gated by an `ask` rule
     * is the session owner's or an administrator's decision ([approver]); a question is answered by anyone who may
     * send messages to the session, which the caller already passed. Answers need a question request, and their
     * indexes must exist in it when its input is known.
     */
    fun checkAnswer(request: PermissionRequestRecord, answer: PermissionAnswer, approver: Boolean, currentEpoch: Int = request.epoch) {
        // Who may decide comes first, so someone who may not learns nothing about the request's state.
        if (!request.question && !approver) throw TeamFailure("permission_answer_denied", 403, "Only the session owner or an administrator may approve or deny this tool use.")
        // An execution that ended (a pause, a new pod) took its open requests with it.
        when (if (request.epoch != currentEpoch && request.state == PermissionRequestState.OPEN) PermissionRequestState.ENDED else request.state) {
            PermissionRequestState.OPEN -> Unit
            PermissionRequestState.EXPIRED -> throw TeamFailure("permission_request_expired", 409, "This permission request expired without an answer and was denied.")
            PermissionRequestState.ANSWERED -> throw TeamFailure("permission_request_answered", 409, "This permission request was already answered.")
            PermissionRequestState.ENDED -> throw TeamFailure("permission_request_ended", 409, "The turn of this permission request has ended.")
        }
        // Nobody may approve what nobody could read: an input too large for the transcript can only be denied.
        if (answer.allow && request.inputLeftOut)
            throw TeamFailure("permission_input_unseen", 400, "This request's input was too large to show in the transcript, so it can only be denied.")
        if (!request.question) {
            if (answer.answers.isNotEmpty()) throw TeamFailure("invalid_permission_answer", 400, "This request is a tool use, not a question: send no answers.")
            return
        }
        if (answer.allow && answer.answers.isEmpty()) throw TeamFailure("invalid_permission_answer", 400, "Answer at least one question, or deny the request.")
        val questions = request.questions ?: return
        val texts = answer.answers.mapNotNull { questions.getOrNull(it.question)?.text }
        if (texts.toSet().size != texts.size) throw TeamFailure("invalid_permission_answer", 400, "Two of these questions have the same text; answer only one of them.")
        for (item in answer.answers) {
            val shape = questions.getOrNull(item.question) ?: throw TeamFailure("invalid_permission_answer", 400, "The request has ${questions.size} questions, numbered from 0.")
            if (item.options.any { it >= shape.options } || (item.options.size > 1 && !shape.multiSelect))
                throw TeamFailure("invalid_permission_answer", 400, "Question ${item.question} has ${shape.options} options, numbered from 0" + if (shape.multiSelect) "." else ", and takes one.")
        }
        // Claude Code reads one answer per question text: every one of them is answered (a repeated text once).
        val asked = questions.mapIndexed { index, shape -> shape.text ?: "#$index" }.toSet()
        if (answer.allow && answer.answers.map { questions[it.question].text ?: "#${it.question}" }.toSet() != asked)
            throw TeamFailure("invalid_permission_answer", 400, "Answer each of the request's questions, or deny it.")
    }
    const val MAX_PERMISSION_ANSWERS = 16
    /**
     * Pending permission answers a session holds at most. They have their own bound, outside [MAX_PENDING_INPUTS]: a
     * queue full of messages waiting for the turn to end must never block the answer that lets it end.
     */
    const val MAX_PENDING_PERMISSION_ANSWERS = 16
    const val MAX_PERMISSION_TEXT_CHARS = 4_000
    /** Question and option indexes stay below this bound (Claude Code asks at most 4 questions of 4 options). */
    const val MAX_PERMISSION_INDEX = 64
    /** The longest deadline a team may set for unanswered permission requests: a day. */
    const val MAX_PERMISSION_DEADLINE_MINUTES = 1_440
    /** The `turn` phases that end a turn, and with it every permission request still open in it. */
    val TURN_END_PHASES = setOf("completed", "interrupted", "failed")
    /** Printable ASCII without spaces: the agent chooses the shape (Claude Code: a UUID). */
    private val permissionRequestId = Regex("[\\x21-\\x7E]{1,128}")
    fun isPermissionRequestId(id: String) = permissionRequestId.matches(id)

    fun validatePage(limit: Int, after: Long?, before: Long?) {
        if (limit !in 1..MAX_PAGE) throw TeamFailure("invalid_limit", 400, "Use a page size between 1 and $MAX_PAGE.")
        if (after != null && before != null) throw TeamFailure("invalid_cursor", 400, "Send after or before, not both.")
        if ((after != null && after < 0) || (before != null && before < 1)) throw TeamFailure("invalid_cursor", 400, "Use a non-negative event sequence.")
    }
}
