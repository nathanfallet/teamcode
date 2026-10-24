package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.Passwords
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Login throttling in MySQL, so every replica shares it (audit M3). Two kinds of rows:
 * - a source address gets [SOURCE_ATTEMPTS] attempts a minute from clients that are not a trusted device, whatever the
 *   username, so one address cannot spray guesses, and no address can use up anyone else's budget;
 * - a failure key (a username from untrusted clients, or one trusted device of a member) backs off exponentially after
 *   [FREE_FAILURES] consecutive failures, from one minute up to [MAX_LOCK_MILLIS]. A trusted device has its own key,
 *   so failures sent by others never lock a member out of the devices they already signed in from.
 */
internal object SqlLoginThrottle {
    const val SOURCE_ATTEMPTS = 30
    const val FREE_FAILURES = 5
    const val FIRST_LOCK_MILLIS = 60_000L
    const val MAX_LOCK_MILLIS = 15 * 60_000L
    /** Failures older than this are forgotten, and their rows purged. */
    const val RETENTION_MILLIS = 24 * 3_600_000L

    fun sourceKey(address: String?) = Passwords.digest("source:" + (address ?: "unknown"))
    fun usernameKey(username: String) = Passwords.digest("username:" + username.lowercase())
    fun deviceKey(memberId: String, device: String) = Passwords.digest("device:$memberId:$device")

    /** Counts the attempt (a refusal too, capped) and tells whether it is within the source's budget. */
    fun admitSource(c: JdbcTransaction, key: String): Boolean {
        val row = lock(c, key)
        val now = c.nowMillis()
        val window = now / 60_000
        val attempts = if (row[LoginThrottle.windowNumber] == window) row[LoginThrottle.attempts] else 0
        LoginThrottle.update({ LoginThrottle.key eq key }) {
            it[windowNumber] = window; it[LoginThrottle.attempts] = minOf(attempts + 1, SOURCE_ATTEMPTS + 1); it[updatedAt] = now
        }
        return attempts < SOURCE_ATTEMPTS
    }
    /** Locks the failure row for the rest of the transaction, so concurrent guesses are counted one by one. */
    fun isLocked(c: JdbcTransaction, key: String): Boolean = lock(c, key)[LoginThrottle.lockedUntil] > c.nowMillis()
    fun recordFailure(c: JdbcTransaction, key: String) {
        val row = lock(c, key)
        val now = c.nowMillis()
        val previous = if (row[LoginThrottle.updatedAt] < now - RETENTION_MILLIS) 0 else row[LoginThrottle.failures]
        val failures = minOf(previous + 1, 1_000)
        LoginThrottle.update({ LoginThrottle.key eq key }) {
            it[LoginThrottle.failures] = failures; it[lockedUntil] = if (failures < FREE_FAILURES) 0 else now + lockMillis(failures); it[updatedAt] = now
        }
    }
    fun clear(key: String) { LoginThrottle.deleteWhere { LoginThrottle.key eq key } }
    /** Rows with neither a recent failure nor a running lock or window; bounded per pass. */
    fun purge(c: JdbcTransaction, limit: Int): Int {
        val now = c.nowMillis()
        return LoginThrottle.deleteWhere(limit = limit) { (updatedAt less now - RETENTION_MILLIS) and (lockedUntil less now) }
    }
    /** One minute at the [FREE_FAILURES]th failure, doubling with each further one, at most [MAX_LOCK_MILLIS]. */
    fun lockMillis(failures: Int): Long = minOf(FIRST_LOCK_MILLIS shl minOf(failures - FREE_FAILURES, 10), MAX_LOCK_MILLIS)

    private fun lock(c: JdbcTransaction, key: String): ResultRow {
        val now = c.nowMillis()
        LoginThrottle.upsert(onUpdate = { it[LoginThrottle.key] = LoginThrottle.key }) { it[LoginThrottle.key] = key; it[updatedAt] = now }
        return LoginThrottle.selectAll().where { LoginThrottle.key eq key }.forUpdate().single()
    }
}
