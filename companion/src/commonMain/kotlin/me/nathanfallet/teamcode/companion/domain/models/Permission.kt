package me.nathanfallet.teamcode.companion.domain.models

/**
 * One question of an `AskUserQuestion` request. [index] is its place in the request's `input.questions`, the index the
 * server takes back; texts are the agent's, shown as plain text only.
 */
data class AgentQuestion(val index: Int, val text: String, val header: String?, val options: List<AgentQuestionOption>, val multiSelect: Boolean)

data class AgentQuestionOption(val label: String, val description: String?)

/** What the member picked for one question: options by index, and "Other" with its free text. */
data class QuestionChoice(val options: Set<Int> = emptySet(), val other: Boolean = false, val otherText: String = "")

/** The answer to the question at [question]: chosen [options] by index, or free [text] (one or the other). */
data class QuestionAnswer(val question: Int, val options: List<Int> = emptyList(), val text: String? = null)

/** What `POST …/permissions` receives: allow (with [answers] for a question) or deny (with an optional [message]). */
data class PermissionAnswer(val requestId: String, val allow: Boolean, val answers: List<QuestionAnswer> = emptyList(), val message: String? = null)

/** How the server took an answer. Only [Retry] is worth sending again, with the same idempotency key. */
sealed interface AnswerOutcome {
    /** Accepted (`202`): it waits for the agent; the request closes when the transcript records it consumed. */
    data class Accepted(val receipt: InputReceipt) : AnswerOutcome
    /** A network failure, a timeout, a 5xx or a 429: the answer may or may not have arrived. */
    data class Retry(val reason: String?) : AnswerOutcome
    /** `409`: answered by someone else, expired, ended, or the session takes no answer now. */
    data class Closed(val code: String?) : AnswerOutcome
    /** `400`: the server read the answer as invalid (an index, an unseen input); the draft stays to be corrected. */
    data class Invalid(val code: String?) : AnswerOutcome
    /** `403` and other refusals: this member may not answer it ([code] `permission_answer_denied`), or the server cannot. */
    data class Refused(val code: String?, val reason: String?) : AnswerOutcome
}
