package me.nathanfallet.teamcode.domain.cloud

import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.nathanfallet.teamcode.domain.team.Principal
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
 *
 * A remote session has no pod: its host presents its owner's own login token ([logins]), accepted only for an
 * interactive login of the owner from the session's device; a call for an epoch that is not the current, live one is
 * fenced the same way. Revoking the login or removing the device stops the host at its next call.
 */
class AuthenticateRunnerUseCase(
    private val store: CloudSessionStore,
    private val cluster: ClusterPort,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Resolves a member's login token; null refuses every remote host. */
    private val logins: (suspend (String) -> Principal?)? = null,
) {
    private data class Cached(val identity: TokenIdentity?, val until: Long)
    // Accepted and refused reviews are kept apart, so a flood of bad tokens can never evict a runner's review.
    private val accepted = LinkedHashMap<String, Cached>()
    private val refused = LinkedHashMap<String, Cached>()
    private val lock = Mutex()

    suspend operator fun invoke(token: String?, teamId: String, sessionId: String, epoch: Int): RunnerIdentity {
        if (token == null || token.length !in 16..16_384 || token.any { it.isWhitespace() || it.isISOControl() } ||
            !identifier.matches(teamId) || !identifier.matches(sessionId) || epoch !in 1..1_000_000) throw unauthenticated()
        val session = store.find(teamId, sessionId) ?: throw unauthenticated()
        if (session.kind == CloudSessionKind.REMOTE) return host(session, token, epoch)
        val identity = review(session.clusterId, token) ?: throw unauthenticated()
        val pod = identity.podName ?: throw unauthenticated()
        if (identity.namespace != session.namespace || !pod.startsWith("tc-${session.id}-e")) throw unauthenticated()
        if (pod != CloudSessionService.podName(session.id, epoch) || epoch != session.currentEpoch)
            throw TeamFailure("execution_fenced", 409, "This execution is no longer current. Stop the agent and exit without checkpointing.")
        return RunnerIdentity(teamId, sessionId, epoch, pod)
    }

    private suspend fun host(session: CloudSession, token: String, epoch: Int): RunnerIdentity {
        val principal = logins?.invoke(token) ?: throw unauthenticated()
        if (principal.serviceScopes != null || principal.member.passwordChangeRequired || principal.teamId != session.teamId || principal.member.id != session.ownerId)
            throw unauthenticated()
        val device = session.host?.deviceId
        if (device == null || store.deviceOf(principal) != device) throw unauthenticated()
        val execution = store.execution(session.id, epoch)
        if (epoch != session.currentEpoch || execution == null || execution.state == ExecutionState.ENDED)
            throw TeamFailure("execution_fenced", 409, "This execution is no longer current. Stop the agent and exit without checkpointing.")
        return RunnerIdentity(session.teamId, session.id, epoch, execution.podName)
    }

    private suspend fun review(clusterId: String, token: String): TokenIdentity? {
        val key = clusterId + ":" + MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        val now = clock()
        lock.withLock { (accepted[key] ?: refused[key])?.takeIf { it.until > now }?.let { return it.identity } }
        val identity = cluster.reviewToken(clusterId, token, AUDIENCE)
        lock.withLock {
            // Refusals are cached briefly too, so a repeated bad token is not a cluster call every time.
            val cache = if (identity == null) refused else accepted
            cache.entries.removeIf { it.value.until <= now }
            if (cache.size >= MAX_CACHED) cache.remove(cache.keys.first())
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
