package me.nathanfallet.teamcode.companion.domain.usecases.auth

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
        if (replacement != confirmation) throw IllegalArgumentException("The passwords do not match.")
        if (replacement.length < MIN_PASSWORD_LENGTH) throw IllegalArgumentException("Use at least $MIN_PASSWORD_LENGTH characters.")
        if (replacement == currentPassword) throw IllegalArgumentException("Choose a password different from the temporary one.")
        auth.changePassword(pending.server.origin, pending.temporaryToken, currentPassword, replacement)
        // The change revoked the temporary session: a failed sign-in now cannot be retried with it.
        val result = try {
            signIn(pending.server, pending.username, replacement, pending.deviceName)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw CompanionException.PasswordChanged()
        }
        return when (result) {
            is SignInResult.SignedIn -> result
            // The server asked for another change right after one: nothing the app can do but report it.
            is SignInResult.PasswordChangeRequired -> throw CompanionException.PasswordRejected(null)
        }
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 14
    }
}
