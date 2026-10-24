package me.nathanfallet.teamcode.companion.domain.services

/**
 * Updates of the app and the CLI together, through the installed CLI (`teamcode update`, then the app's own
 * installation, as `teamcode ui` does it). The app never downloads or verifies a release itself: the CLI authenticates
 * every release with its compiled publisher keys.
 *
 * Only the desktop app's module binds it. The mobile apps (iOS, Android, iPad included) ship through their own store
 * release track, separate from the CLI and desktop releases: the CLI's version must never drive an update prompt there,
 * so they bind none and the update banner never shows.
 */
interface AppUpdater {
    /** The version of this app. */
    val appVersion: String

    /** The version the installed CLI reports (`teamcode version`); null when no CLI answers. No network. */
    suspend fun cliVersion(): String?

    /**
     * The latest stable version, as the installed CLI authenticated it (`teamcode update --check`); null when no
     * stable release is published. Throws on any failure (no CLI, offline, rate limited): the caller stays silent.
     */
    suspend fun latestVersion(): String?

    /**
     * Updates the CLI, then installs the app of the same version, and arranges for the app to reopen once it has quit.
     * [output] receives the CLI's lines as they come. A refusal is an [AppUpdateException] with a sentence to show.
     */
    suspend fun update(output: (String) -> Unit)

    /** Quits the app, so that the version just installed opens. */
    fun quit()

    /** Why the last update failed, when the app was reopened on its previous version to say so; given once. */
    fun takeStartupError(): String? = null
}

/** An update refusal with a sentence for the member (the CLI's own explanation, never a response body). */
class AppUpdateException(override val message: String) : Exception(message)
