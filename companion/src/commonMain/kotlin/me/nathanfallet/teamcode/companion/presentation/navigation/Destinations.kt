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
