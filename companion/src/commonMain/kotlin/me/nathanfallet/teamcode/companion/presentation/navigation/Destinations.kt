package me.nathanfallet.teamcode.companion.presentation.navigation

import kotlinx.serialization.Serializable

/** Enter a server origin and check it is a TeamCode server. */
@Serializable
object AddServerRoute

/** Password sign-in on [origin]; [username] is set when an existing account must sign in again. */
@Serializable
data class LoginRoute(val origin: String, val username: String? = null)

/** The three tabs of a signed-in app: Sessions, Artifacts, Account. */
@Serializable
object MainRoute

/** One cloud session of an account: header and live transcript. */
@Serializable
data class SessionRoute(val accountId: String, val sessionId: String)

/** The new-session sheet's account (a sheet over the Sessions tab, not a destination of its own). */
@Serializable
data class NewSessionRoute(val accountId: String)

/** An account's artifact gallery; [sessionId] keeps those one cloud session published. */
@Serializable
data class ArtifactsRoute(val accountId: String, val sessionId: String? = null)

/** One artifact of an account, at [version] (the current one when null). */
@Serializable
data class ArtifactRoute(val accountId: String, val artifactId: String, val version: Int? = null)
