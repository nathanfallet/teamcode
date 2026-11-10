package me.nathanfallet.teamcode.companion.domain.repositories

/** This installation's push destination on an account's server (`notifications.push.v1`). */
interface PushRepository {
    suspend fun register(accountId: String, token: String, publicKey: String)
    suspend fun clear(accountId: String)
}
