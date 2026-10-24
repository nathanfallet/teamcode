package me.nathanfallet.teamcode.companion.domain.usecases.auth

import me.nathanfallet.teamcode.companion.domain.models.InvalidInput
import me.nathanfallet.teamcode.companion.domain.models.requireInput
import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.SignInResult
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository

/**
 * The password change a temporary password requires, then a new login with the new password: the server revokes
 * every session of the member on a change, the temporary one included (the CLI does the same).
 */
class ChangePasswordUseCase(
    private val auth: AuthRepository,
    private val signIn: SignInUseCase,
) {
    suspend operator fun invoke(
        pending: SignInResult.PasswordChangeRequired,
        currentPassword: String,
        replacement: String,
        confirmation: String,
    ): SignInResult.SignedIn {
        requireInput(replacement == confirmation, InvalidInput.Problem.PASSWORDS_MISMATCH, message = "The passwords do not match.")
        requireInput(replacement.length >= MIN_PASSWORD_LENGTH, InvalidInput.Problem.PASSWORD_SHORT, MIN_PASSWORD_LENGTH, "Use at least $MIN_PASSWORD_LENGTH characters.")
        requireInput(replacement != currentPassword, InvalidInput.Problem.PASSWORD_SAME, message = "Choose a password different from the temporary one.")
        auth.changePassword(pending.server.origin, pending.temporaryToken, currentPassword, replacement)
        // The change revoked the temporary session: a failed sign-in now cannot be retried with it.
        val result = try {
            signIn(pending.server, pending.username, replacement, pending.deviceName)
        } catch (error: CancellationException) {
            throw error
        } catch (error: CompanionException.UnsupportedApi) {
            // Signing in again with the new password would end the same way: said now.
            throw error
        } catch (_: Exception) {
            throw CompanionException.PasswordChanged()
        }
        return when (result) {
            is SignInResult.SignedIn -> result
            // The server asks for another change right after this one: the change itself went through, so the member
            // starts over from the sign-in form with the new password.
            is SignInResult.PasswordChangeRequired -> throw CompanionException.PasswordChanged()
        }
    }

    /** The member left before changing the password: the temporary session is revoked rather than left valid. */
    fun abandon(pending: SignInResult.PasswordChangeRequired) = auth.abandon(pending.server.origin, pending.temporaryToken)

    companion object {
        const val MIN_PASSWORD_LENGTH = 14
    }
}
