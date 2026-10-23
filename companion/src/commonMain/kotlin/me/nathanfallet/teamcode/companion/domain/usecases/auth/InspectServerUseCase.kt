package me.nathanfallet.teamcode.companion.domain.usecases.auth

import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.ServerInfo
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository

/**
 * Checks the typed origin before any password is asked: an `https` origin (or loopback) that answers like TeamCode.
 * The server's version and capabilities are behind authentication, so its API version is checked here only when it is
 * announced without a credential, and otherwise by [SignInUseCase] with the session it creates.
 */
class InspectServerUseCase(private val auth: AuthRepository) {
    suspend operator fun invoke(input: String): ServerInfo {
        val info = auth.discover(normalizeOrigin(input))
        info.apiVersion?.let(::requireSupportedApi)
        return info
    }

    companion object {
        const val SUPPORTED_API_VERSION = 1
    }
}

internal fun requireSupportedApi(apiVersion: Int) {
    if (apiVersion != InspectServerUseCase.SUPPORTED_API_VERSION) throw CompanionException.UnsupportedApi(apiVersion)
}
