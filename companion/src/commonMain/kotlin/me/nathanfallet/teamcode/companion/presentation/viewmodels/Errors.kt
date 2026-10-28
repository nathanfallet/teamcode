package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.compose.runtime.Composable
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.InvalidInput
import me.nathanfallet.teamcode.companion.domain.models.visibleCode
import me.nathanfallet.teamcode.companion.domain.services.LocalHostException
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A failure shown to the member. [text] is the English sentence (logs, tests); [resource] and [args] word the same
 * sentence in the member's language, read where it is shown ([words]). Without a [resource] the text is shown as it is:
 * the CLI's own explanation, or a sentence the sign-in provider gave.
 */
data class Failure(val text: String, val resource: StringResource? = null, val args: List<Any> = emptyList())

/** The failure's sentence in the language of the system. */
@Composable
fun Failure.words(): String = resource?.let { stringResource(it, *args.toTypedArray()) } ?: text

/** The sentence shown for a failure: the domain's own messages, never a server-provided text. */
internal fun Throwable.userMessage(): Failure = when (this) {
    is CompanionException.RateLimited -> retryAfterSeconds?.let { Failure("Too many attempts. Try again in $it s.", Res.string.error_rate_limited_in, listOf(it)) }
        ?: Failure(message ?: "", Res.string.error_rate_limited)
    is CompanionException -> sentence().let { (resource, args) -> Failure(message ?: GENERIC, resource, args) }
    // The CLI's own explanation (a folder refused, a server refusal): TeamCode's text, never a response body.
    is LocalHostException -> Failure(message)
    is InvalidInput -> inputSentence().let { (resource, args) -> Failure(message ?: "", resource, args) }
    is IllegalArgumentException -> message?.let { Failure(it) } ?: Failure("Check what you entered.", Res.string.error_check_input)
    else -> Failure(GENERIC, Res.string.error_generic)
}

private const val GENERIC = "Something went wrong."

private fun CompanionException.sentence(): Pair<StringResource?, List<Any>> = when (this) {
    is CompanionException.InvalidOrigin -> Res.string.error_invalid_origin to emptyList()
    is CompanionException.Unreachable -> Res.string.error_unreachable to emptyList()
    is CompanionException.NotTeamCode -> Res.string.error_not_teamcode to emptyList()
    is CompanionException.UnsupportedApi -> Res.string.error_unsupported_api to listOf(apiVersion)
    is CompanionException.InvalidCredentials -> Res.string.error_invalid_credentials to emptyList()
    is CompanionException.Unauthorized -> Res.string.error_unauthorized to emptyList()
    is CompanionException.RateLimited -> Res.string.error_rate_limited to emptyList()
    is CompanionException.PasswordRejected -> Res.string.error_password_rejected to emptyList()
    is CompanionException.PasswordChanged -> Res.string.error_password_changed to emptyList()
    is CompanionException.NotFound -> Res.string.error_not_found to emptyList()
    is CompanionException.AccessRevoked -> Res.string.error_access_revoked to emptyList()
    is CompanionException.UnreadableEvent -> Res.string.error_unreadable_event to emptyList()
    is CompanionException.Unsupported -> Res.string.error_unsupported to emptyList()
    is CompanionException.ForeignContent -> Res.string.error_foreign_content to emptyList()
    is CompanionException.CredentialStorage -> Res.string.error_credential_storage to emptyList()
    is CompanionException.TransferRefused -> visibleCode(code)?.let { Res.string.transfer_refused_other to listOf<Any>(it) } ?: (Res.string.error_transfer_refused to emptyList())
    // The sign-in provider's explanation (from the client library): TeamCode's text, never the provider's.
    is CompanionException.ProviderSignIn -> null to emptyList()
    is CompanionException.UnavailableAccountType -> Res.string.error_unavailable_account_type to emptyList()
    is CompanionException.EnrollmentRefused -> when (code) {
        CompanionException.EnrollmentRefused.ENROLLMENT_FORBIDDEN -> Res.string.error_enrollment_forbidden
        "account_in_use" -> Res.string.error_enrollment_in_use
        else -> Res.string.error_enrollment_session
    } to emptyList()
    is CompanionException.GitHubRefused -> when (code) {
        CompanionException.GitHubRefused.APP_NOT_CONFIGURED -> Res.string.error_github_app_not_configured to emptyList()
        CompanionException.GitHubRefused.DEVICE_FLOW_DISABLED -> Res.string.error_github_device_flow_disabled to emptyList()
        else -> visibleCode(code)?.let { Res.string.error_github_refused_code to listOf<Any>(it) } ?: (Res.string.error_github_refused to emptyList())
    }
    is CompanionException.FileRefused -> visibleCode(code)?.let { Res.string.error_file_refused_code to listOf<Any>(it) } ?: (Res.string.error_file_refused to emptyList())
    is CompanionException.DeviceRefused -> when (code) {
        CompanionException.DeviceRefused.DEVICE_OFFLINE -> Res.string.error_device_offline to emptyList()
        "folder_not_published" -> Res.string.error_device_folder_not_published to emptyList()
        "device_not_found" -> Res.string.error_device_not_found to emptyList()
        "remote_requires_login" -> Res.string.error_device_requires_login to emptyList()
        "device_target_unsupported" -> Res.string.error_device_target_unsupported to emptyList()
        "invalid_folder" -> Res.string.error_device_invalid_folder to emptyList()
        CompanionException.DeviceRefused.DAEMON_RESUME_UNSUPPORTED -> Res.string.error_device_resume_unsupported to emptyList()
        "device_no_answer" -> Res.string.error_device_no_answer to emptyList()
        "folder_not_configured", "folder_missing", "folder_moved", "folder_refused" -> Res.string.error_device_resume_folder to listOf<Any>(code)
        else -> visibleCode(code)?.let { Res.string.error_device_refused_code to listOf<Any>(it) } ?: (Res.string.error_device_refused to emptyList())
    }
    is CompanionException.Server -> automationCodeSentence(code)?.let { it to emptyList<Any>() } ?: visibleCode(code)?.let { Res.string.error_server_http_code to listOf<Any>(statusCode, it) } ?: (Res.string.error_server_http to listOf<Any>(statusCode))
}

