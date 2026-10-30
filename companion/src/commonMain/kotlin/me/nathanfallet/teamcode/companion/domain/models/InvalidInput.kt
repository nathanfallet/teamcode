package me.nathanfallet.teamcode.companion.domain.models

/**
 * What the member entered fails a check made here, before anything is sent. The [message] is the English sentence;
 * the screens word [problem] in the member's language ([limit] fills the number some sentences carry).
 */
class InvalidInput(val problem: Problem, val limit: Int = 0, message: String) : IllegalArgumentException(message) {
    enum class Problem {
        PASSWORDS_MISMATCH, PASSWORD_SHORT, PASSWORD_SAME, DEVICE_NAME, MEMBER_ID, MEMBER_OWNS, FILES_MAX, MESSAGE_LENGTH,
        FIRST_MESSAGE_LENGTH, SESSION_NAME, CHOOSE_PROFILE, CHOOSE_DEVICE, CHOOSE_FOLDER, CHOOSE_AGENT, CHOOSE_LOCAL_FOLDER,
        AUTOMATION_NAME, AUTOMATION_PROMPT, AUTOMATION_CRON, AUTOMATION_TIMEZONE, AUTOMATION_VARIABLE, AUTOMATION_PAYLOAD, AUTOMATION_NOT_OWNER, AUTOMATION_LIMITS,
    }
}

/** Throws an [InvalidInput] unless [valid]. */
internal fun requireInput(valid: Boolean, problem: InvalidInput.Problem, limit: Int = 0, message: String) {
    if (!valid) throw InvalidInput(problem, limit, message)
}
