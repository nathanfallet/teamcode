package me.nathanfallet.teamcode.domain.cloud

import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** The pod a runner call comes from, as proven by the cluster. */
data class RunnerIdentity(val teamId: String, val sessionId: String, val epoch: Int, val podName: String)

/**
 * Authenticates a runner by its projected service-account token: the session's cluster reviews the token
 * (TokenReview, audience [AUDIENCE]), and the namespace and pod name bound into it must be the session's
 * namespace and a pod of this session. The pod name always comes from the review, never from the request.
 *
 * Reviews are cached for at most [CACHE_MILLIS] per token and cluster, so a 15-second heartbeat costs one review
 * a minute. A pod of the session with another epoch is fenced (`409 execution_fenced`) so it exits; anything else
 * is `401 runner_unauthenticated`, without saying why.
 */
class AuthenticateRunnerUseCase(
    private val store: CloudSessionStore,
    private val cluster: ClusterPort,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Cached(val identity: TokenIdentity?, val until: Long)
    private val cache = LinkedHashMap<String, Cached>()
    private val lock = Mutex()

    suspend operator fun invoke(token: String?, teamId: String, sessionId: String, epoch: Int): RunnerIdentity {
        if (token == null || token.length !in 16..16_384 || token.any { it.isWhitespace() || it.isISOControl() } ||
            !identifier.matches(teamId) || !identifier.matches(sessionId) || epoch !in 1..1_000_000) throw unauthenticated()
        val session = store.find(teamId, sessionId) ?: throw unauthenticated()
        val identity = review(session.clusterId, token) ?: throw unauthenticated()
        val pod = identity.podName ?: throw unauthenticated()
        if (identity.namespace != session.namespace || !pod.startsWith("tc-${session.id}-e")) throw unauthenticated()
        if (pod != CloudSessionService.podName(session.id, epoch) || epoch != session.currentEpoch)
            throw TeamFailure("execution_fenced", 409, "This execution is no longer current. Stop the agent and exit without checkpointing.")
        return RunnerIdentity(teamId, sessionId, epoch, pod)
    }

    private suspend fun review(clusterId: String, token: String): TokenIdentity? {
        val key = clusterId + ":" + MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        val now = clock()
        lock.withLock { cache[key]?.takeIf { it.until > now }?.let { return it.identity } }
        val identity = cluster.reviewToken(clusterId, token, AUDIENCE)
        lock.withLock {
            cache.entries.removeIf { it.value.until <= now }
            if (cache.size >= MAX_CACHED) cache.remove(cache.keys.first())
            // Refusals are cached briefly too, so a bad token cannot turn every request into a cluster call.
            cache[key] = Cached(identity, now + if (identity == null) REFUSAL_CACHE_MILLIS else CACHE_MILLIS)
        }
        return identity
    }

    private fun unauthenticated() = TeamFailure("runner_unauthenticated", 401, "The runner could not be authenticated.")

    companion object {
        const val AUDIENCE = "teamcode"
        const val CACHE_MILLIS = 60_000L
        const val REFUSAL_CACHE_MILLIS = 10_000L
        const val MAX_CACHED = 4_096
        private val identifier = Regex("[a-zA-Z0-9-]{1,36}")
    }
}
