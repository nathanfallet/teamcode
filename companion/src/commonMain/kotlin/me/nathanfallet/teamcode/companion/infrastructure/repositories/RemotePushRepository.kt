package me.nathanfallet.teamcode.companion.infrastructure.repositories

import me.nathanfallet.teamcode.companion.domain.repositories.PushRepository

class RemotePushRepository(private val calls: AccountCalls) : PushRepository {
    override suspend fun register(accountId: String, token: String, publicKey: String, platform: String) = calls.withAccount(accountId) { it.registerPush(token, publicKey, platform) }
    override suspend fun clear(accountId: String) = calls.withAccount(accountId) { it.clearPush() }
}
