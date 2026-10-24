package me.nathanfallet.teamcode.companion.domain.repositories

/** What the update banner remembers across restarts. */
interface UpdatePreferences {
    /** The version whose banner the member closed: it does not show again for that version. */
    var dismissedVersion: String?
    /** When the last check was attempted, successful or not (epoch milliseconds). */
    var lastAttemptMillis: Long?
    /** The latest stable version the last successful check found; null when none, or never checked. */
    var latestVersion: String?
}
