package me.nathanfallet.teamcode.domain.routing

import me.nathanfallet.teamcode.domain.gateway.WireProtocol

/** Opaque hashes only: client session identifiers and conversation content are never persisted. */
data class RequestAffinity(val sessionKey: String, val conversationKey: String, val requestedModel: String, val routeKey: String, val protocol: WireProtocol, val effort: String? = null, val thinkingTokens: Long? = null) {
    init {
        require(listOf(sessionKey, conversationKey, routeKey).all { Regex("[a-f0-9]{64}").matches(it) })
        require(Regex("[a-zA-Z0-9_.:/-]{1,120}").matches(requestedModel))
    }
}