private fun InvalidInput.inputSentence(): Pair<StringResource, List<Any>> = when (problem) {
    InvalidInput.Problem.PASSWORDS_MISMATCH -> Res.string.input_passwords_mismatch to emptyList()
    InvalidInput.Problem.PASSWORD_SHORT -> Res.string.input_password_short to listOf(limit)
    InvalidInput.Problem.PASSWORD_SAME -> Res.string.input_password_same to emptyList()
    InvalidInput.Problem.DEVICE_NAME -> Res.string.input_device_name to emptyList()
    InvalidInput.Problem.MEMBER_ID -> Res.string.input_member_id to emptyList()
    InvalidInput.Problem.MEMBER_OWNS -> Res.string.input_member_owns to emptyList()
    InvalidInput.Problem.FILES_MAX -> Res.string.input_files_max to listOf(limit)
    InvalidInput.Problem.MESSAGE_LENGTH -> Res.string.input_message_length to listOf(limit)
    InvalidInput.Problem.FIRST_MESSAGE_LENGTH -> Res.string.input_first_message_length to listOf(limit)
    InvalidInput.Problem.SESSION_NAME -> Res.string.input_session_name to listOf(limit)
    InvalidInput.Problem.CHOOSE_PROFILE -> Res.string.input_choose_profile to emptyList()
    InvalidInput.Problem.CHOOSE_DEVICE -> Res.string.input_choose_device to emptyList()
    InvalidInput.Problem.CHOOSE_FOLDER -> Res.string.input_choose_folder to emptyList()
    InvalidInput.Problem.CHOOSE_AGENT -> Res.string.input_choose_agent to emptyList()
    InvalidInput.Problem.CHOOSE_LOCAL_FOLDER -> Res.string.input_choose_local_folder to emptyList()
    InvalidInput.Problem.AUTOMATION_NAME -> Res.string.input_automation_name to listOf(limit)
    InvalidInput.Problem.AUTOMATION_PROMPT -> Res.string.input_automation_prompt to listOf(limit)
    InvalidInput.Problem.AUTOMATION_CRON -> Res.string.input_automation_cron to emptyList()
    InvalidInput.Problem.AUTOMATION_TIMEZONE -> Res.string.input_automation_timezone to emptyList()
    InvalidInput.Problem.AUTOMATION_VARIABLE -> Res.string.input_automation_variable to emptyList()
    InvalidInput.Problem.AUTOMATION_PAYLOAD -> Res.string.input_automation_payload to emptyList()
    InvalidInput.Problem.AUTOMATION_LIMITS -> Res.string.input_automation_limits to emptyList()
    InvalidInput.Problem.AUTOMATION_NOT_OWNER -> Res.string.input_automation_not_owner to emptyList()
}

/** The sentence of a code an automation call answers with (`409 stale_version`, `422 not_available_yet`...), when the app words it. */
private fun automationCodeSentence(code: String?): StringResource? = when (code) {
    "stale_version" -> Res.string.error_automation_stale_version
    "automation_limit" -> Res.string.error_automation_limit
    "not_available_yet" -> Res.string.error_automation_not_available_yet
    "profile_unassigned" -> Res.string.error_automation_profile_unassigned
    "invalid_automation" -> Res.string.error_automation_invalid
    "payload_required" -> Res.string.error_automation_payload_required
    "payload_not_accepted" -> Res.string.error_automation_payload_not_accepted
    "automation_disabled" -> Res.string.error_automation_disabled
    "automation_owner_only" -> Res.string.input_automation_not_owner
    "invalid_payload" -> Res.string.error_automation_invalid_payload
    else -> null
}
