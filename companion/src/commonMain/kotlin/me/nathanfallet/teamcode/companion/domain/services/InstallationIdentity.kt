package me.nathanfallet.teamcode.companion.domain.services

/**
 * The non-secret identifier of this installation, drawn on first use and kept across sign-outs, so the server lists
 * the app as one device (as the CLI's `installation-id`).
 */
interface InstallationIdentity {
    fun id(): String
}
