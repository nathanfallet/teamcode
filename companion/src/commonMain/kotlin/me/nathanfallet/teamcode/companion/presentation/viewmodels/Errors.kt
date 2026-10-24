package me.nathanfallet.teamcode.companion.presentation.viewmodels

import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.InvalidInput
import me.nathanfallet.teamcode.companion.domain.models.visibleCode
import me.nathanfallet.teamcode.companion.domain.services.LocalHostException
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.getString

/**
 * The sentence shown for a failure, in the member's language: the domain's own failures, never a server-provided text.
 * The CLI's explanations ([LocalHostException]) and the sign-in provider's messages keep their own wording.
 */
internal suspend fun Throwable.userMessage(): String = when (this) {
    is CompanionException.RateLimited -> retryAfterSeconds?.let { getString(Res.string.error_rate_limited_in, it) } ?: getString(Res.string.error_rate_limited)
    is CompanionException -> userSentence()
    // The CLI's own explanation (a folder refused, a server refusal): TeamCode's text, never a response body.
    is LocalHostException -> message
    is InvalidInput -> inputSentence()
    is IllegalArgumentException -> message ?: getString(Res.string.error_check_input)
    else -> getString(Res.string.error_generic)
}

private suspend fun CompanionException.userSentence(): String = when (this) {
    is CompanionException.InvalidOrigin -> getString(Res.string.error_invalid_origin)
    is CompanionException.Unreachable -> getString(Res.string.error_unreachable)
    is CompanionException.NotTeamCode -> getString(Res.string.error_not_teamcode)
    is CompanionException.UnsupportedApi -> getString(Res.string.error_unsupported_api, apiVersion)
    is CompanionException.InvalidCredentials -> getString(Res.string.error_invalid_credentials)
    is CompanionException.Unauthorized -> getString(Res.string.error_unauthorized)
    is CompanionException.RateLimited -> getString(Res.string.error_rate_limited)
    is CompanionException.PasswordRejected -> getString(Res.string.error_password_rejected)
    is CompanionException.PasswordChanged -> getString(Res.string.error_password_changed)
    is CompanionException.NotFound -> getString(Res.string.error_not_found)
    is CompanionException.AccessRevoked -> getString(Res.string.error_access_revoked)
    is CompanionException.UnreadableEvent -> getString(Res.string.error_unreadable_event)
    is CompanionException.Unsupported -> getString(Res.string.error_unsupported)
    is CompanionException.ForeignContent -> getString(Res.string.error_foreign_content)
    is CompanionException.CredentialStorage -> getString(Res.string.error_credential_storage)
    is CompanionException.TransferRefused -> visibleCode(code)?.let { getString(Res.string.transfer_refused_other, it) } ?: getString(Res.string.error_transfer_refused)
    // The sign-in provider's explanation (from the client library): TeamCode's text, never the provider's.
    is CompanionException.ProviderSignIn -> message ?: getString(Res.string.error_generic)
    is CompanionException.UnavailableAccountType -> getString(Res.string.error_unavailable_account_type)
    is CompanionException.EnrollmentRefused -> getString(
        when (code) {
            CompanionException.EnrollmentRefused.ENROLLMENT_FORBIDDEN -> Res.string.error_enrollment_forbidden
            "account_in_use" -> Res.string.error_enrollment_in_use
            else -> Res.string.error_enrollment_session
        },
    )
    is CompanionException.GitHubRefused -> when (code) {
        CompanionException.GitHubRefused.APP_NOT_CONFIGURED -> getString(Res.string.error_github_app_not_configured)
        CompanionException.GitHubRefused.DEVICE_FLOW_DISABLED -> getString(Res.string.error_github_device_flow_disabled)
        else -> visibleCode(code)?.let { getString(Res.string.error_github_refused_code, it) } ?: getString(Res.string.error_github_refused)
    }
    is CompanionException.FileRefused -> visibleCode(code)?.let { getString(Res.string.error_file_refused_code, it) } ?: getString(Res.string.error_file_refused)
    is CompanionException.DeviceRefused -> when (code) {
        CompanionException.DeviceRefused.DEVICE_OFFLINE -> getString(Res.string.error_device_offline)
        "folder_not_published" -> getString(Res.string.error_device_folder_not_published)
        "device_not_found" -> getString(Res.string.error_device_not_found)
        "remote_requires_login" -> getString(Res.string.error_device_requires_login)
        "device_target_unsupported" -> getString(Res.string.error_device_target_unsupported)
        "invalid_folder" -> getString(Res.string.error_device_invalid_folder)
        else -> visibleCode(code)?.let { getString(Res.string.error_device_refused_code, it) } ?: getString(Res.string.error_device_refused)
    }
    is CompanionException.Server -> visibleCode(code)?.let { getString(Res.string.error_server_http_code, statusCode, it) } ?: getString(Res.string.error_server_http, statusCode)
}

private suspend fun InvalidInput.inputSentence(): String = when (problem) {
    InvalidInput.Problem.PASSWORDS_MISMATCH -> getString(Res.string.input_passwords_mismatch)
    InvalidInput.Problem.PASSWORD_SHORT -> getString(Res.string.input_password_short, limit)
    InvalidInput.Problem.PASSWORD_SAME -> getString(Res.string.input_password_same)
    InvalidInput.Problem.DEVICE_NAME -> getString(Res.string.input_device_name)
    InvalidInput.Problem.MEMBER_ID -> getString(Res.string.input_member_id)
    InvalidInput.Problem.MEMBER_OWNS -> getString(Res.string.input_member_owns)
    InvalidInput.Problem.FILES_MAX -> getString(Res.string.input_files_max, limit)
    InvalidInput.Problem.MESSAGE_LENGTH -> getString(Res.string.input_message_length, limit)
    InvalidInput.Problem.FIRST_MESSAGE_LENGTH -> getString(Res.string.input_first_message_length, limit)
    InvalidInput.Problem.SESSION_NAME -> getString(Res.string.input_session_name, limit)
    InvalidInput.Problem.CHOOSE_PROFILE -> getString(Res.string.input_choose_profile)
    InvalidInput.Problem.CHOOSE_DEVICE -> getString(Res.string.input_choose_device)
    InvalidInput.Problem.CHOOSE_FOLDER -> getString(Res.string.input_choose_folder)
    InvalidInput.Problem.CHOOSE_AGENT -> getString(Res.string.input_choose_agent)
    InvalidInput.Problem.CHOOSE_LOCAL_FOLDER -> getString(Res.string.input_choose_local_folder)
}
