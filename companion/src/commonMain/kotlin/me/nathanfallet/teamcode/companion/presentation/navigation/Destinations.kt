package me.nathanfallet.teamcode.companion.presentation.navigation

import kotlinx.serialization.Serializable

/** Enter a server origin and check it is a TeamCode server. */
@Serializable
object AddServerRoute

/** Password sign-in on [origin]; [username] is set when an existing account must sign in again. */
@Serializable
data class LoginRoute(val origin: String, val username: String? = null)

/** The active account. */
@Serializable
object HomeRoute

@Serializable
object SettingsRoute

/** One cloud session of an account: header and live transcript. */
@Serializable
data class SessionRoute(val accountId: String, val sessionId: String)

/** The new-session form of an account. */
@Serializable
data class NewSessionRoute(val accountId: String)
