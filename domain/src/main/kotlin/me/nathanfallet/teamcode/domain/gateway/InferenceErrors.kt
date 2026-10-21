package me.nathanfallet.teamcode.domain.gateway

/**
 * `error.type` of an inference refusal in each wire protocol's envelope. Agents key their behavior on it, so only
 * documented values are used: Anthropic Messages `{"type":"error","error":{"type","message"}}` and OpenAI Responses
 * `{"error":{"type","code","message","param"}}`. The TeamCode code travels separately (`x-teamcode-error`, and `code`
 * in the Responses envelope).
 */
object InferenceErrors {
    /** Codes for a budget the caller exhausted, not a transient rate limit. */
    val BUDGET_CODES = setOf("member_allowance", "pool_allowance", "group_allowance", "run_allowance")

    fun type(protocol: WireProtocol, status: Int, code: String): String = when (protocol) {
        WireProtocol.MESSAGES -> when (status) {
            400, 409, 422 -> "invalid_request_error"
            401 -> "authentication_error"
            403 -> "permission_error"
            404 -> "not_found_error"
            413 -> "request_too_large"
            // Claude Code does not retry a billing_error and prints its message.
            429 -> if (code in BUDGET_CODES) "billing_error" else "rate_limit_error"
            // Never overloaded_error (529): Claude Code retries it whatever the headers say.
            else -> "api_error"
        }
        // Not insufficient_quota: Codex replaces that error's message with its own.
        WireProtocol.RESPONSES -> when (status) {
            400, 409, 413, 422 -> "invalid_request_error"
            401 -> "authentication_error"
            403 -> "permission_error"
            404 -> "not_found_error"
            429 -> "rate_limit_error"
            else -> "server_error"
        }
    }
}
