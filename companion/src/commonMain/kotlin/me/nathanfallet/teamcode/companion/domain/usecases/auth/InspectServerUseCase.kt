package me.nathanfallet.teamcode.companion.domain.usecases.auth

import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.ServerInfo
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository

/** Reads `/api/v1/capabilities` of the typed origin, before any password is asked. */
class InspectServerUseCase(private val auth: AuthRepository) {
    suspend operator fun invoke(input: String): ServerInfo {
        val info = auth.serverInfo(normalizeOrigin(input))
        if (info.apiVersion != SUPPORTED_API_VERSION) throw CompanionException.UnsupportedApi(info.apiVersion)
        return info
    }

    companion object {
        const val SUPPORTED_API_VERSION = 1
    }
}
