package me.nathanfallet.teamcode.companion.domain.usecases.updates

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
        // Never below the app itself (a newer app than the CLI and the release); the app's own version when only the CLI is behind.
        return target.value.takeIf { target > app || (target == app && latest != null && latest > cli) }
    }

    /** How long to wait before the next check, given the last attempt; 0 when it is due (or the clock went back). */
    fun nextCheckDelay(nowMillis: Long, lastAttemptMillis: Long?, interval: Long = MIN_CHECK_INTERVAL_MILLIS): Long {
        val last = lastAttemptMillis ?: return 0
        val elapsed = nowMillis - last
        return if (elapsed < 0 || elapsed >= interval) 0 else interval - elapsed
    }

    private fun parse(value: String): Version? = if (value.length <= 80 && pattern.matches(value)) Version(value) else null

    private val pattern = Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")

    /**
     * SemVer ordering, prereleases included. The domain knows no API contract, so this is not the CLI's `ReleaseVersion`:
     * the versions it compares come out of the CLI, which already verified them.
     */
    private class Version(val value: String) : Comparable<Version> {
        private val core = value.substringBefore('-').split('.').map { it.toLong() }
        private val prerelease = value.substringAfter('-', "").takeIf { it.isNotEmpty() }?.split('.')

        override fun compareTo(other: Version): Int {
            for (index in core.indices) core[index].compareTo(other.core[index]).let { if (it != 0) return it }
            val left = prerelease ?: return if (other.prerelease == null) 0 else 1
            val right = other.prerelease ?: return -1
            for (index in 0 until minOf(left.size, right.size)) {
                val a = left[index].toLongOrNull()
                val b = right[index].toLongOrNull()
                val comparison = when {
                    a != null && b != null -> a.compareTo(b)
                    a != null -> -1
                    b != null -> 1
                    else -> left[index].compareTo(right[index])
                }
                if (comparison != 0) return comparison
            }
            return left.size.compareTo(right.size)
        }

        override fun equals(other: Any?) = other is Version && compareTo(other) == 0
        override fun hashCode() = value.hashCode()
    }
}
