package me.nathanfallet.teamcode.companion.domain.usecases.updates

import me.nathanfallet.teamcode.api.models.ReleaseFormat
import me.nathanfallet.teamcode.api.models.ReleaseVersion

/**
 * When the update banner shows, and for which version. The CLI and the app ship under one version number, and the
 * update leaves both on the newer of the latest stable release and the CLI's own version ([target]).
 */
object UpdatePolicy {
    /** At most one check in this long, across restarts: one check costs 3 of GitHub's 60 unauthenticated requests per hour. */
    const val MIN_CHECK_INTERVAL_MILLIS = 60 * 60_000L

    /** While the app runs, it checks again this often. */
    const val CHECK_INTERVAL_MILLIS = 6 * 60 * 60_000L

    /**
     * The version an update would bring, or null when there is nothing to offer: no CLI (nothing to update with),
     * a development build of the app, or the app and the CLI already on the latest release or newer.
     */
    fun target(appVersion: String, cliVersion: String?, latestVersion: String?): String? {
        val app = parse(appVersion) ?: return null
        if (app.value.endsWith("-SNAPSHOT")) return null
        val cli = cliVersion?.let(::parse) ?: return null
        val latest = latestVersion?.let(::parse)
        val target = if (latest != null && latest > cli) latest else cli
        return target.value.takeIf { target > app || (latest != null && latest > cli) }
    }

    /** How long to wait before the next check, given the last attempt; 0 when it is due (or the clock went back). */
    fun nextCheckDelay(nowMillis: Long, lastAttemptMillis: Long?, interval: Long = MIN_CHECK_INTERVAL_MILLIS): Long {
        val last = lastAttemptMillis ?: return 0
        val elapsed = nowMillis - last
        return if (elapsed < 0 || elapsed >= interval) 0 else interval - elapsed
    }

    private fun parse(value: String): ReleaseVersion? = if (ReleaseFormat.validVersion(value)) ReleaseVersion.parse(value) else null
}
